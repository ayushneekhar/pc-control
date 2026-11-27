package com.neekhar.pccontrol.feature.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

object ChatEngines {
    const val CLAUDE = "claude"
    const val GEMINI = "gemini"

    fun normalize(raw: String?): String =
        if (raw?.trim()?.lowercase() == GEMINI) GEMINI else CLAUDE
}

object ChatRoles {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"
}

object ChatStatuses {
    const val PENDING = "pending"
    const val DONE = "done"
    const val ERROR = "error"
}

@Serializable
data class ChatAttachment(
    val id: String,
    val name: String,
    @SerialName("mime_type") val mimeType: String = "application/octet-stream",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val kind: String = "file", // "image" | "file"
    val url: String = "",
) {
    val isImage: Boolean get() = kind == "image"
}

@Serializable
data class ChatReplyRef(
    val id: String,
    val role: String,
    val engine: String? = null,
    val text: String = "",
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("role", role)
        if (!engine.isNullOrBlank()) put("engine", engine)
        put("text", text)
    }
}

@Serializable
data class ChatProgressStep(
    val t: Double = 0.0,
    val text: String = "",
)

@Serializable
data class ChatProgress(
    val engine: String? = null,
    val started: Double = 0.0,
    @SerialName("duration_s") val durationS: Double? = null,
    @SerialName("step_count") val stepCount: Int? = null,
    val steps: List<ChatProgressStep> = emptyList(),
)

/**
 * Matches `/pc/api/chat/messages`, `/pc/api/chat/send`, `/pc/api/chat/steer`, and SSE `message` payloads.
 * `reply_to` may be either a legacy string message ID (on assistant messages) or a structured
 * [ChatReplyRef] JSON object (on user messages quoting another message), so it is decoded as [JsonElement].
 */
@Serializable
data class ChatMessage(
    val id: String,
    val role: String,
    val engine: String = ChatEngines.CLAUDE,
    val text: String = "",
    @SerialName("created_at") val createdAt: Double = 0.0,
    val status: String = ChatStatuses.DONE,
    val error: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("reply_to") val replyTo: JsonElement? = null,
    val kind: String? = null,
    val attachments: List<ChatAttachment>? = null,
    val progress: ChatProgress? = null,
    val optimistic: Boolean = false,
    val queued: Boolean = false,
    val steered: Boolean = false,
) {
    val isUser: Boolean get() = role == ChatRoles.USER
    val isAssistant: Boolean get() = role == ChatRoles.ASSISTANT
    val isSystem: Boolean get() = role == ChatRoles.SYSTEM
    val isPending: Boolean get() = status == ChatStatuses.PENDING
    val isError: Boolean get() = status == ChatStatuses.ERROR

    /** Extracts a structured quoted [ChatReplyRef], ignoring legacy string `reply_to` IDs. */
    fun replyRef(): ChatReplyRef? {
        val obj = replyTo as? JsonObject ?: return null
        val refId = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val refRole = (obj["role"] as? JsonPrimitive)?.contentOrNull ?: ChatRoles.USER
        val refEngine = (obj["engine"] as? JsonPrimitive)?.contentOrNull
        val refText = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: ""
        return ChatReplyRef(id = refId, role = refRole, engine = refEngine, text = refText)
    }

    /** Extracts a legacy string `reply_to` ID (used on assistant messages pointing to the triggering user message). */
    fun replyToLegacyId(): String? {
        val prim = replyTo as? JsonPrimitive ?: return null
        if (!prim.isString) return null
        return prim.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}

@Serializable
data class ChatMessagesResponse(
    val messages: List<ChatMessage> = emptyList(),
    val engine: String = ChatEngines.CLAUDE,
    val running: Boolean = false,
    val progress: ChatProgress? = null,
    @SerialName("queued_ids") val queuedIds: List<String> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    val ts: Long = 0L,
)

@Serializable
data class ChatSendRequest(
    val text: String,
    @SerialName("client_id") val clientId: String,
    val attachments: List<ChatAttachment>? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
)

@Serializable
data class ChatSendResponse(
    val ok: Boolean = true,
    val deduped: Boolean = false,
    @SerialName("user_message") val userMessage: ChatMessage,
    @SerialName("assistant_message") val assistantMessage: ChatMessage? = null,
    val engine: String = ChatEngines.CLAUDE,
    val running: Boolean = false,
)

@Serializable
data class ChatSteerRequest(
    @SerialName("message_id") val messageId: String,
)

@Serializable
data class ChatSteerResponse(
    val ok: Boolean = true,
    val status: String = "steered",
    val steered: Boolean = false,
    @SerialName("user_message") val userMessage: ChatMessage,
    val running: Boolean = false,
)

@Serializable
data class ChatSteerErrorBody(
    val error: String? = null,
    @SerialName("user_message") val userMessage: ChatMessage? = null,
    val running: Boolean? = null,
)

@Serializable
data class ChatNewResponse(
    val ok: Boolean = true,
    val engine: String = ChatEngines.CLAUDE,
    @SerialName("boundary_message") val boundaryMessage: ChatMessage,
    val running: Boolean = false,
)

@Serializable
data class ChatUploadRequest(
    val name: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("data_base64") val dataBase64: String,
)

sealed interface ChatSseEvent {
    data class Snapshot(
        val engine: String,
        val running: Boolean,
        val progress: ChatProgress?,
        val queuedIds: List<String>,
        val ts: Long,
    ) : ChatSseEvent

    data class Message(
        val message: ChatMessage,
        val running: Boolean,
        val engine: String?,
        val progress: ChatProgress?,
        val hasProgressField: Boolean,
        val steered: Boolean?,
        val removedMessageId: String?,
        val ts: Long,
    ) : ChatSseEvent

    data class Progress(
        val running: Boolean,
        val progress: ChatProgress?,
        val ts: Long,
    ) : ChatSseEvent
}

@Serializable
data class LinkPreview(
    val url: String,
    val domain: String = "",
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    @SerialName("site_name") val siteName: String? = null,
)

@Serializable
data class LinkPreviewResponse(
    val ok: Boolean = true,
    val preview: LinkPreview? = null,
)

@Serializable
data class ChatCacheState(
    val messages: List<ChatMessage> = emptyList(),
    val engine: String = ChatEngines.CLAUDE,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class AgentModelOption(
    val id: String,
    val label: String,
)

@Serializable
data class GeminiInstanceOption(
    val id: String,
    val label: String,
    @SerialName("signed_in") val signedIn: Boolean = false,
    val email: String? = null,
)

@Serializable
data class ClaudeAgentConfig(
    val account: String? = null,
    val model: String = "",
    val effort: String = "",
    val models: List<AgentModelOption> = emptyList(),
    val efforts: List<String> = emptyList(),
)

@Serializable
data class GeminiAgentConfig(
    @SerialName("instance_id") val instanceId: String = "antigravity",
    val model: String = "",
    @SerialName("thought_level") val thoughtLevel: String = "",
    @SerialName("auto_rotate") val autoRotate: Boolean = false,
    val instances: List<GeminiInstanceOption> = emptyList(),
    val models: List<AgentModelOption> = emptyList(),
    val efforts: List<String> = emptyList(),
)

@Serializable
data class ChatAgentSettings(
    val claude: ClaudeAgentConfig = ClaudeAgentConfig(),
    val gemini: GeminiAgentConfig = GeminiAgentConfig(),
)

@Serializable
data class ClaudeSettingsPatch(
    val model: String? = null,
    val effort: String? = null,
)

@Serializable
data class GeminiSettingsPatch(
    @SerialName("instance_id") val instanceId: String? = null,
    val model: String? = null,
    @SerialName("thought_level") val thoughtLevel: String? = null,
)

@Serializable
data class ChatAgentSettingsPatch(
    val claude: ClaudeSettingsPatch? = null,
    val gemini: GeminiSettingsPatch? = null,
) {
    val isEmpty: Boolean get() = claude == null && gemini == null
}

@Serializable
data class ChatAgentSwitchRequest(
    val engine: String,
)

@Serializable
data class ChatAgentState(
    val engine: String = ChatEngines.CLAUDE,
    @SerialName("claude_active") val claudeActive: Boolean = false,
    @SerialName("gemini_active") val geminiActive: Boolean = false,
    val available: Boolean = true,
    val switching: Boolean = false,
)

data class AgentSelectionResult(
    val engine: String,
    val patch: ChatAgentSettingsPatch,
)

data class PreviewImageItem(
    val url: String,
    val name: String,
    val mimeType: String? = null,
    val linkUrl: String? = null,
)

data class EditingMessageState(
    val messageId: String,
    val previewText: String,
    val previousDraft: String,
    val previousAttachments: List<ChatAttachment>,
)

class PickedLocalFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
)

@Serializable
internal data class ChatErrorPayload(
    val error: String? = null,
)
