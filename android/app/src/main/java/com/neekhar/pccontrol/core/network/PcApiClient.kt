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
import kotlinx.coroutines.flow.callbackFlow
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared HTTP & SSE client for PC Control.
 *
 * Conventions (matching `src/lib/api.ts` & `src/lib/chatApi.ts`):
 * - Tailscale base URL serves `/pc/...` and `/vitals/api`.
 * - Chat endpoints support either a custom URL + Bearer token, or the Tailscale base URL with
 *   automatic fallback to Cloudflare (`https://agent.neekhar.dev/pc/api/chat`) when a chat Bearer
 *   token is configured and Tailscale is unreachable.
 */
class PcApiClient(
    private val prefs: AppPrefs,
    okHttpClient: OkHttpClient? = null,
    private val getRetryBackoffMs: Long = GET_RETRY_BACKOFF_MS,
) {
    val json: Json = DefaultJson

    val okHttpClient: OkHttpClient = okHttpClient ?: OkHttpClient.Builder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 20
            },
        )
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

    /** Active Tailscale / primary server origin (no trailing slash). */
    val baseUrl: String
        get() = prefs.current.value.serverUrl

    /** Active chat origin (custom chat URL if configured, otherwise [baseUrl]). */
    val chatBaseUrl: String
        get() = resolveChatTarget(prefs.current.value).baseUrl

    /** Configured Bearer token for off-Tailscale chat endpoints, or empty string. */
    val chatToken: String
        get() = prefs.current.value.chatToken

    fun resolveChatTarget(snapshot: AppPrefsSnapshot = prefs.current.value): ResolvedChatTarget =
        resolveChatTarget(
            defaultBaseUrl = snapshot.serverUrl,
            customChatUrl = snapshot.chatUrl,
            chatToken = snapshot.chatToken,
        )

    fun resolveUrl(pathOrUrl: String, useChatTarget: Boolean = false): String {
        val trimmed = pathOrUrl.trim()
        if (HTTP_PREFIX.containsMatchIn(trimmed)) return trimmed
        val origin = if (useChatTarget) chatBaseUrl else baseUrl
        val normalizedPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return "$origin$normalizedPath"
    }

    /**
     * Resolves a chat attachment URL (relative or absolute) and returns its URL plus any
     * `Authorization` header required (scoped strictly to the chat origin).
     */
    fun resolveChatAttachment(url: String): Pair<String, Map<String, String>> =
        resolveChatAttachmentSource(url = url, target = resolveChatTarget())

    suspend inline fun <reified T> getJson(
        pathOrUrl: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        useChatTarget: Boolean = false,
    ): ApiResult<T> = executeJson(
        method = "GET",
        pathOrUrl = pathOrUrl,
        requestBody = null,
        headers = headers,
        timeoutMs = timeoutMs,
        useChatTarget = useChatTarget,
    )

    suspend inline fun <reified Req, reified Res> postJson(
        pathOrUrl: String,
        body: Req,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_POST_TIMEOUT_MS,
        useChatTarget: Boolean = false,
    ): ApiResult<Res> {
        val bodyStr = json.encodeToString(body)
        return executeJson(
            method = "POST",
            pathOrUrl = pathOrUrl,
            requestBody = bodyStr.toRequestBody(JSON_MEDIA_TYPE),
            headers = headers,
            timeoutMs = timeoutMs,
            useChatTarget = useChatTarget,
        )
    }

    suspend inline fun <reified Res> postJson(
        pathOrUrl: String,
        bodyJson: String = "{}",
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_POST_TIMEOUT_MS,
        useChatTarget: Boolean = false,
    ): ApiResult<Res> = executeJson(
        method = "POST",
        pathOrUrl = pathOrUrl,
        requestBody = bodyJson.toRequestBody(JSON_MEDIA_TYPE),
        headers = headers,
        timeoutMs = timeoutMs,
        useChatTarget = useChatTarget,
    )

    suspend inline fun <reified Req, reified Res> putJson(
        pathOrUrl: String,
        body: Req,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_POST_TIMEOUT_MS,
        useChatTarget: Boolean = false,
    ): ApiResult<Res> {
        val bodyStr = json.encodeToString(body)
        return executeJson(
            method = "PUT",
            pathOrUrl = pathOrUrl,
            requestBody = bodyStr.toRequestBody(JSON_MEDIA_TYPE),
            headers = headers,
            timeoutMs = timeoutMs,
            useChatTarget = useChatTarget,
        )
    }

    suspend inline fun <reified Res> deleteJson(
        pathOrUrl: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_POST_TIMEOUT_MS,
        useChatTarget: Boolean = false,
    ): ApiResult<Res> = executeJson(
        method = "DELETE",
        pathOrUrl = pathOrUrl,
        requestBody = null,
        headers = headers,
        timeoutMs = timeoutMs,
        useChatTarget = useChatTarget,
    )

    suspend inline fun <reified Res> postMultipart(
        pathOrUrl: String,
        body: MultipartBody,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_UPLOAD_TIMEOUT_MS,
        useChatTarget: Boolean = false,
    ): ApiResult<Res> = executeJson(
        method = "POST",
        pathOrUrl = pathOrUrl,
        requestBody = body,
        headers = headers,
        timeoutMs = timeoutMs,
        useChatTarget = useChatTarget,
    )

    suspend fun getBytes(
        pathOrUrl: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_BYTES_TIMEOUT_MS,
        useChatTarget: Boolean = false,
    ): ApiResult<ByteArray> {
        val snap = prefs.awaitLoaded()
        val target = if (useChatTarget) resolveChatTarget(snap) else null
        val url = buildTargetUrl(pathOrUrl, target?.baseUrl ?: snap.serverUrl)
        val mergedHeaders = buildMap {
            put("Accept", "*/*")
            if (target != null) putAll(target.headers)
            putAll(headers)
        }
        val shouldFallbackToCloudflare = useChatTarget &&
            target?.isCustom == false &&
            snap.chatToken.isNotBlank() &&
            !HTTP_PREFIX.containsMatchIn(pathOrUrl.trim())

        val first = executeBytesOnce(url, mergedHeaders, timeoutMs, isCustomChat = target?.isCustom == true)
        if (first is ApiResult.Error && first.statusCode == 0 && shouldFallbackToCloudflare) {
            val fallbackUrl = buildTargetUrl(pathOrUrl, AppPrefs.CLOUDFLARE_CHAT_ORIGIN)
            val fallbackHeaders = mergedHeaders + ("Authorization" to "Bearer ${snap.chatToken}")
            val fallbackFirst = executeBytesOnce(fallbackUrl, fallbackHeaders, timeoutMs, isCustomChat = true)
            if (isRetryableGetError("GET", fallbackFirst)) {
                delay(getRetryBackoffMs)
                return executeBytesOnce(fallbackUrl, fallbackHeaders, timeoutMs, isCustomChat = true)
            }
            return fallbackFirst
        }
        if (isRetryableGetError("GET", first)) {
            delay(getRetryBackoffMs)
            return executeBytesOnce(url, mergedHeaders, timeoutMs, isCustomChat = target?.isCustom == true)
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
    ): ApiResult<T> {
        return when (val raw = executeRaw(method, pathOrUrl, requestBody, headers, timeoutMs, useChatTarget)) {
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
    ): ApiResult<String> {
        val snap = prefs.awaitLoaded()
        val target = if (useChatTarget) resolveChatTarget(snap) else null
        val url = buildTargetUrl(pathOrUrl, target?.baseUrl ?: snap.serverUrl)
        val mergedHeaders = buildMap {
            put("Accept", "application/json")
            if (target != null) putAll(target.headers)
            putAll(headers)
        }
        val shouldFallbackToCloudflare = useChatTarget &&
            target?.isCustom == false &&
            snap.chatToken.isNotBlank() &&
            !HTTP_PREFIX.containsMatchIn(pathOrUrl.trim())

        val first = executeStringOnce(method, url, requestBody, mergedHeaders, timeoutMs, isCustomChat = target?.isCustom == true)
        if (first is ApiResult.Error && first.statusCode == 0 && shouldFallbackToCloudflare) {
            val fallbackUrl = buildTargetUrl(pathOrUrl, AppPrefs.CLOUDFLARE_CHAT_ORIGIN)
            val fallbackHeaders = mergedHeaders + ("Authorization" to "Bearer ${snap.chatToken}")
            val fallbackFirst = executeStringOnce(method, fallbackUrl, requestBody, fallbackHeaders, timeoutMs, isCustomChat = true)
            if (isRetryableGetError(method, fallbackFirst)) {
                delay(getRetryBackoffMs)
                return executeStringOnce(method, fallbackUrl, requestBody, fallbackHeaders, timeoutMs, isCustomChat = true)
            }
            return fallbackFirst
        }
        if (isRetryableGetError(method, first)) {
            delay(getRetryBackoffMs)
            return executeStringOnce(method, url, requestBody, mergedHeaders, timeoutMs, isCustomChat = target?.isCustom == true)
        }
        return first
    }

    /**
     * Cold Flow of Server-Sent Events from [url] (absolute or relative path).
     * Automatically reconnects with exponential backoff until the collector cancels.
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
     * alongside parsed [SseMessage.Event] items.
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

        val runner = launch(Dispatchers.IO) {
            var attempt = 0
            var retryDelay = initialRetryMs.coerceAtLeast(250L)

            while (currentCoroutineContext().isActive) {
                val snap = prefs.awaitLoaded()
                val target = if (useChatTarget) resolveChatTarget(snap) else null
                val resolvedUrl = buildTargetUrl(url, target?.baseUrl ?: snap.serverUrl)
                val reqBuilder = Request.Builder()
                    .url(resolvedUrl)
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                target?.headers?.forEach { (k, v) -> reqBuilder.header(k, v) }
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }

                val streamEnded = CompletableDeferred<String?>()
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
                        streamEnded.complete(null)
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?,
                    ) {
                        val reason = t?.message ?: response?.let { "HTTP ${it.code}" }
                        streamEnded.complete(reason)
                    }
                }

                val source = factory.newEventSource(reqBuilder.build(), listener)
                currentSource = source

                val failureReason = try {
                    streamEnded.await()
                } finally {
                    source.cancel()
                    if (currentSource === source) currentSource = null
                }

                if (!autoReconnect || !currentCoroutineContext().isActive) {
                    break
                }

                attempt += 1
                trySend(SseMessage.Reconnecting(attempt = attempt, delayMs = retryDelay, error = failureReason))
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(maxRetryMs)
            }
            close()
        }

        awaitClose {
            currentSource?.cancel()
            runner.cancel()
        }
    }

    private suspend fun executeStringOnce(
        method: String,
        url: String,
        requestBody: RequestBody?,
        headers: Map<String, String>,
        timeoutMs: Long,
        isCustomChat: Boolean,
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
                message = if (isCustomChat) "Chat endpoint timed out." else "Timed out. Is Tailscale connected?",
                cause = e,
            )
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            ApiResult.Error(
                statusCode = 0,
                message = if (isCustomChat) {
                    "Can't reach chat endpoint (${e.message ?: e::class.simpleName})"
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
        isCustomChat: Boolean,
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
                message = if (isCustomChat) "Chat endpoint timed out." else "Timed out. Is Tailscale connected?",
                cause = e,
            )
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            ApiResult.Error(
                statusCode = 0,
                message = if (isCustomChat) {
                    "Can't reach chat endpoint (${e.message ?: e::class.simpleName})"
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
        val builder = Request.Builder().url(url)
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

        internal fun buildTargetUrl(pathOrUrl: String, origin: String): String {
            val trimmed = pathOrUrl.trim()
            if (HTTP_PREFIX.containsMatchIn(trimmed)) return trimmed
            val cleanOrigin = origin.trim().trimEnd('/')
            val normalizedPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return "$cleanOrigin$normalizedPath"
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

            val baseLower = target.baseUrl.lowercase()
            val uriLower = resolved.lowercase()
            val matchesOrigin = uriLower == baseLower || uriLower.startsWith("$baseLower/")
            return if (matchesOrigin) resolved to target.headers else resolved to emptyMap()
        }
    }
}
