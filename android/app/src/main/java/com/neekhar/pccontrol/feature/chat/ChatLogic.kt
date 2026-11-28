package com.neekhar.pccontrol.feature.chat

import com.neekhar.pccontrol.core.platform.MarkdownStripper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val THINKING_INDICATOR_ID = "__chat_thinking__"
const val MAX_ATTACHMENTS = 4
const val MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024
const val INLINE_IMAGE_MAX_HEIGHT_DP = 440
const val INLINE_IMAGE_MIN_WIDTH_DP = 64
const val INLINE_IMAGE_MIN_HEIGHT_DP = 48
const val INLINE_IMAGE_DEFAULT_ASPECT = 4.0 / 3.0

const val SWIPE_REPLY_ACTIVATION_DX = 10f
const val SWIPE_REPLY_DIRECTION_RATIO = 1.5f
const val SWIPE_REPLY_THRESHOLD = 60f
const val SWIPE_REPLY_RESET_THRESHOLD = 48f
const val SWIPE_REPLY_MAX_DRAG = 84f

data class ChatTarget(
    val baseUrl: String,
    val headers: Map<String, String>,
    val custom: Boolean,
)

data class ChatAttachmentSource(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
)

data class ChatSseParseResult(
    val events: List<ChatSseEvent>,
    val rest: String,
)

enum class TableAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

sealed interface MessageSegment {
    data class Text(val text: String) : MessageSegment
    data class CodeBlock(val lang: String, val code: String) : MessageSegment
    data class Table(
        val headers: List<String>,
        val alignments: List<TableAlignment>,
        val rows: List<List<String>>,
    ) : MessageSegment

    data class Image(
        val alt: String,
        val url: String,
        val linkUrl: String? = null,
    ) : MessageSegment
}

sealed interface InlineToken {
    data class Text(val text: String) : InlineToken
    data class Code(val code: String) : InlineToken
    data class Bold(val children: List<InlineToken>) : InlineToken
    data class Italic(val children: List<InlineToken>) : InlineToken
    data class Strike(val children: List<InlineToken>) : InlineToken
    data class Link(val children: List<InlineToken>, val url: String, val external: Boolean) : InlineToken
}

fun InlineToken.plainText(): String = when (this) {
    is InlineToken.Text -> text
    is InlineToken.Code -> code
    is InlineToken.Bold -> children.joinToString("") { it.plainText() }
    is InlineToken.Italic -> children.joinToString("") { it.plainText() }
    is InlineToken.Strike -> children.joinToString("") { it.plainText() }
    is InlineToken.Link -> children.joinToString("") { it.plainText() }
}

data class InlineImageBoxSize(
    val width: Int,
    val height: Int,
)

data class ChatListAutoFormatResult(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val changed: Boolean,
)

object ChatLogic {

    private val chatJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val chatApiPathSuffixRe = Regex("""/pc/api/chat.*$""", RegexOption.IGNORE_CASE)
    private val trailingSlashRe = Regex("""/+$""")
    private val httpSchemeRe = Regex("""^https?://""", RegexOption.IGNORE_CASE)
    private val httpHostRe = Regex("""^https?://[^/\s]+""", RegexOption.IGNORE_CASE)
    private val t3ThreadSchemeRe = Regex("""^t3-thread://v2/([^/]+)/(.+)$""", RegexOption.IGNORE_CASE)
    private val t3ThreadWebRe = Regex("""^https?://app\.t3\.codes/threads/([^/\s]+)/([^/\s?#]+)""", RegexOption.IGNORE_CASE)
    private val unicodeBulletLineRe = Regex("""^([ \t]*)•[ \t]+""", RegexOption.MULTILINE)
    private val tableAlignCellRe = Regex("""^:?-{1,}:?$""")
    private val dataImageBase64Re = Regex("""^data:image/[a-z0-9.+-]+;base64,""", RegexOption.IGNORE_CASE)
    private val relativePathRe = Regex("""^/[^\s]*$""")
    private val codeFenceRe = Regex("""```(?:([a-zA-Z0-9_+#.-]+)[ \t]*\r?\n|[ \t]*\r?\n)?([\s\S]*?)```""")
    private val linkUrlSchemeRe = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://""")
    private val trailingPunctRe = Regex("""[.,;:!?'"\]}]""")
    private const val MAX_INLINE_DEPTH = 8
    private val geminiLabelRe = Regex("""^Gemini\s+[\d.]+\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val orchestratorLabelRe = Regex("""^Orchestrator\b""", RegexOption.IGNORE_CASE)
    private val parenCaptureRe = Regex("""\(([^)]+)\)""")
    private val claudeLabelRe = Regex("""^([A-Za-z]+)\s+[\d.]+$""")
    private val orderedListRe = Regex("""^([ \t]*)(\d+)([.)]) (.*)$""")
    private val bulletListRe = Regex("""^([ \t]*)([-*•]) (.*)$""")
    private val whitespaceRe = Regex("""\s+""")

    /**
     * Stable list key for a [ChatMessage] so an optimistic user bubble keeps the exact same key
     * once confirmed by the server (preventing item remount and scroll jumps mid-send).
     */
    fun getChatMessageKey(msg: ChatMessage): String {
        if (msg.role == ChatRoles.USER && !msg.clientId.isNullOrBlank()) {
            return "u:${msg.clientId}"
        }
        return msg.id
    }

    /**
     * Builds the rendered list, inserting a synthetic thinking/activity item whenever a reply is
     * in flight and no pending assistant message already exists.
     *
     * Any trailing run of queued user messages (`msg.isUser && (msg.queued || msg.id in queuedIds)`)
     * renders BELOW the active turn's live thinking trace so visual order matches execution order.
     */
    fun buildChatListItems(
        messages: List<ChatMessage>,
        running: Boolean,
        sending: Boolean,
        engine: String,
        activeProgress: ChatProgress? = null,
        queuedIds: Set<String> = emptySet(),
    ): List<ChatMessage> {
        val hasPendingMsg = messages.any { it.status == ChatStatuses.PENDING }
        if ((!running && !sending) || hasPendingMsg) {
            return messages
        }
        val thinkingItem = ChatMessage(
            id = THINKING_INDICATOR_ID,
            role = ChatRoles.ASSISTANT,
            engine = activeProgress?.engine ?: engine,
            text = "",
            createdAt = activeProgress?.started ?: 0.0,
            status = ChatStatuses.PENDING,
            progress = activeProgress,
            optimistic = true,
        )
        var insertIdx = messages.size
        while (insertIdx > 0) {
            val candidate = messages[insertIdx - 1]
            if (candidate.isUser && (candidate.queued || candidate.id in queuedIds)) {
                insertIdx -= 1
            } else {
                break
            }
        }
        if (insertIdx == messages.size) {
            return messages + thinkingItem
        }
        val out = ArrayList<ChatMessage>(messages.size + 1)
        out.addAll(messages.subList(0, insertIdx))
        out.add(thinkingItem)
        out.addAll(messages.subList(insertIdx, messages.size))
        return out
    }

    fun normalizeChatUrl(raw: String): String {
        val trimmed = raw.trim().replace(trailingSlashRe, "")
        if (trimmed.isEmpty()) return ""
        return trimmed.replace(chatApiPathSuffixRe, "").replace(trailingSlashRe, "")
    }

    fun resolveChatTarget(
        defaultBaseUrl: String,
        customUrl: String,
        customToken: String,
    ): ChatTarget {
        val cleanCustom = normalizeChatUrl(customUrl)
        val cleanToken = customToken.trim()
        if (cleanCustom.isNotEmpty()) {
            val headers = if (cleanToken.isNotEmpty()) {
                mapOf("Authorization" to "Bearer $cleanToken")
            } else {
                emptyMap()
            }
            return ChatTarget(baseUrl = cleanCustom, headers = headers, custom = true)
        }
        return ChatTarget(
            baseUrl = defaultBaseUrl.trim().replace(trailingSlashRe, ""),
            headers = emptyMap(),
            custom = false,
        )
    }

    /**
     * Resolves relative and absolute attachment/image URLs, attaching the chat Bearer token ONLY
     * when the target URL belongs to the configured custom chat origin.
     */
    fun buildChatAttachmentSource(
        url: String,
        defaultBaseUrl: String,
        customUrl: String,
        customToken: String,
    ): ChatAttachmentSource {
        val target = resolveChatTarget(defaultBaseUrl, customUrl, customToken)
        val trimmed = url.trim()
        val isAbsolute = httpSchemeRe.containsMatchIn(trimmed)
        val uri = if (isAbsolute) {
            trimmed
        } else {
            val path = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            "${target.baseUrl}$path"
        }
        if (target.headers.isEmpty()) return ChatAttachmentSource(uri = uri)
        if (!isAbsolute) return ChatAttachmentSource(uri = uri, headers = target.headers)
        val baseLower = target.baseUrl.lowercase(Locale.US)
        val uriLower = uri.lowercase(Locale.US)
        return if (uriLower == baseLower || uriLower.startsWith("$baseLower/")) {
            ChatAttachmentSource(uri = uri, headers = target.headers)
        } else {
            ChatAttachmentSource(uri = uri)
        }
    }

    fun makeClientId(nowMs: Long = System.currentTimeMillis()): String {
        val rand = UUID.randomUUID().toString().replace("-", "").take(8)
        return "c-$nowMs-$rand"
    }

    /**
     * Merge server messages with any still-in-flight optimistic messages, deduplicating by id and
     * client_id and keeping chronological order.
     */
    fun mergeChatMessages(
        current: List<ChatMessage>,
        incoming: List<ChatMessage>,
    ): List<ChatMessage> {
        val seenIds = HashSet<String>(incoming.size + current.size)
        val seenClientIds = HashSet<String>()
        val merged = ArrayList<ChatMessage>(incoming.size + current.size)

        for (m in incoming) {
            if (!seenIds.add(m.id)) continue
            m.clientId?.takeIf { it.isNotEmpty() }?.let { seenClientIds.add(it) }
            merged.add(m.copy(optimistic = false))
        }

        for (m in current) {
            if (!m.optimistic) continue
            if (seenIds.contains(m.id)) continue
            val cid = m.clientId
            if (!cid.isNullOrEmpty() && seenClientIds.contains(cid)) continue
            val legacyReply = m.replyToLegacyId()
            if (legacyReply != null && seenClientIds.contains(legacyReply)) continue
            merged.add(m)
        }

        merged.sortBy { it.createdAt }
        return merged
    }

    /** Prepend older paginated messages without duplicating existing IDs. */
    fun prependOlderMessages(
        older: List<ChatMessage>,
        current: List<ChatMessage>,
    ): List<ChatMessage> {
        val existingIds = current.mapTo(HashSet(current.size)) { it.id }
        val prefix = older.filter { it.id !in existingIds }
        return prefix + current
    }

    fun shouldPollChat(
        messages: List<ChatMessage>,
        running: Boolean,
        isForeground: Boolean,
    ): Boolean {
        if (!isForeground) return false
        if (running) return true
        return messages.any { it.status == ChatStatuses.PENDING || it.queued }
    }

    /** Returns true when a user message should show the clickable "Send now" steering action. */
    fun canSteerQueuedMessage(msg: ChatMessage, running: Boolean): Boolean =
        running && msg.role == ChatRoles.USER && msg.queued && !msg.optimistic

    /**
     * Apply a single incoming server message (new or updated) to the local chat list,
     * deduplicating by ID or user `client_id` and removing any superseded placeholder.
     */
    fun upsertChatMessage(
        current: List<ChatMessage>,
        incoming: ChatMessage,
        removedId: String? = null,
    ): List<ChatMessage> {
        val normalized = incoming.copy(optimistic = false)
        var replaced = false
        val next = ArrayList<ChatMessage>(current.size + 1)

        for (m in current) {
            if (removedId != null && m.id == removedId) continue
            val sameId = m.id == normalized.id
            val sameClientId =
                !normalized.clientId.isNullOrEmpty() &&
                    m.role == ChatRoles.USER &&
                    normalized.role == ChatRoles.USER &&
                    m.clientId == normalized.clientId
            if (sameId || sameClientId) {
                if (!replaced) {
                    next.add(normalized)
                    replaced = true
                }
                continue
            }
            next.add(m)
        }
        if (!replaced) {
            next.add(normalized)
        }
        next.sortBy { it.createdAt }
        return next
    }

    /**
     * Reconcile local `queued` flags with the authoritative server `queued_ids` list from an SSE
     * snapshot, without reverting messages that are currently being optimistically steered.
     */
    fun reconcileQueuedMessages(
        current: List<ChatMessage>,
        queuedIds: Collection<String>,
        running: Boolean,
        optimisticSteeredIds: Set<String> = emptySet(),
    ): List<ChatMessage> {
        val queuedSet = if (running) queuedIds.toHashSet() else emptySet()
        var changed = false
        val next = current.map { m ->
            if (m.role != ChatRoles.USER || m.optimistic) return@map m
            if (m.id in optimisticSteeredIds) {
                if (!m.queued) return@map m
                changed = true
                return@map m.copy(queued = false, steered = true)
            }
            val shouldBeQueued = m.id in queuedSet
            if (m.queued == shouldBeQueued) return@map m
            changed = true
            m.copy(queued = shouldBeQueued)
        }
        return if (changed) next else current
    }

    /** Optimistically mark a queued user message as steered immediately upon tapping "Send now". */
    fun applyOptimisticSteer(
        current: List<ChatMessage>,
        messageId: String,
    ): List<ChatMessage> {
        var changed = false
        val next = current.map { m ->
            if (m.role != ChatRoles.USER || m.id != messageId) return@map m
            if (!m.queued && m.steered) return@map m
            changed = true
            m.copy(queued = false, steered = true)
        }
        return if (changed) next else current
    }

    /** Roll back an optimistic steer when the steer request fails. */
    fun rollbackOptimisticSteer(
        current: List<ChatMessage>,
        messageId: String,
        stillRunning: Boolean,
    ): List<ChatMessage> {
        var changed = false
        val next = current.map { m ->
            if (m.role != ChatRoles.USER || m.id != messageId) return@map m
            if (m.queued == stillRunning && !m.steered) return@map m
            changed = true
            m.copy(queued = stillRunning, steered = false)
        }
        return if (changed) next else current
    }

    /**
     * Prevent incoming polling/SSE updates from flickering an in-flight optimistically steered
     * message back to `queued = true` before the steer HTTP request completes.
     */
    fun preserveOptimisticSteers(
        current: List<ChatMessage>,
        optimisticSteeredIds: Set<String>,
    ): List<ChatMessage> {
        if (optimisticSteeredIds.isEmpty()) return current
        var changed = false
        val next = current.map { m ->
            if (m.role != ChatRoles.USER || m.id !in optimisticSteeredIds) return@map m
            if (!m.queued) return@map m
            changed = true
            m.copy(queued = false, steered = true)
        }
        return if (changed) next else current
    }

    /**
     * Merge incoming live activity/progress updates while preserving step history if an
     * incremental update arrives for the same run.
     */
    fun mergeProgress(existing: ChatProgress?, incoming: ChatProgress?): ChatProgress? {
        if (incoming == null) return null
        if (existing == null || existing.started != incoming.started) return incoming
        if (incoming.steps.size >= existing.steps.size) return incoming
        val existingTexts = existing.steps.mapTo(HashSet()) { "${it.t}:${it.text}" }
        val mergedSteps = existing.steps + incoming.steps.filter { "${it.t}:${it.text}" !in existingTexts }
        return incoming.copy(steps = mergedSteps)
    }

    /** Parse complete SSE event blocks from a raw text buffer for `/pc/api/chat/events`. */
    fun parseChatSseBlocks(buf: String, json: Json = chatJson): ChatSseParseResult {
        val events = mutableListOf<ChatSseEvent>()
        var rem = buf.replace("\r\n", "\n")

        while (true) {
            val idx = rem.indexOf("\n\n")
            if (idx < 0) break
            val block = rem.substring(0, idx)
            rem = rem.substring(idx + 2)

            var eventName = "message"
            val dataLines = mutableListOf<String>()
            for (line in block.split('\n')) {
                if (line.startsWith(":")) continue
                if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim()
                } else if (line.startsWith("data:")) {
                    val raw = line.substring(5)
                    dataLines.add(if (raw.startsWith(" ")) raw.substring(1) else raw)
                }
            }
            if (dataLines.isEmpty()) continue
            val payloadStr = dataLines.joinToString("\n")
            val obj = try {
                json.parseToJsonElement(payloadStr) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: continue

            when (eventName) {
                "snapshot" -> {
                    val engine = ChatEngines.normalize((obj["engine"] as? JsonPrimitive)?.contentOrNull)
                    val running = (obj["running"] as? JsonPrimitive)?.booleanOrNull ?: false
                    val progress = obj["progress"]?.takeIf { it !is JsonNull }?.let {
                        runCatching { json.decodeFromJsonElement<ChatProgress>(it) }.getOrNull()
                    }
                    val queuedIds = (obj["queued_ids"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?: emptyList()
                    val ts = (obj["ts"] as? JsonPrimitive)?.longOrNull ?: 0L
                    events.add(
                        ChatSseEvent.Snapshot(
                            engine = engine,
                            running = running,
                            progress = progress,
                            queuedIds = queuedIds,
                            ts = ts,
                        )
                    )
                }

                "message" -> {
                    val msgEl = obj["message"] as? JsonObject ?: continue
                    val message = runCatching { json.decodeFromJsonElement<ChatMessage>(msgEl) }.getOrNull()
                        ?: continue
                    val running = (obj["running"] as? JsonPrimitive)?.booleanOrNull ?: false
                    val engineRaw = (obj["engine"] as? JsonPrimitive)?.contentOrNull
                    val engine = when (engineRaw?.lowercase(Locale.US)) {
                        ChatEngines.GEMINI -> ChatEngines.GEMINI
                        ChatEngines.CLAUDE -> ChatEngines.CLAUDE
                        else -> null
                    }
                    val hasProgressField = obj.containsKey("progress")
                    val progress = obj["progress"]?.takeIf { it !is JsonNull }?.let {
                        runCatching { json.decodeFromJsonElement<ChatProgress>(it) }.getOrNull()
                    }
                    val steered = (obj["steered"] as? JsonPrimitive)?.booleanOrNull
                    val removedId = (obj["removed_message_id"] as? JsonPrimitive)?.contentOrNull
                    val ts = (obj["ts"] as? JsonPrimitive)?.longOrNull ?: 0L
                    events.add(
                        ChatSseEvent.Message(
                            message = message,
                            running = running,
                            engine = engine,
                            progress = progress,
                            hasProgressField = hasProgressField,
                            steered = steered,
                            removedMessageId = removedId,
                            ts = ts,
                        )
                    )
                }

                "progress" -> {
                    val running = (obj["running"] as? JsonPrimitive)?.booleanOrNull ?: false
                    val progress = obj["progress"]?.takeIf { it !is JsonNull }?.let {
                        runCatching { json.decodeFromJsonElement<ChatProgress>(it) }.getOrNull()
                    }
                    val ts = (obj["ts"] as? JsonPrimitive)?.longOrNull ?: 0L
                    events.add(
                        ChatSseEvent.Progress(
                            running = running,
                            progress = progress,
                            ts = ts,
                        )
                    )
                }

                "message_deleted" -> {
                    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    if (id.isNotEmpty()) {
                        val ts = (obj["ts"] as? JsonPrimitive)?.longOrNull ?: 0L
                        events.add(ChatSseEvent.MessageDeleted(id = id, ts = ts))
                    }
                }

                "state" -> {
                    val running = (obj["running"] as? JsonPrimitive)?.booleanOrNull ?: false
                    val engineRaw = (obj["engine"] as? JsonPrimitive)?.contentOrNull
                    val engine = when (engineRaw?.lowercase(Locale.US)) {
                        ChatEngines.GEMINI -> ChatEngines.GEMINI
                        ChatEngines.CLAUDE -> ChatEngines.CLAUDE
                        else -> null
                    }
                    val queuedIds = (obj["queued_ids"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?: emptyList()
                    val ts = (obj["ts"] as? JsonPrimitive)?.longOrNull ?: 0L
                    events.add(
                        ChatSseEvent.State(
                            running = running,
                            engine = engine,
                            queuedIds = queuedIds,
                            ts = ts,
                        )
                    )
                }
            }
        }
        return ChatSseParseResult(events = events, rest = rem)
    }

    // ---------------------------------------------------------------- Links & Markdown

    fun isHttpUrl(url: String): Boolean = httpSchemeRe.containsMatchIn(url)

    fun isT3ThreadWebUrl(url: String): Boolean = t3ThreadWebRe.containsMatchIn(url.trim())

    /** Convert `t3-thread://v2/<envId>/<threadId>` into the T3 Code Android App Link URL. */
    fun resolveT3ThreadUrl(url: String): String? {
        val m = t3ThreadSchemeRe.matchEntire(url.trim()) ?: return null
        fun decodeOnce(s: String): String = try {
            URLDecoder.decode(s, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            s
        }
        fun encodeSegment(s: String): String =
            URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20")

        val envId = decodeOnce(m.groupValues[1]).trim()
        val threadId = decodeOnce(m.groupValues[2]).trim()
        if (envId.isEmpty() || threadId.isEmpty()) return null
        return "https://app.t3.codes/threads/${encodeSegment(envId)}/${encodeSegment(threadId)}"
    }

    /** Resolve a tapped Markdown/chat link URL (`http(s)` or `t3-thread://v2/...`) into an openable URL. */
    fun resolveChatLinkUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (isHttpUrl(trimmed)) return trimmed
        return resolveT3ThreadUrl(trimmed)
    }

    /**
     * Resolve a local file link target (`/abs/path...` or `file:///abs/path...`) into a clean
     * copyable file path (stripping `file://` if present), or null if not a local file link.
     */
    fun resolveLocalFilePath(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("file://", ignoreCase = true)) {
            return trimmed.substring(7).takeIf { it.isNotEmpty() }
        }
        if (trimmed.startsWith("/")) return trimmed
        return null
    }

    /** Convert leading Unicode bullet markers (`• item`) into CommonMark `- item`. */
    fun normalizeMarkdownForDisplay(text: String): String {
        if (text.isEmpty() || !text.contains('•')) return text
        return unicodeBulletLineRe.replace(text, "$1- ")
    }

    private fun trimBareUrl(raw: String): Pair<String, String> {
        var end = raw.length
        while (end > 0) {
            val ch = raw[end - 1]
            if (ch == ')') {
                val slice = raw.substring(0, end)
                val opens = slice.count { it == '(' }
                val closes = slice.count { it == ')' }
                if (closes > opens) {
                    end -= 1
                    continue
                }
                break
            }
            if (trailingPunctRe.matches(ch.toString())) {
                end -= 1
                continue
            }
            break
        }
        return raw.substring(0, end) to raw.substring(end)
    }

    /**
     * Starting at a backtick at [start], find the matching CommonMark code span closing backtick
     * run of the exact same length. Returns `(contentStart, contentEnd, afterCloseIndex)` or null.
     */
    private fun matchCodeSpan(text: String, start: Int): Triple<Int, Int, Int>? {
        var tickEnd = start
        while (tickEnd < text.length && text[tickEnd] == '`') tickEnd += 1
        val tickLen = tickEnd - start
        if (tickLen == 0) return null

        var k = tickEnd
        while (k < text.length) {
            if (text[k] == '`') {
                var closeEnd = k
                while (closeEnd < text.length && text[closeEnd] == '`') closeEnd += 1
                if (closeEnd - k == tickLen) {
                    return Triple(tickEnd, k, closeEnd)
                }
                k = closeEnd
            } else {
                k += 1
            }
        }
        return null
    }

    private fun normalizeCodeSpanContent(raw: String): String {
        val normalized = raw.replace("\r\n", " ").replace('\n', ' ')
        return if (
            normalized.length >= 2 &&
            normalized.startsWith(' ') &&
            normalized.endsWith(' ') &&
            normalized.any { it != ' ' }
        ) {
            normalized.substring(1, normalized.length - 1)
        } else {
            normalized
        }
    }

    /**
     * Find a matching closing double delimiter (`**` or `~~`) on the same line, skipping over
     * inline code spans. Returns `(contentEnd, afterCloseIndex)` or null.
     */
    private fun findClosingDoubleDelimiter(text: String, contentStart: Int, delimChar: Char): Pair<Int, Int>? {
        var k = contentStart
        while (k < text.length) {
            val ch = text[k]
            if (ch == '\n') return null
            if (ch == '`') {
                val code = matchCodeSpan(text, k)
                if (code != null) {
                    k = code.third
                    continue
                }
                while (k < text.length && text[k] == '`') k += 1
                continue
            }
            if (ch == delimChar) {
                var runEnd = k
                while (runEnd < text.length && text[runEnd] == delimChar) runEnd += 1
                val runLen = runEnd - k
                if (runLen >= 2) {
                    // Use the trailing two delimiter chars so `***bold italic***` keeps `*...*` inside
                    val closeStart = runEnd - 2
                    if (closeStart > contentStart) {
                        return closeStart to runEnd
                    }
                }
                k = runEnd
                continue
            }
            k += 1
        }
        return null
    }

    /**
     * Find a matching closing single `*` on the same line, skipping over inline code spans and
     * requiring non-whitespace flanking characters so math like `2 * 3 * 4` stays plain text.
     */
    private fun findClosingSingleItalic(text: String, contentStart: Int): Int? {
        if (contentStart >= text.length || text[contentStart].isWhitespace() || text[contentStart] == '\n') {
            return null
        }
        var k = contentStart
        while (k < text.length) {
            val ch = text[k]
            if (ch == '\n') return null
            if (ch == '`') {
                val code = matchCodeSpan(text, k)
                if (code != null) {
                    k = code.third
                    continue
                }
                while (k < text.length && text[k] == '`') k += 1
                continue
            }
            if (ch == '*') {
                var runEnd = k
                while (runEnd < text.length && text[runEnd] == '*') runEnd += 1
                val runLen = runEnd - k
                if ((runLen == 1 || runLen == 3) && k > contentStart && !text[k - 1].isWhitespace()) {
                    return runEnd - 1
                }
                k = runEnd
                continue
            }
            k += 1
        }
        return null
    }

    /**
     * Match a Markdown link `[label](scheme://url)` starting at [openBracket], respecting inline
     * code spans and balanced brackets/parentheses. Returns `(labelRaw, url, afterCloseParen)`.
     */
    private fun matchMarkdownLink(text: String, openBracket: Int): Triple<String, String, Int>? {
        var bracketDepth = 0
        var k = openBracket
        var closeBracket = -1
        while (k < text.length) {
            val ch = text[k]
            if (ch == '\n') return null
            if (ch == '`') {
                val code = matchCodeSpan(text, k)
                if (code != null) {
                    k = code.third
                    continue
                }
                while (k < text.length && text[k] == '`') k += 1
                continue
            }
            if (ch == '[') {
                bracketDepth += 1
            } else if (ch == ']') {
                bracketDepth -= 1
                if (bracketDepth == 0) {
                    closeBracket = k
                    break
                }
            }
            k += 1
        }
        if (closeBracket <= openBracket + 1) return null
        if (closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null

        val urlStart = closeBracket + 2
        var parenDepth = 0
        var u = urlStart
        var closeParen = -1
        while (u < text.length) {
            val ch = text[u]
            if (ch.isWhitespace()) return null
            if (ch == '(') {
                parenDepth += 1
            } else if (ch == ')') {
                if (parenDepth == 0) {
                    closeParen = u
                    break
                }
                parenDepth -= 1
            }
            u += 1
        }
        if (closeParen <= urlStart) return null
        val url = text.substring(urlStart, closeParen)
        if (!linkUrlSchemeRe.containsMatchIn(url) && resolveLocalFilePath(url) == null) return null
        val labelRaw = text.substring(openBracket + 1, closeBracket)
        return Triple(labelRaw, url, closeParen + 1)
    }

    /**
     * Tokenize inline code (including CommonMark multi-backtick spans), Markdown links, bare
     * http(s) URLs, bold, italic, and strikethrough spans with recursive nesting.
     */
    fun parseInlineTokens(text: String): List<InlineToken> =
        parseInlineTokensInternal(text = text, depth = 0, insideLink = false)

    private fun parseInlineTokensInternal(
        text: String,
        depth: Int,
        insideLink: Boolean,
    ): List<InlineToken> {
        if (text.isEmpty()) return emptyList()
        val tokens = mutableListOf<InlineToken>()

        fun pushText(chunk: String) {
            if (chunk.isEmpty()) return
            val last = tokens.lastOrNull()
            if (last is InlineToken.Text) {
                tokens[tokens.lastIndex] = InlineToken.Text(last.text + chunk)
            } else {
                tokens.add(InlineToken.Text(chunk))
            }
        }

        fun parseNested(inner: String, nestedInsideLink: Boolean = insideLink): List<InlineToken> =
            if (depth < MAX_INLINE_DEPTH) {
                parseInlineTokensInternal(inner, depth + 1, nestedInsideLink)
            } else {
                listOf(InlineToken.Text(inner))
            }

        var i = 0
        while (i < text.length) {
            val ch = text[i]

            // 1. CommonMark inline code span (`...`, ``...``, etc.) — verbatim content
            if (ch == '`') {
                val code = matchCodeSpan(text, i)
                if (code != null) {
                    val content = normalizeCodeSpanContent(text.substring(code.first, code.second))
                    tokens.add(InlineToken.Code(content))
                    i = code.third
                    continue
                }
                var tickEnd = i
                while (tickEnd < text.length && text[tickEnd] == '`') tickEnd += 1
                pushText(text.substring(i, tickEnd))
                i = tickEnd
                continue
            }

            // 2. Markdown link `[label](url)` (non-nested inside links)
            if (!insideLink && ch == '[') {
                val link = matchMarkdownLink(text, i)
                if (link != null) {
                    val (labelRaw, url, afterClose) = link
                    tokens.add(
                        InlineToken.Link(
                            children = parseNested(labelRaw, nestedInsideLink = true),
                            url = url,
                            external = isHttpUrl(url),
                        )
                    )
                    i = afterClose
                    continue
                }
            }

            // 3. Bare http(s):// URL
            if (
                !insideLink &&
                (ch == 'h' || ch == 'H') &&
                (text.startsWith("http://", i, ignoreCase = true) ||
                    text.startsWith("https://", i, ignoreCase = true))
            ) {
                var end = i
                while (end < text.length && !text[end].isWhitespace() && text[end] != '<' && text[end] != '>') {
                    end += 1
                }
                val rawUrl = text.substring(i, end)
                val (cleanUrl, trailing) = trimBareUrl(rawUrl)
                if (cleanUrl.isNotEmpty() && httpHostRe.containsMatchIn(cleanUrl)) {
                    tokens.add(
                        InlineToken.Link(
                            children = listOf(InlineToken.Text(cleanUrl)),
                            url = cleanUrl,
                            external = true,
                        )
                    )
                    pushText(trailing)
                    i = end
                    continue
                }
            }

            // 4. Bold `**...**`
            if (ch == '*' && i + 1 < text.length && text[i + 1] == '*') {
                if (depth < MAX_INLINE_DEPTH) {
                    val closing = findClosingDoubleDelimiter(text, i + 2, '*')
                    if (closing != null) {
                        val (contentEnd, afterClose) = closing
                        tokens.add(InlineToken.Bold(parseNested(text.substring(i + 2, contentEnd))))
                        i = afterClose
                        continue
                    }
                }
                pushText("**")
                i += 2
                continue
            }

            // 5. Strikethrough `~~...~~`
            if (ch == '~' && i + 1 < text.length && text[i + 1] == '~') {
                if (depth < MAX_INLINE_DEPTH) {
                    val closing = findClosingDoubleDelimiter(text, i + 2, '~')
                    if (closing != null) {
                        val (contentEnd, afterClose) = closing
                        tokens.add(InlineToken.Strike(parseNested(text.substring(i + 2, contentEnd))))
                        i = afterClose
                        continue
                    }
                }
                pushText("~~")
                i += 2
                continue
            }

            // 6. Italic `*...*`
            if (ch == '*' && depth < MAX_INLINE_DEPTH) {
                val closeIdx = findClosingSingleItalic(text, i + 1)
                if (closeIdx != null) {
                    tokens.add(InlineToken.Italic(parseNested(text.substring(i + 1, closeIdx))))
                    i = closeIdx + 1
                    continue
                }
            }

            // Plain text fallback — consume up to the next potential delimiter
            val plainStart = i
            i += 1
            while (i < text.length) {
                val c = text[i]
                if (c == '`' || c == '[' || c == '*' || c == '~' || c == 'h' || c == 'H') break
                i += 1
            }
            pushText(text.substring(plainStart, i))
        }

        return tokens
    }

    private fun collectLinks(tokens: List<InlineToken>, out: MutableList<InlineToken.Link>) {
        for (tok in tokens) {
            when (tok) {
                is InlineToken.Link -> out.add(tok)
                is InlineToken.Bold -> collectLinks(tok.children, out)
                is InlineToken.Italic -> collectLinks(tok.children, out)
                is InlineToken.Strike -> collectLinks(tok.children, out)
                is InlineToken.Text, is InlineToken.Code -> Unit
            }
        }
    }

    /** Return the first external http(s) URL in a message (ignoring code blocks, inline code, and standalone images). */
    fun extractPreviewUrl(text: String): String? {
        if (text.isBlank()) return null
        val links = mutableListOf<InlineToken.Link>()
        for (seg in parseMessageSegments(text)) {
            when (seg) {
                is MessageSegment.Text -> {
                    links.clear()
                    collectLinks(parseInlineTokens(seg.text), links)
                    links.firstOrNull { it.external }?.let { return it.url }
                }

                is MessageSegment.Table -> {
                    val allRows = listOf(seg.headers) + seg.rows
                    for (row in allRows) {
                        for (cell in row) {
                            links.clear()
                            collectLinks(parseInlineTokens(cell), links)
                            links.firstOrNull { it.external }?.let { return it.url }
                        }
                    }
                }

                is MessageSegment.CodeBlock,
                is MessageSegment.Image,
                -> Unit
            }
        }
        return null
    }

    /** Extract all T3 Code thread links (either `t3-thread://v2/...` or `https://app.t3.codes/threads/...`). */
    fun extractT3ThreadLinks(text: String): List<Pair<String, String>> {
        if (text.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<Pair<String, String>>()
        val links = mutableListOf<InlineToken.Link>()
        for (seg in parseMessageSegments(text)) {
            if (seg !is MessageSegment.Text) continue
            links.clear()
            collectLinks(parseInlineTokens(seg.text), links)
            for (tok in links) {
                val resolved = resolveT3ThreadUrl(tok.url)
                    ?: if (isT3ThreadWebUrl(tok.url)) tok.url else null
                if (resolved != null && seen.add(resolved)) {
                    val labelText = tok.plainText()
                    val label = labelText.takeUnless { it == tok.url || it.isBlank() } ?: "Open T3 thread"
                    results.add(label to resolved)
                }
            }
        }
        return results
    }

    /** Returns true for image URLs safe and valid to render inline. */
    fun isRenderableImageUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        if (httpHostRe.containsMatchIn(trimmed)) return true
        if (relativePathRe.matches(trimmed) && !trimmed.startsWith("//")) return true
        if (dataImageBase64Re.containsMatchIn(trimmed)) return true
        return false
    }

    fun deriveImageDisplayName(alt: String, url: String): String {
        val cleanAlt = alt.trim()
        if (cleanAlt.isNotEmpty()) return cleanAlt
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("data:", ignoreCase = true)) return "Image"
        val withoutHash = trimmed.substringBefore('#')
        val withoutQuery = withoutHash.substringBefore('?')
        val last = withoutQuery.split('/').lastOrNull { it.isNotEmpty() } ?: return "Image"
        return try {
            URLDecoder.decode(last, StandardCharsets.UTF_8.name()).trim().ifEmpty { "Image" }
        } catch (_: Exception) {
            last
        }
    }

    /**
     * Compute natural-aspect-ratio display dimensions (in dp) for an inline Markdown image:
     * fits within [containerWidth] and clamps height to [maxHeight] so portrait images are never cropped.
     */
    fun computeInlineImageSize(
        naturalWidth: Int,
        naturalHeight: Int,
        containerWidth: Int,
        maxHeight: Int = INLINE_IMAGE_MAX_HEIGHT_DP,
    ): InlineImageBoxSize {
        val safeContainerW = if (containerWidth > 0) containerWidth else 280
        val safeMaxH = if (maxHeight > 0) maxHeight else INLINE_IMAGE_MAX_HEIGHT_DP

        if (naturalWidth <= 0 || naturalHeight <= 0) {
            val placeholderW = min(safeContainerW, 280)
            val placeholderH = min(safeMaxH, (placeholderW / INLINE_IMAGE_DEFAULT_ASPECT).roundToInt())
            return InlineImageBoxSize(
                width = max(INLINE_IMAGE_MIN_WIDTH_DP, placeholderW),
                height = max(INLINE_IMAGE_MIN_HEIGHT_DP, placeholderH),
            )
        }

        val aspect = naturalWidth.toDouble() / naturalHeight.toDouble()
        var width = safeContainerW.toDouble()
        var height = width / aspect

        if (height > safeMaxH) {
            height = safeMaxH.toDouble()
            width = height * aspect
        }

        return InlineImageBoxSize(
            width = min(safeContainerW, max(INLINE_IMAGE_MIN_WIDTH_DP, width.roundToInt())),
            height = min(safeMaxH, max(INLINE_IMAGE_MIN_HEIGHT_DP, height.roundToInt())),
        )
    }

    private fun splitTableRow(line: String): List<String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val pipeIndices = mutableListOf<Int>()
        var inCode = false
        var i = 0
        while (i < trimmed.length) {
            val ch = trimmed[i]
            if (ch == '\\' && !inCode && i + 1 < trimmed.length) {
                i += 2
                continue
            }
            if (ch == '`') {
                inCode = !inCode
                i += 1
                continue
            }
            if (ch == '|' && !inCode) {
                pipeIndices.add(i)
            }
            i += 1
        }
        if (pipeIndices.isEmpty()) return null

        var start = 0
        var end = trimmed.length
        var firstPipe = 0
        var lastPipe = pipeIndices.lastIndex

        if (pipeIndices[firstPipe] == 0) {
            start = 1
            firstPipe += 1
        }
        if (lastPipe >= firstPipe && pipeIndices[lastPipe] == trimmed.lastIndex) {
            end = trimmed.lastIndex
            lastPipe -= 1
        }

        val rawCells = mutableListOf<String>()
        var cursor = start
        for (p in firstPipe..lastPipe) {
            val idx = pipeIndices[p]
            rawCells.add(trimmed.substring(cursor, idx))
            cursor = idx + 1
        }
        rawCells.add(trimmed.substring(cursor, end))
        if (rawCells.isEmpty()) return null

        return rawCells.map { cell ->
            val out = StringBuilder(cell.length)
            var code = false
            var j = 0
            while (j < cell.length) {
                val ch = cell[j]
                if (ch == '`') {
                    code = !code
                    out.append(ch)
                    j += 1
                } else if (ch == '\\' && !code && j + 1 < cell.length && cell[j + 1] == '|') {
                    out.append('|')
                    j += 2
                } else {
                    out.append(ch)
                    j += 1
                }
            }
            out.toString().trim()
        }
    }

    private fun parseTableSeparator(line: String): List<TableAlignment>? {
        val cells = splitTableRow(line) ?: return null
        if (cells.isEmpty()) return null
        val alignments = mutableListOf<TableAlignment>()
        for (cell in cells) {
            if (!tableAlignCellRe.matches(cell)) return null
            val left = cell.startsWith(':')
            val right = cell.endsWith(':')
            alignments.add(
                when {
                    left && right -> TableAlignment.CENTER
                    right -> TableAlignment.RIGHT
                    else -> TableAlignment.LEFT
                }
            )
        }
        return alignments
    }

    private fun parseMarkdownDestination(text: String, openParenIdx: Int): Pair<String, Int>? {
        if (openParenIdx >= text.length || text[openParenIdx] != '(') return null
        var i = openParenIdx + 1
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i += 1
        if (i >= text.length) return null

        val url: String
        if (text[i] == '<') {
            i += 1
            val start = i
            while (i < text.length && text[i] != '>' && text[i] != '\n') {
                i += if (text[i] == '\\' && i + 1 < text.length) 2 else 1
            }
            if (i >= text.length || text[i] != '>') return null
            url = text.substring(start, i).trim()
            i += 1
        } else {
            val start = i
            var depth = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch == '\\' && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (ch == '(') {
                    depth += 1
                    i += 1
                    continue
                }
                if (ch == ')') {
                    if (depth == 0) break
                    depth -= 1
                    i += 1
                    continue
                }
                if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') break
                i += 1
            }
            url = text.substring(start, i).trim()
        }

        if (url.isEmpty()) return null

        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i += 1
        if (i < text.length && (text[i] == '"' || text[i] == '\'' || text[i] == '(')) {
            val closeCh = if (text[i] == '(') ')' else text[i]
            i += 1
            while (i < text.length && text[i] != closeCh && text[i] != '\n') {
                i += if (text[i] == '\\' && i + 1 < text.length) 2 else 1
            }
            if (i >= text.length || text[i] != closeCh) return null
            i += 1
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i += 1
        }

        if (i >= text.length || text[i] != ')') return null
        return url to (i + 1)
    }

    private data class ParsedMdImage(val alt: String, val url: String, val endIdx: Int)

    private fun parseMarkdownImageAt(text: String, bangIdx: Int): ParsedMdImage? {
        if (bangIdx + 1 >= text.length || text[bangIdx] != '!' || text[bangIdx + 1] != '[') return null
        var i = bangIdx + 2
        val alt = StringBuilder()
        var depth = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                alt.append(text[i + 1])
                i += 2
                continue
            }
            if (ch == '\n' && i + 1 < text.length && text[i + 1] == '\n') return null
            if (ch == '[') {
                depth += 1
                alt.append(ch)
                i += 1
                continue
            }
            if (ch == ']') {
                if (depth == 0) break
                depth -= 1
                alt.append(ch)
                i += 1
                continue
            }
            alt.append(ch)
            i += 1
        }
        if (i + 1 >= text.length || text[i] != ']' || text[i + 1] != '(') return null
        val (url, endIdx) = parseMarkdownDestination(text, i + 1) ?: return null
        if (!isRenderableImageUrl(url)) return null
        return ParsedMdImage(alt = alt.toString().trim(), url = url, endIdx = endIdx)
    }

    private fun splitTextAndImages(text: String): List<MessageSegment> {
        if (text.isEmpty() || !text.contains("![")) {
            return if (text.isNotBlank()) listOf(MessageSegment.Text(text)) else emptyList()
        }

        val out = mutableListOf<MessageSegment>()
        var textStart = 0

        fun flushTextUntil(endIdx: Int) {
            if (endIdx <= textStart) return
            val chunk = text.substring(textStart, endIdx).trim('\n')
            if (chunk.isNotBlank()) {
                out.add(MessageSegment.Text(chunk))
            }
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                i += 2
                continue
            }
            if (ch == '`') {
                var tickCount = 1
                while (i + tickCount < text.length && text[i + tickCount] == '`') tickCount += 1
                val closer = "`".repeat(tickCount)
                val closeIdx = text.indexOf(closer, i + tickCount)
                i = if (closeIdx != -1) closeIdx + tickCount else i + tickCount
                continue
            }
            // Linked image: `[![alt](imgUrl)](linkUrl)`
            if (ch == '[' && i + 2 < text.length && text[i + 1] == '!' && text[i + 2] == '[') {
                val inner = parseMarkdownImageAt(text, i + 1)
                if (inner != null && inner.endIdx + 1 < text.length &&
                    text[inner.endIdx] == ']' && text[inner.endIdx + 1] == '('
                ) {
                    val outerDest = parseMarkdownDestination(text, inner.endIdx + 1)
                    if (outerDest != null) {
                        flushTextUntil(i)
                        val resolvedLink = resolveChatLinkUrl(outerDest.first)
                        out.add(MessageSegment.Image(alt = inner.alt, url = inner.url, linkUrl = resolvedLink))
                        i = outerDest.second
                        textStart = i
                        continue
                    }
                }
            }
            // Standalone image: `![alt](imgUrl)`
            if (ch == '!' && i + 1 < text.length && text[i + 1] == '[') {
                val parsed = parseMarkdownImageAt(text, i)
                if (parsed != null) {
                    flushTextUntil(i)
                    out.add(MessageSegment.Image(alt = parsed.alt, url = parsed.url))
                    i = parsed.endIdx
                    textStart = i
                    continue
                }
            }
            i += 1
        }
        flushTextUntil(text.length)
        return out
    }

    private fun splitTextAndTables(text: String): List<MessageSegment> {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n')
        val out = mutableListOf<MessageSegment>()
        val textBuf = mutableListOf<String>()

        fun flushText() {
            val joined = textBuf.joinToString("\n").trim('\n')
            textBuf.clear()
            if (joined.isNotBlank()) {
                out.addAll(splitTextAndImages(joined))
            }
        }

        var i = 0
        while (i < lines.size) {
            if (i + 1 < lines.size) {
                val headers = splitTableRow(lines[i])
                val alignments = if (headers != null) parseTableSeparator(lines[i + 1]) else null
                if (headers != null && alignments != null && headers.size == alignments.size) {
                    flushText()
                    val colCount = headers.size
                    val rows = mutableListOf<List<String>>()
                    var j = i + 2
                    while (j < lines.size) {
                        val line = lines[j]
                        if (line.isBlank()) break
                        val cells = splitTableRow(line) ?: break
                        val normalizedRow = when {
                            cells.size == colCount -> cells
                            cells.size < colCount -> cells + List(colCount - cells.size) { "" }
                            else -> cells.subList(0, colCount)
                        }
                        rows.add(normalizedRow)
                        j += 1
                    }
                    out.add(MessageSegment.Table(headers = headers, alignments = alignments, rows = rows))
                    i = j
                    continue
                }
            }
            textBuf.add(lines[i])
            i += 1
        }
        flushText()
        return out
    }

    /** Split message text into plain text, GFM tables, Markdown images, and fenced code blocks. */
    fun parseMessageSegments(text: String): List<MessageSegment> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<MessageSegment>()
        var lastIndex = 0
        for (match in codeFenceRe.findAll(text)) {
            if (match.range.first > lastIndex) {
                val plain = text.substring(lastIndex, match.range.first)
                if (plain.isNotBlank()) {
                    out.addAll(splitTextAndTables(plain.trim('\r', '\n')))
                }
            }
            val lang = match.groups[1]?.value?.trim() ?: ""
            val rawCode = match.groups[2]?.value ?: ""
            val code = rawCode.removeSuffix("\r\n").removeSuffix("\n")
            out.add(MessageSegment.CodeBlock(lang = lang, code = code))
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) {
            val tail = text.substring(lastIndex)
            if (tail.isNotBlank() || out.isEmpty()) {
                val trimmedTail = tail.trim('\r', '\n')
                val parsed = splitTextAndTables(trimmedTail)
                if (parsed.isNotEmpty()) {
                    out.addAll(parsed)
                } else if (out.isEmpty()) {
                    out.add(MessageSegment.Text(trimmedTail))
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Labels, Progress & Formatting

    fun formatEngineLabel(engine: String?): String =
        if (engine?.trim()?.lowercase(Locale.US) == ChatEngines.GEMINI) "Gemini" else "Claude"

    fun shortModelLabel(modelId: String?, label: String? = null): String {
        val rawLabel = label?.trim().orEmpty()
        val rawId = modelId?.trim()?.lowercase(Locale.US).orEmpty()

        if (rawLabel.isNotEmpty()) {
            geminiLabelRe.matchEntire(rawLabel)?.let { return it.groupValues[1].trim() }
            if (orchestratorLabelRe.containsMatchIn(rawLabel)) {
                val paren = parenCaptureRe.find(rawLabel)?.groupValues?.get(1)?.trim()
                return if (!paren.isNullOrEmpty()) "Orch $paren" else "Orch"
            }
            claudeLabelRe.matchEntire(rawLabel)?.let { return it.groupValues[1] }
            return rawLabel
        }

        if (rawId.isNotEmpty()) {
            val parts = rawId.split('-').filter { it.isNotEmpty() }
            if (parts.firstOrNull() == "claude" && parts.size >= 2) {
                return parts[1].replaceFirstChar { it.uppercaseChar() }
            }
            if (parts.firstOrNull() == "gemini" && parts.size >= 2) {
                return parts.last().replaceFirstChar { it.uppercaseChar() }
            }
            return rawId
        }
        return ""
    }

    fun shortEffortLabel(effort: String?): String {
        val v = effort?.trim()?.lowercase(Locale.US).orEmpty()
        if (v.isEmpty()) return ""
        return when (v) {
            "medium" -> "Med"
            "xhigh" -> "XHigh"
            else -> v.replaceFirstChar { it.uppercaseChar() }
        }
    }

    fun formatComposerChipLabel(
        engine: String,
        modelId: String?,
        modelLabel: String?,
        effort: String?,
    ): String {
        val m = shortModelLabel(modelId, modelLabel).ifEmpty { formatEngineLabel(engine) }
        val e = shortEffortLabel(effort)
        return if (e.isNotEmpty()) "$m · $e" else m
    }

    fun formatAttachmentSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes.toDouble() / 1024.0
        if (kb < 1024.0) {
            return if (kb < 10.0) String.format(Locale.US, "%.1f KB", kb)
            else "${kb.roundToInt()} KB"
        }
        val mb = kb / 1024.0
        return if (mb < 10.0) String.format(Locale.US, "%.1f MB", mb)
        else "${mb.roundToInt()} MB"
    }

    fun formatProgressDuration(seconds: Double): String {
        val total = max(1, if (seconds.isFinite()) seconds.roundToInt() else 0)
        if (total < 60) return "${total}s"
        val mins = total / 60
        val secs = total % 60
        return if (secs == 0) "${mins}m" else "${mins}m ${secs}s"
    }

    fun formatProgressSummary(progress: ChatProgress?): String? {
        if (progress == null || progress.steps.isEmpty()) return null
        val count = if ((progress.stepCount ?: 0) > 0) progress.stepCount!! else progress.steps.size
        val noun = if (count == 1) "step" else "steps"
        val dur = progress.durationS
        return if (dur != null && dur.isFinite() && dur > 0.0) {
            "Worked for ${formatProgressDuration(dur)} · $count $noun"
        } else {
            "$count $noun"
        }
    }

    fun currentProgressStepText(progress: ChatProgress?): String {
        val last = progress?.steps?.lastOrNull()?.text?.trim()
        return if (!last.isNullOrEmpty()) last else "Thinking…"
    }

    /**
     * Parse an activity/tool status line into inline Markdown tokens while cleaning up
     * stray or server-truncated unclosed backticks (e.g. ``running `set -e; long cmd…``)
     * so raw backtick characters are never displayed in the live activity shimmer or timeline.
     */
    private fun closeTruncatedBacktickSpan(text: String): String {
        var i = 0
        while (i < text.length) {
            if (text[i] == '`') {
                val matched = matchCodeSpan(text, i)
                if (matched != null) {
                    i = matched.third
                    continue
                }
                var tickEnd = i
                while (tickEnd < text.length && text[tickEnd] == '`') tickEnd += 1
                val openTicks = text.substring(i, tickEnd)
                val remainder = text.substring(tickEnd)
                return if (remainder.isNotBlank()) {
                    val pad = if (remainder.endsWith('`')) " " else ""
                    text + pad + openTicks
                } else {
                    text.substring(0, i)
                }
            }
            i += 1
        }
        return text
    }

    fun parseActivityStepTokens(raw: String): List<InlineToken> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parsed = parseInlineTokens(closeTruncatedBacktickSpan(trimmed))
        val cleaned = mutableListOf<InlineToken>()

        fun appendTokens(tokens: List<InlineToken>) {
            for (tok in tokens) {
                when (tok) {
                    is InlineToken.Text -> {
                        val tickIdx = tok.text.indexOf('`')
                        if (tickIdx < 0) {
                            cleaned.add(tok)
                        } else {
                            val before = tok.text.substring(0, tickIdx)
                            val after = tok.text.substring(tickIdx + 1).replace("`", "").trim()
                            if (before.isNotEmpty()) {
                                cleaned.add(InlineToken.Text(before))
                            }
                            if (after.isNotEmpty()) {
                                cleaned.add(InlineToken.Code(after))
                            }
                        }
                    }

                    is InlineToken.Code -> {
                        val code = tok.code.trim()
                        if (code.isNotEmpty()) cleaned.add(InlineToken.Code(code))
                    }

                    is InlineToken.Bold -> cleaned.add(
                        InlineToken.Bold(parseActivityStepTokensChildren(tok.children)),
                    )

                    is InlineToken.Italic -> cleaned.add(
                        InlineToken.Italic(parseActivityStepTokensChildren(tok.children)),
                    )

                    is InlineToken.Strike -> cleaned.add(
                        InlineToken.Strike(parseActivityStepTokensChildren(tok.children)),
                    )

                    is InlineToken.Link -> {
                        // Flatten links in compact activity status lines to their readable label tokens
                        appendTokens(tok.children)
                    }
                }
            }
        }

        appendTokens(parsed)
        return cleaned
    }

    private fun parseActivityStepTokensChildren(children: List<InlineToken>): List<InlineToken> =
        children.flatMap { tok ->
            when (tok) {
                is InlineToken.Text -> {
                    val tickIdx = tok.text.indexOf('`')
                    if (tickIdx < 0) {
                        listOf(tok)
                    } else {
                        buildList {
                            val before = tok.text.substring(0, tickIdx)
                            val after = tok.text.substring(tickIdx + 1).replace("`", "").trim()
                            if (before.isNotEmpty()) add(InlineToken.Text(before))
                            if (after.isNotEmpty()) add(InlineToken.Code(after))
                        }
                    }
                }
                is InlineToken.Link -> parseActivityStepTokensChildren(tok.children)
                else -> listOf(tok)
            }
        }

    fun formatChatTime(epochSec: Double, nowMs: Long = System.currentTimeMillis()): String {
        if (!epochSec.isFinite() || epochSec <= 0.0) return ""
        val msgDate = Date((epochSec * 1000.0).toLong())
        val msgCal = Calendar.getInstance().apply { time = msgDate }
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val sameDay = msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
            msgCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
        val timeFmt = SimpleDateFormat("h:mm a", Locale.US).format(msgDate).lowercase(Locale.US)
        if (sameDay) return timeFmt
        val dateFmt = SimpleDateFormat("MMM d", Locale.US).format(msgDate)
        return "$dateFmt, $timeFmt"
    }

    private fun hasSendableContent(msg: ChatMessage): Boolean =
        msg.text.isNotBlank() || !msg.attachments.isNullOrEmpty()

    /**
     * Finds the preceding user message that corresponds to the message at [index]
     * (typically an assistant error bubble) so it can be retried with one tap.
     * Stops at system conversation boundaries.
     */
    fun findPrecedingUserMessage(messages: List<ChatMessage>, index: Int): ChatMessage? {
        if (index <= 0 || index >= messages.size) return null
        val target = messages[index]
        val replyTo = target.replyToLegacyId()
        if (!replyTo.isNullOrEmpty()) {
            for (i in index - 1 downTo 0) {
                val m = messages[i]
                if (m.role == ChatRoles.USER && (m.id == replyTo || m.clientId == replyTo) && hasSendableContent(m)) {
                    return m
                }
            }
        }
        for (i in index - 1 downTo 0) {
            val m = messages[i]
            if (m.role == ChatRoles.SYSTEM) return null
            if (m.role == ChatRoles.USER && hasSendableContent(m)) {
                return m
            }
        }
        return null
    }

    fun formatMessagePreview(msg: ChatMessage): String {
        val body = msg.text.trim()
        if (body.isNotEmpty()) {
            val cleaned = MarkdownStripper.stripToSingleLine(body)
            return if (cleaned.isNotEmpty()) cleaned else whitespaceRe.replace(body, " ")
        }
        if (msg.status == ChatStatuses.ERROR && !msg.error.isNullOrBlank()) {
            return whitespaceRe.replace(msg.error.trim(), " ")
        }
        val atts = msg.attachments.orEmpty()
        if (atts.size == 1) {
            return atts[0].name.ifBlank { if (atts[0].isImage) "Photo" else "Attachment" }
        }
        if (atts.size > 1) {
            return "${atts.size} attachments"
        }
        return ""
    }

    fun buildReplyRef(msg: ChatMessage, maxLen: Int = 240): ChatReplyRef {
        var snippet = formatMessagePreview(msg)
        if (snippet.isEmpty()) {
            val atts = msg.attachments.orEmpty()
            snippet = when {
                atts.size == 1 -> if (atts[0].isImage) "[image]" else "[file]"
                atts.size > 1 -> "[${atts.size} attachments]"
                else -> ""
            }
        }
        if (snippet.length > maxLen) {
            snippet = snippet.substring(0, maxLen - 1) + "…"
        }
        return ChatReplyRef(
            id = msg.id,
            role = msg.role,
            engine = msg.engine,
            text = snippet,
        )
    }

    fun replySenderLabel(role: String, engine: String? = null): String = when (role) {
        ChatRoles.USER -> "You"
        ChatRoles.SYSTEM -> "System"
        else -> formatEngineLabel(engine)
    }

    fun replySenderLabel(ref: ChatReplyRef): String = replySenderLabel(ref.role, ref.engine)

    fun isReplyableMessage(msg: ChatMessage): Boolean {
        if (msg.role == ChatRoles.SYSTEM) return false
        if (msg.status == ChatStatuses.PENDING || msg.optimistic) return false
        return msg.role == ChatRoles.USER ||
            msg.status == ChatStatuses.ERROR ||
            msg.text.isNotBlank() ||
            !msg.attachments.isNullOrEmpty()
    }

    fun shouldCaptureSwipeReply(dx: Float, dy: Float): Boolean {
        if (!dx.isFinite() || !dy.isFinite()) return false
        return dx > SWIPE_REPLY_ACTIVATION_DX && dx > abs(dy) * SWIPE_REPLY_DIRECTION_RATIO
    }

    fun computeSwipeReplyOffset(dx: Float): Float {
        if (!dx.isFinite() || dx <= 0f) return 0f
        if (dx <= SWIPE_REPLY_THRESHOLD) return dx
        val excess = dx - SWIPE_REPLY_THRESHOLD
        val headroom = SWIPE_REPLY_MAX_DRAG - SWIPE_REPLY_THRESHOLD
        return (SWIPE_REPLY_THRESHOLD + headroom * (1.0 - exp(-excess / 48.0))).toFloat()
    }

    private fun findSingleNewlineInsertion(
        prevText: String,
        nextText: String,
        prevSelectionStart: Int?,
        prevSelectionEnd: Int?,
    ): Pair<Int, Int>? {
        if (prevSelectionStart != null && prevSelectionEnd != null) {
            val start = min(prevSelectionStart, prevSelectionEnd).coerceIn(0, prevText.length)
            val end = max(prevSelectionStart, prevSelectionEnd).coerceIn(0, prevText.length)
            if (nextText.length == prevText.length - (end - start) + 1 &&
                nextText == prevText.substring(0, start) + "\n" + prevText.substring(end)
            ) {
                return start to end
            }
        }

        if (nextText.length != prevText.length + 1) return null
        var prefixLen = 0
        while (prefixLen < prevText.length && prevText[prefixLen] == nextText[prefixLen]) {
            prefixLen += 1
        }
        var suffixLen = 0
        val maxSuffix = prevText.length - prefixLen
        while (suffixLen < maxSuffix &&
            prevText[prevText.lastIndex - suffixLen] == nextText[nextText.lastIndex - suffixLen]
        ) {
            suffixLen += 1
        }
        if (prefixLen + suffixLen != prevText.length) return null
        if (nextText[prefixLen] != '\n') return null
        return prefixLen to prefixLen
    }

    /**
     * Smart list continuation & double-Enter disengage for the chat composer.
     * Triggers ONLY when a single newline (`\n`) is inserted.
     */
    fun applyChatListAutoFormat(
        prevText: String,
        nextText: String,
        prevSelectionStart: Int? = null,
        prevSelectionEnd: Int? = null,
    ): ChatListAutoFormatResult {
        val insertion = findSingleNewlineInsertion(prevText, nextText, prevSelectionStart, prevSelectionEnd)
            ?: return ChatListAutoFormatResult(nextText, nextText.length, nextText.length, changed = false)

        val (insertPos, afterPos) = insertion
        val lineStart = if (insertPos == 0) 0 else prevText.lastIndexOf('\n', insertPos - 1) + 1
        val nextNewline = prevText.indexOf('\n', afterPos)
        val lineEnd = if (nextNewline == -1) prevText.length else nextNewline

        val lineBeforeCursor = prevText.substring(lineStart, insertPos)
        val lineAfterCursor = prevText.substring(afterPos, lineEnd)

        var nextMarker: String? = null
        var contentBefore = ""

        val orderedMatch = orderedListRe.matchEntire(lineBeforeCursor)
        if (orderedMatch != null) {
            val indent = orderedMatch.groupValues[1]
            val numStr = orderedMatch.groupValues[2]
            val delim = orderedMatch.groupValues[3]
            val content = orderedMatch.groupValues[4]
            val num = numStr.toLongOrNull()
            if (num != null && numStr.length <= 9) {
                nextMarker = "$indent${num + 1}$delim "
                contentBefore = content
            }
        } else {
            val bulletMatch = bulletListRe.matchEntire(lineBeforeCursor)
            if (bulletMatch != null) {
                val indent = bulletMatch.groupValues[1]
                val bullet = bulletMatch.groupValues[2]
                val content = bulletMatch.groupValues[3]
                nextMarker = "$indent$bullet "
                contentBefore = content
            }
        }

        if (nextMarker == null) {
            val cursor = insertPos + 1
            return ChatListAutoFormatResult(nextText, cursor, cursor, changed = false)
        }

        // Double-Enter disengage when line only holds the marker.
        if (contentBefore.isBlank() && lineAfterCursor.isBlank()) {
            val beforeLine = prevText.substring(0, lineStart)
            val afterLine = prevText.substring(lineEnd)
            val formatted = beforeLine + afterLine
            val cursor = beforeLine.length
            return ChatListAutoFormatResult(formatted, cursor, cursor, changed = true)
        }

        val isMidLineSplit = contentBefore.isNotBlank() && lineAfterCursor.isNotBlank()
        val rawPrefix = prevText.substring(0, insertPos)
        val prefix = if (isMidLineSplit) rawPrefix.trimEnd(' ', '\t') else rawPrefix
        val suffix = prevText.substring(afterPos).trimStart(' ', '\t')
        val inserted = "\n$nextMarker"
        val formatted = prefix + inserted + suffix
        val cursor = prefix.length + inserted.length
        return ChatListAutoFormatResult(formatted, cursor, cursor, changed = true)
    }
}
