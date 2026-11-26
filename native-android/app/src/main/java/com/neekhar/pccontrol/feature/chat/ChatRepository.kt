package com.neekhar.pccontrol.feature.chat

import android.util.Base64
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.LocalCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

class ChatApiException(
    val statusCode: Int,
    override val message: String,
    val rawBody: String? = null,
) : IOException(message)

sealed interface ChatEventStreamItem {
    data class ConnectionChanged(val connected: Boolean) : ChatEventStreamItem
    data class Event(val event: ChatSseEvent) : ChatEventStreamItem
}

/**
 * Data layer for PC Control Agent Chat (`/pc/api/chat/*`).
 * Handles HTTP requests, SSE live streaming with exponential backoff, attachment uploads,
 * SSRF-safe OG link previews (with bounded TTL LRU cache), model/effort sync, and [LocalCache].
 */
class ChatRepository(
    private val apiClient: PcApiClient,
    private val localCache: LocalCache? = null,
) {
    private val json: Json get() = apiClient.json
    private val baseHttpClient: OkHttpClient get() = apiClient.okHttpClient

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private data class CachedPreview(
        val preview: LinkPreview?,
        val expiresAtMs: Long,
    )

    private val previewLock = Any()
    private val previewCache = object : LinkedHashMap<String, CachedPreview>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPreview>?): Boolean =
            size > LINK_PREVIEW_MAX_ENTRIES
    }
    private val previewInFlight = ConcurrentHashMap<String, Boolean>()

    // ---------------------------------------------------------------- Target resolution

    private fun resolveTarget(): ChatTarget {
        val resolved = apiClient.resolveChatTarget()
        return ChatTarget(
            baseUrl = resolved.baseUrl,
            headers = resolved.headers,
            custom = resolved.isCustom,
        )
    }

    /**
     * Resolves an attachment or inline image URL against the active chat target, attaching the
     * Bearer token ONLY when the resolved URL belongs to the chat origin.
     */
    fun resolveAttachmentSource(url: String): ChatAttachmentSource {
        val (uri, headers) = apiClient.resolveChatAttachment(url)
        return ChatAttachmentSource(uri = uri, headers = headers)
    }

    // ---------------------------------------------------------------- LocalCache persistence

    fun loadCachedChatState(): ChatCacheState? =
        localCache?.get<ChatCacheState>(CHAT_STATE_CACHE_KEY)

    fun saveCachedChatState(messages: List<ChatMessage>, engine: String, hasMore: Boolean) {
        val cache = localCache ?: return
        val clean = messages.filterNot { it.optimistic }.takeLast(MAX_CACHED_MESSAGES)
        cache.put(
            CHAT_STATE_CACHE_KEY,
            ChatCacheState(messages = clean, engine = engine, hasMore = hasMore),
        )
    }

    fun loadCachedAgentSettings(): ChatAgentSettings? =
        localCache?.get<ChatAgentSettings>(AGENT_SETTINGS_CACHE_KEY)

    fun saveCachedAgentSettings(settings: ChatAgentSettings) {
        localCache?.put(AGENT_SETTINGS_CACHE_KEY, settings)
    }

    // ---------------------------------------------------------------- HTTP helpers

    private suspend fun executeRequest(
        method: String,
        path: String,
        jsonBody: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): String = withContext(Dispatchers.IO) {
        val target = resolveTarget()
        val url = "${target.baseUrl}$path"
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        for ((k, v) in target.headers) {
            reqBuilder.header(k, v)
        }
        when (method) {
            "GET" -> reqBuilder.get()
            "POST" -> {
                val body = (jsonBody ?: "{}").toRequestBody(jsonMediaType)
                reqBuilder.post(body)
            }

            "DELETE" -> reqBuilder.delete()
            else -> error("Unsupported HTTP method: $method")
        }

        val client = baseHttpClient.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val serverMsg = runCatching {
                        if (text.isNotBlank()) json.decodeFromString<ChatErrorPayload>(text).error else null
                    }.getOrNull()
                    throw ChatApiException(
                        statusCode = resp.code,
                        message = serverMsg?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}",
                        rawBody = text.takeIf { it.isNotBlank() },
                    )
                }
                text
            }
        } catch (e: ChatApiException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val friendly = if (target.custom) {
                "Can't reach chat endpoint (${e.message ?: e.javaClass.simpleName})"
            } else {
                "Can't reach the laptop. Is Tailscale connected? (${e.message ?: e.javaClass.simpleName})"
            }
            throw ChatApiException(statusCode = 0, message = friendly)
        }
    }

    // ---------------------------------------------------------------- Chat REST API

    suspend fun fetchMessages(before: String? = null, limit: Int = 50): ChatMessagesResponse {
        val params = buildString {
            append("?limit=").append(limit)
            if (!before.isNullOrBlank()) {
                append("&before=").append(URLEncoder.encode(before, StandardCharsets.UTF_8.name()))
            }
        }
        val text = executeRequest("GET", "/pc/api/chat/messages$params")
        return json.decodeFromString(text)
    }

    suspend fun sendMessage(
        text: String,
        clientId: String,
        attachments: List<ChatAttachment> = emptyList(),
        replyToId: String? = null,
    ): ChatSendResponse {
        val req = ChatSendRequest(
            text = text,
            clientId = clientId,
            attachments = attachments.takeIf { it.isNotEmpty() },
            replyToId = replyToId?.takeIf { it.isNotBlank() },
        )
        val raw = executeRequest("POST", "/pc/api/chat/send", json.encodeToString(req))
        return json.decodeFromString(raw)
    }

    suspend fun steerMessage(messageId: String): ChatSteerResponse {
        val req = ChatSteerRequest(messageId = messageId)
        val raw = executeRequest("POST", "/pc/api/chat/steer", json.encodeToString(req))
        return json.decodeFromString(raw)
    }

    fun parseSteerErrorBody(rawBody: String?): ChatSteerErrorBody? {
        if (rawBody.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<ChatSteerErrorBody>(rawBody) }.getOrNull()
    }

    suspend fun startNewChat(): ChatNewResponse {
        val raw = executeRequest("POST", "/pc/api/chat/new", "{}")
        return json.decodeFromString(raw)
    }

    /**
     * Attempts to interrupt an active generation if the server exposes `/pc/api/chat/stop`.
     * Returns true if interrupted, false if the server does not support stop (HTTP 404).
     */
    suspend fun interruptActiveTurn(): Boolean {
        return try {
            executeRequest("POST", "/pc/api/chat/stop", "{}", timeoutMs = 8_000L)
            true
        } catch (e: ChatApiException) {
            if (e.statusCode == 404) false else throw e
        }
    }

    suspend fun uploadAttachment(file: PickedLocalFile): ChatAttachment {
        if (file.bytes.size > MAX_ATTACHMENT_BYTES) {
            throw ChatApiException(413, "Attachment exceeds 15 MB limit")
        }
        val base64 = withContext(Dispatchers.Default) {
            Base64.encodeToString(file.bytes, Base64.NO_WRAP)
        }
        val req = ChatUploadRequest(
            name = file.name,
            mimeType = file.mimeType,
            dataBase64 = base64,
        )
        val raw = executeRequest(
            method = "POST",
            path = "/pc/api/chat/upload",
            jsonBody = json.encodeToString(req),
            timeoutMs = UPLOAD_TIMEOUT_MS,
        )
        return json.decodeFromString(raw)
    }

    // ---------------------------------------------------------------- Agent settings & switching

    suspend fun getAgentSettings(): ChatAgentSettings {
        val raw = executeRequest("GET", "/pc/api/chat/agent")
        val parsed = json.decodeFromString<ChatAgentSettings>(raw)
        saveCachedAgentSettings(parsed)
        return parsed
    }

    suspend fun updateAgentSettings(patch: ChatAgentSettingsPatch): ChatAgentSettings {
        val raw = executeRequest("POST", "/pc/api/chat/agent", json.encodeToString(patch))
        val parsed = json.decodeFromString<ChatAgentSettings>(raw)
        saveCachedAgentSettings(parsed)
        return parsed
    }

    suspend fun switchAgent(engine: String): ChatAgentState {
        val raw = executeRequest(
            method = "POST",
            path = "/pc/api/chat/agent/switch",
            jsonBody = json.encodeToString(ChatAgentSwitchRequest(engine = engine)),
            timeoutMs = 20_000L,
        )
        return json.decodeFromString(raw)
    }

    // ---------------------------------------------------------------- OG Link Previews

    fun getCachedLinkPreview(url: String): Pair<Boolean, LinkPreview?> {
        val key = url.trim()
        if (key.isEmpty()) return true to null
        val now = System.currentTimeMillis()
        synchronized(previewLock) {
            val entry = previewCache[key] ?: return false to null
            if (now >= entry.expiresAtMs) {
                previewCache.remove(key)
                return false to null
            }
            return true to entry.preview
        }
    }

    suspend fun fetchLinkPreview(url: String): LinkPreview? {
        val key = url.trim()
        if (key.isEmpty()) return null
        val (hit, cached) = getCachedLinkPreview(key)
        if (hit) return cached

        if (previewInFlight.putIfAbsent(key, true) != null) {
            // Another request for this exact URL is already in flight.
            return null
        }
        return try {
            val encoded = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
            val raw = executeRequest(
                method = "GET",
                path = "/pc/api/chat/link-preview?url=$encoded",
                timeoutMs = LINK_PREVIEW_TIMEOUT_MS,
            )
            val parsed = json.decodeFromString<LinkPreviewResponse>(raw).preview
            val ttl = if (parsed != null) LINK_PREVIEW_HIT_TTL_MS else LINK_PREVIEW_MISS_TTL_MS
            synchronized(previewLock) {
                previewCache[key] = CachedPreview(parsed, System.currentTimeMillis() + ttl)
            }
            parsed
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            previewInFlight.remove(key)
        }
    }

    // ---------------------------------------------------------------- Live SSE Stream

    /**
     * Opens a resilient Server-Sent Events stream to `/pc/api/chat/events` with automatic
     * reconnect backoff (1.5s -> 15s) and watchdog read timeout (65s).
     */
    fun observeChatEvents(): Flow<ChatEventStreamItem> = flow {
        var retryDelayMs = SSE_INITIAL_RETRY_MS
        while (currentCoroutineContext().isActive) {
            val target = resolveTarget()
            val reqBuilder = Request.Builder()
                .url("${target.baseUrl}/pc/api/chat/events")
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
            for ((k, v) in target.headers) {
                reqBuilder.header(k, v)
            }

            val sseClient = baseHttpClient.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(SSE_READ_WATCHDOG_SEC, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val call = sseClient.newCall(reqBuilder.build())
            var connectedEmitted = false
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("SSE HTTP ${resp.code}")
                    }
                    val source = resp.body?.source() ?: throw IOException("Empty SSE body")
                    val buf = StringBuilder()

                    while (currentCoroutineContext().isActive && !source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        buf.append(line).append('\n')
                        if (line.isEmpty()) {
                            val parsed = ChatLogic.parseChatSseBlocks(buf.toString(), json)
                            buf.clear()
                            buf.append(parsed.rest)
                            for (ev in parsed.events) {
                                retryDelayMs = SSE_INITIAL_RETRY_MS
                                if (!connectedEmitted) {
                                    connectedEmitted = true
                                    emit(ChatEventStreamItem.ConnectionChanged(true))
                                }
                                emit(ChatEventStreamItem.Event(ev))
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                call.cancel()
                throw e
            } catch (_: Exception) {
                // Reconnect with backoff below.
            } finally {
                if (connectedEmitted) {
                    emit(ChatEventStreamItem.ConnectionChanged(false))
                }
            }

            if (!currentCoroutineContext().isActive) break
            delay(retryDelayMs)
            retryDelayMs = min(retryDelayMs * 2L, SSE_MAX_RETRY_MS)
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val CHAT_STATE_CACHE_KEY = "chat:state"
        private const val AGENT_SETTINGS_CACHE_KEY = "agent:settings"
        private const val MAX_CACHED_MESSAGES = 80
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val UPLOAD_TIMEOUT_MS = 60_000L
        private const val LINK_PREVIEW_TIMEOUT_MS = 8_000L
        private const val LINK_PREVIEW_MAX_ENTRIES = 128
        private const val LINK_PREVIEW_HIT_TTL_MS = 6L * 60L * 60L * 1000L
        private const val LINK_PREVIEW_MISS_TTL_MS = 10L * 60L * 1000L
        private const val SSE_INITIAL_RETRY_MS = 1_500L
        private const val SSE_MAX_RETRY_MS = 15_000L
        private const val SSE_READ_WATCHDOG_SEC = 65L
    }
}
