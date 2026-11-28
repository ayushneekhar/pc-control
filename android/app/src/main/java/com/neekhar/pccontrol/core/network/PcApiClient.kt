package com.neekhar.pccontrol.core.network

import com.neekhar.pccontrol.core.storage.AppPrefs
import com.neekhar.pccontrol.core.storage.AppPrefsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared HTTP & SSE client for PC Control.
 *
 * Transport rules:
 * - All `/pc/api/...` requests route through the unified dynamic transport ([ApiTransport.CLOUDFLARE]
 *   at `apiUrl` with dual Cloudflare Access + API Bearer auth, or [ApiTransport.TAILSCALE] at
 *   `serverUrl` when promoted via a one-shot 500 ms probe while Tailscale VPN is active).
 * - If a request over [ApiTransport.TAILSCALE] fails with a network/connection error
 *   (`statusCode == 0`), the client immediately flips active transport to [ApiTransport.CLOUDFLARE]
 *   and retries seamlessly.
 * - Auth headers (`Authorization`, `CF-Access-Client-Id`, `CF-Access-Client-Secret`) are strictly
 *   scoped to `apiUrl`'s exact origin (scheme + host + port) and never sent to Tailscale or
 *   external hosts.
 * - Video streaming (go2rtc WebRTC `/api/ws`, `/api/frame.jpeg`, HLS replay `/rec/...`) stays
 *   explicitly pinned to [tailscaleBaseUrl].
 */
class PcApiClient(
    private val prefs: AppPrefs,
    okHttpClient: OkHttpClient? = null,
    private val getRetryBackoffMs: Long = GET_RETRY_BACKOFF_MS,
    transportManager: ApiTransportManager? = null,
) {
    val json: Json = DefaultJson

    val okHttpClient: OkHttpClient = okHttpClient ?: OkHttpClient.Builder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 20
            },
        )
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
            if (original.header("User-Agent").isNullOrBlank()) {
                builder.header("User-Agent", USER_AGENT)
            }
            val snap = prefs.current.value
            if (snap.isPublicApiConfigured && isSameOrigin(original.url.toString(), snap.apiUrl)) {
                buildCloudflareAuthHeaders(snap).forEach { (k, v) ->
                    if (original.header(k) == null) {
                        builder.header(k, v)
                    }
                }
            }
            chain.proceed(builder.build())
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val sseOkHttpClient: OkHttpClient = this.okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val transportManager: ApiTransportManager = transportManager ?: ApiTransportManager(
        prefs = prefs,
        probeClient = this.okHttpClient,
    )

    /** Live active API transport ([ApiTransport.CLOUDFLARE] vs [ApiTransport.TAILSCALE]). */
    val activeTransport: StateFlow<ApiTransport>
        get() = transportManager.activeTransport

    fun reprobeTransport() {
        transportManager.reprobeNow()
    }

    fun reportTailscaleFailure() {
        transportManager.reportTailscaleFailure()
    }

    /** Explicit Tailscale Serve origin (no trailing slash) for WebRTC cams, HLS replay & port links. */
    val tailscaleBaseUrl: String
        get() = prefs.current.value.serverUrl

    /**
     * Tailscale Serve origin (alias of [tailscaleBaseUrl]) kept for direct-media & port-link callers
     * (`/api/ws`, `/api/frame.jpeg`, `/rec/...`, `:7878`, `:8989`).
     */
    val baseUrl: String
        get() = tailscaleBaseUrl

    /** Active API origin for `/pc/api/...` calls (Cloudflare `apiUrl` or Tailscale `serverUrl`). */
    val apiBaseUrl: String
        get() = resolveApiTarget(prefs.current.value).baseUrl

    /** Active chat origin (now unified with [apiBaseUrl]). */
    val chatBaseUrl: String
        get() = apiBaseUrl

    /** Configured public API Bearer token (falls back to legacy chatToken if unset). */
    val chatToken: String
        get() = prefs.current.value.apiToken.ifBlank { prefs.current.value.chatToken }

    /**
     * Resolves the current unified API target (transport + origin + strictly scoped auth headers).
     */
    fun resolveApiTarget(
        snapshot: AppPrefsSnapshot = prefs.current.value,
        forceTailscale: Boolean = false,
    ): ResolvedApiTarget {
        if (forceTailscale || !snapshot.isPublicApiConfigured) {
            return ResolvedApiTarget(
                transport = ApiTransport.TAILSCALE,
                baseUrl = snapshot.serverUrl.trim().trimEnd('/'),
                headers = emptyMap(),
            )
        }
        return when (transportManager.resolveTransport(snapshot)) {
            ApiTransport.CLOUDFLARE -> ResolvedApiTarget(
                transport = ApiTransport.CLOUDFLARE,
                baseUrl = snapshot.apiUrl.trim().trimEnd('/'),
                headers = buildCloudflareAuthHeaders(snapshot),
            )
            ApiTransport.TAILSCALE -> ResolvedApiTarget(
                transport = ApiTransport.TAILSCALE,
                baseUrl = snapshot.serverUrl.trim().trimEnd('/'),
                headers = emptyMap(),
            )
        }
    }

    fun resolveChatTarget(snapshot: AppPrefsSnapshot = prefs.current.value): ResolvedChatTarget {
        val target = resolveApiTarget(snapshot)
        return ResolvedChatTarget(
            baseUrl = target.baseUrl,
            headers = target.headers,
            isCustom = target.transport == ApiTransport.CLOUDFLARE,
        )
    }

    /**
     * Resolves a relative or absolute path:
     * - Relative `/pc/...` paths use the active API origin ([apiBaseUrl]).
     * - Other relative paths (e.g. `/api/frame.jpeg`, `/rec/...`) use [tailscaleBaseUrl].
     */
    fun resolveUrl(pathOrUrl: String, useChatTarget: Boolean = false): String {
        val trimmed = pathOrUrl.trim()
        if (HTTP_PREFIX.containsMatchIn(trimmed)) return trimmed
        val normalizedPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        val origin = if (useChatTarget || isPcApiPath(normalizedPath)) apiBaseUrl else tailscaleBaseUrl
        return "$origin$normalizedPath"
    }

    /**
     * Resolves an authenticated API image URL (e.g. `/pc/api/notify/image/...`,
     * `/pc/api/catdetect/snaps/...`, `/pc/api/chat/file/...`, `/pc/api/jellyfin/image/...`)
     * returning `(url, headers)` with Cloudflare auth headers attached ONLY when targeting `apiUrl`.
     */
    fun resolveAuthenticatedImage(
        pathOrUrl: String,
        snapshot: AppPrefsSnapshot = prefs.current.value,
    ): Pair<String, Map<String, String>> {
        val trimmed = pathOrUrl.trim()
        if (trimmed.isEmpty()) return "" to emptyMap()
        val isAbsolute = HTTP_PREFIX.containsMatchIn(trimmed)
        if (isAbsolute) {
            val headers = if (snapshot.isPublicApiConfigured && isSameOrigin(trimmed, snapshot.apiUrl)) {
                buildCloudflareAuthHeaders(snapshot)
            } else {
                emptyMap()
            }
            return trimmed to headers
        }
        val normalizedPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return if (isPcApiPath(normalizedPath)) {
            val target = resolveApiTarget(snapshot)
            "${target.baseUrl}$normalizedPath" to target.headers
        } else {
            "${snapshot.serverUrl.trim().trimEnd('/')}$normalizedPath" to emptyMap()
        }
    }

    /**
     * Resolves a chat attachment URL (relative or absolute) and returns its URL plus any
     * auth headers required (scoped strictly to the Cloudflare API origin).
     */
    fun resolveChatAttachment(url: String): Pair<String, Map<String, String>> =
        resolveAuthenticatedImage(url)

    suspend inline fun <reified T> getJson(
        pathOrUrl: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        useChatTarget: Boolean = false,
        forceTailscale: Boolean = false,
    ): ApiResult<T> = executeJson(
        method = "GET",
        pathOrUrl = pathOrUrl,
        requestBody = null,
        headers = headers,
        timeoutMs = timeoutMs,
        useChatTarget = useChatTarget,
        forceTailscale = forceTailscale,
    )

    suspend inline fun <reified Req, reified Res> postJson(
        pathOrUrl: String,
        body: Req,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_POST_TIMEOUT_MS,
        useChatTarget: Boolean = false,
        forceTailscale: Boolean = false,
    ): ApiResult<Res> {
        val bodyStr = json.encodeToString(body)
        return executeJson(
            method = "POST",
            pathOrUrl = pathOrUrl,
            requestBody = bodyStr.toRequestBody(JSON_MEDIA_TYPE),
            headers = headers,
            timeoutMs = timeoutMs,
            useChatTarget = useChatTarget,
            forceTailscale = forceTailscale,
        )
    }

    suspend inline fun <reified Res> postJson(
        pathOrUrl: String,
        bodyJson: String = "{}",
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_POST_TIMEOUT_MS,
        useChatTarget: Boolean = false,
        forceTailscale: Boolean = false,
    ): ApiResult<Res> = executeJson(
        method = "POST",
        pathOrUrl = pathOrUrl,
        requestBody = bodyJson.toRequestBody(JSON_MEDIA_TYPE),
        headers = headers,
        timeoutMs = timeoutMs,
        useChatTarget = useChatTarget,
        forceTailscale = forceTailscale,
    )

    suspend inline fun <reified Req, reified Res> putJson(
        pathOrUrl: String,
        body: Req,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_POST_TIMEOUT_MS,
        useChatTarget: Boolean = false,
        forceTailscale: Boolean = false,
    ): ApiResult<Res> {
        val bodyStr = json.encodeToString(body)
        return executeJson(
            method = "PUT",
            pathOrUrl = pathOrUrl,
            requestBody = bodyStr.toRequestBody(JSON_MEDIA_TYPE),
            headers = headers,
            timeoutMs = timeoutMs,
            useChatTarget = useChatTarget,
            forceTailscale = forceTailscale,
        )
    }

    suspend inline fun <reified Res> deleteJson(
        pathOrUrl: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_POST_TIMEOUT_MS,
        useChatTarget: Boolean = false,
        forceTailscale: Boolean = false,
    ): ApiResult<Res> = executeJson(
        method = "DELETE",
        pathOrUrl = pathOrUrl,
        requestBody = null,
        headers = headers,
        timeoutMs = timeoutMs,
        useChatTarget = useChatTarget,
        forceTailscale = forceTailscale,
    )

    suspend inline fun <reified Res> postMultipart(
        pathOrUrl: String,
        body: MultipartBody,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_UPLOAD_TIMEOUT_MS,
        useChatTarget: Boolean = false,
        forceTailscale: Boolean = false,
    ): ApiResult<Res> = executeJson(
        method = "POST",
        pathOrUrl = pathOrUrl,
        requestBody = body,
        headers = headers,
        timeoutMs = timeoutMs,
        useChatTarget = useChatTarget,
        forceTailscale = forceTailscale,
    )

    suspend fun getBytes(
        pathOrUrl: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_BYTES_TIMEOUT_MS,
        useChatTarget: Boolean = false,
        forceTailscale: Boolean = false,
    ): ApiResult<ByteArray> {
        val snap = prefs.awaitLoaded()
        val trimmed = pathOrUrl.trim()
        val isAbsolute = HTTP_PREFIX.containsMatchIn(trimmed)
        val effectiveForceTailscale = forceTailscale || (!isAbsolute && isWatchTailscaleOnlyPath(trimmed))
        val useUnifiedTransport = !isAbsolute && !effectiveForceTailscale && (useChatTarget || isPcApiPath(trimmed))

        if (!useUnifiedTransport) {
            val url = if (isAbsolute) trimmed else buildTargetUrl(trimmed, snap.serverUrl)
            val scopedHeaders = if (isAbsolute && snap.isPublicApiConfigured && isSameOrigin(url, snap.apiUrl)) {
                buildCloudflareAuthHeaders(snap)
            } else {
                emptyMap()
            }
            val merged = buildMap {
                put("Accept", "*/*")
                putAll(scopedHeaders)
                putAll(headers)
            }
            val first = executeBytesOnce(url, merged, timeoutMs, isCloudflare = scopedHeaders.isNotEmpty())
            if (isRetryableGetError("GET", first)) {
                delay(getRetryBackoffMs)
                return executeBytesOnce(url, merged, timeoutMs, isCloudflare = scopedHeaders.isNotEmpty())
            }
            return first
        }

        val target = resolveApiTarget(snap)
        val url = buildTargetUrl(trimmed, target.baseUrl)
        val mergedHeaders = buildMap {
            put("Accept", "*/*")
            putAll(target.headers)
            putAll(headers)
        }
        val first = executeBytesOnce(
            url = url,
            headers = mergedHeaders,
            timeoutMs = timeoutMs,
            isCloudflare = target.transport == ApiTransport.CLOUDFLARE,
        )
        if (first is ApiResult.Error &&
            first.statusCode == 0 &&
            target.transport == ApiTransport.TAILSCALE &&
            snap.isPublicApiConfigured
        ) {
            transportManager.reportTailscaleFailure()
            val cfUrl = buildTargetUrl(trimmed, snap.apiUrl)
            val cfHeaders = buildMap {
                put("Accept", "*/*")
                putAll(buildCloudflareAuthHeaders(snap))
                putAll(headers)
            }
            val cfFirst = executeBytesOnce(cfUrl, cfHeaders, timeoutMs, isCloudflare = true)
            if (isRetryableGetError("GET", cfFirst)) {
                delay(getRetryBackoffMs)
                return executeBytesOnce(cfUrl, cfHeaders, timeoutMs, isCloudflare = true)
            }
            return cfFirst
        }
        if (isRetryableGetError("GET", first)) {
            delay(getRetryBackoffMs)
            return executeBytesOnce(
                url = url,
                headers = mergedHeaders,
                timeoutMs = timeoutMs,
                isCloudflare = target.transport == ApiTransport.CLOUDFLARE,
            )
        }
        return first
    }

    @PublishedApi
    internal suspend inline fun <reified T> executeJson(
        method: String,
        pathOrUrl: String,
        requestBody: RequestBody?,
        headers: Map<String, String>,
        timeoutMs: Long,
        useChatTarget: Boolean,
        forceTailscale: Boolean = false,
    ): ApiResult<T> {
        return when (val raw = executeRaw(method, pathOrUrl, requestBody, headers, timeoutMs, useChatTarget, forceTailscale)) {
            is ApiResult.Error -> raw
            is ApiResult.Success -> {
                if (T::class == Unit::class) {
                    @Suppress("UNCHECKED_CAST")
                    return ApiResult.Success(Unit as T, raw.statusCode)
                }
                if (T::class == String::class) {
                    @Suppress("UNCHECKED_CAST")
                    return ApiResult.Success(raw.data as T, raw.statusCode)
                }
                try {
                    val decoded = json.decodeFromString<T>(raw.data)
                    ApiResult.Success(decoded, raw.statusCode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ApiResult.Error(
                        statusCode = raw.statusCode,
                        message = "Invalid JSON response: ${e.message ?: e::class.simpleName}",
                        rawBody = raw.data,
                        cause = e,
                    )
                }
            }
        }
    }

    suspend fun executeRaw(
        method: String,
        pathOrUrl: String,
        requestBody: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_POST_TIMEOUT_MS,
        useChatTarget: Boolean = false,
        forceTailscale: Boolean = false,
    ): ApiResult<String> {
        val snap = prefs.awaitLoaded()
        val trimmed = pathOrUrl.trim()
        val isAbsolute = HTTP_PREFIX.containsMatchIn(trimmed)
        val effectiveForceTailscale = forceTailscale || (!isAbsolute && isWatchTailscaleOnlyPath(trimmed))
        val useUnifiedTransport = !isAbsolute && !effectiveForceTailscale && (useChatTarget || isPcApiPath(trimmed))

        if (!useUnifiedTransport) {
            val url = if (isAbsolute) trimmed else buildTargetUrl(trimmed, snap.serverUrl)
            val scopedHeaders = if (isAbsolute && snap.isPublicApiConfigured && isSameOrigin(url, snap.apiUrl)) {
                buildCloudflareAuthHeaders(snap)
            } else {
                emptyMap()
            }
            val merged = buildMap {
                put("Accept", "application/json")
                putAll(scopedHeaders)
                putAll(headers)
            }
            val first = executeStringOnce(
                method = method,
                url = url,
                requestBody = requestBody,
                headers = merged,
                timeoutMs = timeoutMs,
                isCloudflare = scopedHeaders.isNotEmpty(),
            )
            val result = if (isRetryableGetError(method, first)) {
                delay(getRetryBackoffMs)
                executeStringOnce(
                    method = method,
                    url = url,
                    requestBody = requestBody,
                    headers = merged,
                    timeoutMs = timeoutMs,
                    isCloudflare = scopedHeaders.isNotEmpty(),
                )
            } else {
                first
            }
            if (effectiveForceTailscale && result is ApiResult.Success) {
                transportManager.markTailscaleReachable()
            }
            return result
        }

        val target = resolveApiTarget(snap)
        val url = buildTargetUrl(trimmed, target.baseUrl)
        val mergedHeaders = buildMap {
            put("Accept", "application/json")
            putAll(target.headers)
            putAll(headers)
        }
        val first = executeStringOnce(
            method = method,
            url = url,
            requestBody = requestBody,
            headers = mergedHeaders,
            timeoutMs = timeoutMs,
            isCloudflare = target.transport == ApiTransport.CLOUDFLARE,
        )
        // If Tailscale failed with a network/connection error, flip immediately to Cloudflare and retry.
        if (first is ApiResult.Error &&
            first.statusCode == 0 &&
            target.transport == ApiTransport.TAILSCALE &&
            snap.isPublicApiConfigured
        ) {
            transportManager.reportTailscaleFailure()
            val cfUrl = buildTargetUrl(trimmed, snap.apiUrl)
            val cfHeaders = buildMap {
                put("Accept", "application/json")
                putAll(buildCloudflareAuthHeaders(snap))
                putAll(headers)
            }
            val cfFirst = executeStringOnce(
                method = method,
                url = cfUrl,
                requestBody = requestBody,
                headers = cfHeaders,
                timeoutMs = timeoutMs,
                isCloudflare = true,
            )
            if (isRetryableGetError(method, cfFirst)) {
                delay(getRetryBackoffMs)
                return executeStringOnce(
                    method = method,
                    url = cfUrl,
                    requestBody = requestBody,
                    headers = cfHeaders,
                    timeoutMs = timeoutMs,
                    isCloudflare = true,
                )
            }
            return cfFirst
        }
        if (isRetryableGetError(method, first)) {
            delay(getRetryBackoffMs)
            return executeStringOnce(
                method = method,
                url = url,
                requestBody = requestBody,
                headers = mergedHeaders,
                timeoutMs = timeoutMs,
                isCloudflare = target.transport == ApiTransport.CLOUDFLARE,
            )
        }
        return first
    }

    /**
     * Tests `GET <apiUrl>/pc/api/info` directly with explicit credentials (without mutating
     * saved prefs or sending headers to any other origin). Returns `(ApiResult<String>, latencyMs)`.
     */
    suspend fun testDirectPublicApi(
        apiUrl: String,
        apiToken: String,
        cfAccessClientId: String,
        cfAccessClientSecret: String,
        timeoutMs: Long = 10_000L,
    ): Pair<ApiResult<String>, Long> {
        val cleanUrl = AppPrefs.normalizeApiUrl(apiUrl)
        val headers = buildMap {
            put("Accept", "application/json")
            val tok = apiToken.trim()
            if (tok.isNotEmpty()) put("Authorization", "Bearer $tok")
            val cfId = cfAccessClientId.trim()
            if (cfId.isNotEmpty()) put("CF-Access-Client-Id", cfId)
            val cfSecret = cfAccessClientSecret.trim()
            if (cfSecret.isNotEmpty()) put("CF-Access-Client-Secret", cfSecret)
        }
        val startMs = System.currentTimeMillis()
        val result = executeStringOnce(
            method = "GET",
            url = "$cleanUrl/pc/api/info",
            requestBody = null,
            headers = headers,
            timeoutMs = timeoutMs,
            isCloudflare = true,
        )
        val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
        return result to elapsedMs
    }

    /**
     * Cold Flow of Server-Sent Events from [url] (absolute or relative path).
     * Automatically reconnects on transport switches and with exponential backoff until cancelled.
     */
    fun sseFlow(
        url: String,
        headers: Map<String, String> = emptyMap(),
        useChatTarget: Boolean = false,
        autoReconnect: Boolean = true,
        initialRetryMs: Long = 1_500L,
        maxRetryMs: Long = 30_000L,
    ): Flow<SseEvent> = sseStatusFlow(
        url = url,
        headers = headers,
        useChatTarget = useChatTarget,
        autoReconnect = autoReconnect,
        initialRetryMs = initialRetryMs,
        maxRetryMs = maxRetryMs,
    ).filterIsInstance<SseMessage.Event>().map { it.event }

    /**
     * Cold Flow emitting connection lifecycle ([SseMessage.Connected], [SseMessage.Reconnecting])
     * alongside parsed [SseMessage.Event] items. Reconnects immediately when [activeTransport] switches.
     */
    fun sseStatusFlow(
        url: String,
        headers: Map<String, String> = emptyMap(),
        useChatTarget: Boolean = false,
        autoReconnect: Boolean = true,
        initialRetryMs: Long = 1_500L,
        maxRetryMs: Long = 30_000L,
    ): Flow<SseMessage> = callbackFlow {
        val factory = EventSources.createFactory(sseOkHttpClient)
        var currentSource: EventSource? = null
        var currentStreamEnded: CompletableDeferred<SseEndReason>? = null
        var activeOpenedTransport: ApiTransport? = null

        val transportWatcher = launch(Dispatchers.IO) {
            activeTransport.drop(1).collect { newTransport ->
                if (activeOpenedTransport != null && newTransport != activeOpenedTransport) {
                    currentStreamEnded?.complete(SseEndReason.TransportSwitched)
                    currentSource?.cancel()
                }
            }
        }

        val runner = launch(Dispatchers.IO) {
            var attempt = 0
            var retryDelay = initialRetryMs.coerceAtLeast(250L)

            while (currentCoroutineContext().isActive) {
                val snap = prefs.awaitLoaded()
                val trimmed = url.trim()
                val isAbsolute = HTTP_PREFIX.containsMatchIn(trimmed)
                val target = if (!isAbsolute && (useChatTarget || isPcApiPath(trimmed))) {
                    resolveApiTarget(snap)
                } else {
                    null
                }
                val resolvedUrl = if (isAbsolute) {
                    trimmed
                } else {
                    buildTargetUrl(trimmed, target?.baseUrl ?: snap.serverUrl)
                }
                activeOpenedTransport = target?.transport

                val reqBuilder = Request.Builder()
                    .url(resolvedUrl)
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", USER_AGENT)
                if (target != null) {
                    target.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                } else if (isAbsolute && snap.isPublicApiConfigured && isSameOrigin(resolvedUrl, snap.apiUrl)) {
                    buildCloudflareAuthHeaders(snap).forEach { (k, v) -> reqBuilder.header(k, v) }
                }
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }

                val streamEnded = CompletableDeferred<SseEndReason>()
                currentStreamEnded = streamEnded

                val listener = object : EventSourceListener() {
                    override fun onOpen(eventSource: EventSource, response: Response) {
                        attempt = 0
                        retryDelay = initialRetryMs.coerceAtLeast(250L)
                        trySend(SseMessage.Connected)
                    }

                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        trySend(
                            SseMessage.Event(
                                SseEvent(
                                    id = id,
                                    event = type ?: "message",
                                    data = data,
                                ),
                            ),
                        )
                    }

                    override fun onClosed(eventSource: EventSource) {
                        streamEnded.complete(SseEndReason.Closed)
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?,
                    ) {
                        val isTransportError = response == null
                        if (isTransportError &&
                            target?.transport == ApiTransport.TAILSCALE &&
                            snap.isPublicApiConfigured
                        ) {
                            transportManager.reportTailscaleFailure()
                            streamEnded.complete(SseEndReason.TransportSwitched)
                            return
                        }
                        val reason = t?.message ?: response?.let { "HTTP ${it.code}" }
                        streamEnded.complete(SseEndReason.Failed(reason))
                    }
                }

                val source = factory.newEventSource(reqBuilder.build(), listener)
                currentSource = source

                val endReason = try {
                    streamEnded.await()
                } finally {
                    source.cancel()
                    if (currentSource === source) currentSource = null
                    if (currentStreamEnded === streamEnded) currentStreamEnded = null
                }

                if (!autoReconnect || !currentCoroutineContext().isActive) {
                    break
                }

                if (endReason == SseEndReason.TransportSwitched) {
                    attempt = 0
                    retryDelay = initialRetryMs.coerceAtLeast(250L)
                    continue
                }

                val failureReason = (endReason as? SseEndReason.Failed)?.reason
                attempt += 1
                trySend(SseMessage.Reconnecting(attempt = attempt, delayMs = retryDelay, error = failureReason))
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(maxRetryMs)
            }
            close()
        }

        awaitClose {
            transportWatcher.cancel()
            currentSource?.cancel()
            runner.cancel()
        }
    }

    private sealed interface SseEndReason {
        data object Closed : SseEndReason
        data object TransportSwitched : SseEndReason
        data class Failed(val reason: String?) : SseEndReason
    }

    private suspend fun executeStringOnce(
        method: String,
        url: String,
        requestBody: RequestBody?,
        headers: Map<String, String>,
        timeoutMs: Long,
        isCloudflare: Boolean,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val request = buildRequest(method, url, requestBody, headers)
        val client = clientWithTimeout(timeoutMs)
        try {
            client.newCall(request).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = extractErrorMessage(text) ?: "HTTP ${response.code}"
                    ApiResult.Error(
                        statusCode = response.code,
                        message = message,
                        rawBody = text,
                    )
                } else {
                    ApiResult.Success(data = text, statusCode = response.code)
                }
            }
        } catch (e: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            ApiResult.Error(
                statusCode = 0,
                message = if (isCloudflare) "Public API timed out." else "Timed out. Is Tailscale connected?",
                cause = e,
            )
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            ApiResult.Error(
                statusCode = 0,
                message = if (isCloudflare) {
                    "Can't reach public API (${e.message ?: e::class.simpleName})"
                } else {
                    "Can't reach the laptop. Is Tailscale connected? (${e.message ?: e::class.simpleName})"
                },
                cause = e,
            )
        }
    }

    private suspend fun executeBytesOnce(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        isCloudflare: Boolean,
    ): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        val request = buildRequest("GET", url, null, headers)
        val client = clientWithTimeout(timeoutMs)
        try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    val errText = response.body?.string()
                    val message = extractErrorMessage(errText) ?: "HTTP ${response.code}"
                    ApiResult.Error(
                        statusCode = response.code,
                        message = message,
                        rawBody = errText,
                    )
                } else {
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    ApiResult.Success(data = bytes, statusCode = response.code)
                }
            }
        } catch (e: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            ApiResult.Error(
                statusCode = 0,
                message = if (isCloudflare) "Public API timed out." else "Timed out. Is Tailscale connected?",
                cause = e,
            )
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            ApiResult.Error(
                statusCode = 0,
                message = if (isCloudflare) {
                    "Can't reach public API (${e.message ?: e::class.simpleName})"
                } else {
                    "Can't reach the laptop. Is Tailscale connected? (${e.message ?: e::class.simpleName})"
                },
                cause = e,
            )
        }
    }

    private fun buildRequest(
        method: String,
        url: String,
        requestBody: RequestBody?,
        headers: Map<String, String>,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> builder.header(k, v) }
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody(JSON_MEDIA_TYPE))
            "PUT" -> builder.put(requestBody ?: "{}".toRequestBody(JSON_MEDIA_TYPE))
            else -> builder.method(method.uppercase(), requestBody)
        }
        return builder.build()
    }

    private fun clientWithTimeout(timeoutMs: Long): OkHttpClient {
        if (timeoutMs <= 0L) return okHttpClient
        return okHttpClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun extractErrorMessage(rawBody: String?): String? {
        if (rawBody.isNullOrBlank()) return null
        return runCatching {
            val obj = json.parseToJsonElement(rawBody) as? JsonObject ?: return null
            obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            },
        )
    }

    companion object {
        const val USER_AGENT = "PCControl-Android"

        @OptIn(ExperimentalSerializationApi::class)
        val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }

        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val DEFAULT_POST_TIMEOUT_MS = 15_000L
        const val DEFAULT_BYTES_TIMEOUT_MS = 20_000L
        const val DEFAULT_UPLOAD_TIMEOUT_MS = 60_000L
        const val GET_RETRY_BACKOFF_MS = 400L

        internal fun isRetryableGetError(method: String, result: ApiResult<*>): Boolean {
            if (!method.equals("GET", ignoreCase = true)) return false
            if (result !is ApiResult.Error) return false
            return result.statusCode == 0 ||
                result.statusCode == 502 ||
                result.statusCode == 503 ||
                result.statusCode == 504
        }

        @PublishedApi
        internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val HTTP_PREFIX = Regex("^https?://", RegexOption.IGNORE_CASE)

        internal fun isPcApiPath(path: String): Boolean {
            val normalized = if (path.startsWith("/")) path else "/$path"
            return normalized == "/pc/api" || normalized.startsWith("/pc/api/")
        }

        internal fun isWatchTailscaleOnlyPath(path: String): Boolean {
            val withoutQuery = path.substringBefore('?')
            val normalized = if (withoutQuery.startsWith("/")) withoutQuery else "/$withoutQuery"
            return normalized == "/pc/api/watch/playback-info" ||
                normalized == "/pc/api/watch/download-info" ||
                normalized == "/pc/api/watch/stream" ||
                normalized.startsWith("/pc/api/watch/stream/") ||
                normalized == "/pc/api/watch/download" ||
                normalized.startsWith("/pc/api/watch/download/") ||
                normalized == "/pc/api/watch/subtitles" ||
                normalized.startsWith("/pc/api/watch/subtitles/")
        }

        internal fun buildTargetUrl(pathOrUrl: String, origin: String): String {
            val trimmed = pathOrUrl.trim()
            if (HTTP_PREFIX.containsMatchIn(trimmed)) return trimmed
            val cleanOrigin = origin.trim().trimEnd('/')
            val normalizedPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return "$cleanOrigin$normalizedPath"
        }

        fun buildCloudflareAuthHeaders(snapshot: AppPrefsSnapshot): Map<String, String> =
            buildCloudflareAuthHeaders(
                apiToken = snapshot.apiToken,
                cfAccessClientId = snapshot.cfAccessClientId,
                cfAccessClientSecret = snapshot.cfAccessClientSecret,
            )

        fun buildCloudflareAuthHeaders(
            apiToken: String,
            cfAccessClientId: String,
            cfAccessClientSecret: String,
        ): Map<String, String> = buildMap {
            val token = apiToken.trim()
            if (token.isNotEmpty()) {
                put("Authorization", "Bearer $token")
            }
            val cfId = cfAccessClientId.trim()
            if (cfId.isNotEmpty()) {
                put("CF-Access-Client-Id", cfId)
            }
            val cfSecret = cfAccessClientSecret.trim()
            if (cfSecret.isNotEmpty()) {
                put("CF-Access-Client-Secret", cfSecret)
            }
        }

        /**
         * Strict origin equality check (scheme + lowercase host + effective port) to guarantee
         * Cloudflare Access and API Bearer credentials are never leaked to Tailscale or third parties.
         */
        fun isSameOrigin(url: String, origin: String): Boolean {
            val cleanOrigin = origin.trim().trimEnd('/')
            if (cleanOrigin.isEmpty()) return false
            val targetUri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            val originUri = runCatching { URI(cleanOrigin) }.getOrNull() ?: return false
            val tScheme = targetUri.scheme?.lowercase() ?: return false
            val oScheme = originUri.scheme?.lowercase() ?: return false
            val tHost = targetUri.host?.lowercase() ?: return false
            val oHost = originUri.host?.lowercase() ?: return false
            if (tScheme != oScheme || tHost != oHost) return false
            return effectivePort(tScheme, targetUri.port) == effectivePort(oScheme, originUri.port)
        }

        private fun effectivePort(scheme: String, port: Int): Int = when {
            port > 0 -> port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> -1
        }

        fun resolveChatTarget(
            defaultBaseUrl: String,
            customChatUrl: String,
            chatToken: String,
        ): ResolvedChatTarget {
            val cleanCustom = AppPrefs.normalizeChatUrl(customChatUrl)
            val cleanToken = chatToken.trim()
            if (cleanCustom.isNotEmpty()) {
                val headers = if (cleanToken.isNotEmpty()) {
                    mapOf("Authorization" to "Bearer $cleanToken")
                } else {
                    emptyMap()
                }
                return ResolvedChatTarget(baseUrl = cleanCustom, headers = headers, isCustom = true)
            }
            return ResolvedChatTarget(
                baseUrl = defaultBaseUrl.trim().trimEnd('/'),
                headers = emptyMap(),
                isCustom = false,
            )
        }

        fun resolveChatAttachmentSource(
            url: String,
            target: ResolvedChatTarget,
        ): Pair<String, Map<String, String>> {
            val trimmed = url.trim()
            val isAbsolute = HTTP_PREFIX.containsMatchIn(trimmed)
            val resolved = if (isAbsolute) {
                trimmed
            } else {
                val path = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
                "${target.baseUrl}$path"
            }
            if (target.headers.isEmpty()) return resolved to emptyMap()
            if (!isAbsolute) return resolved to target.headers
            return if (isSameOrigin(resolved, target.baseUrl)) resolved to target.headers else resolved to emptyMap()
        }
    }
}
