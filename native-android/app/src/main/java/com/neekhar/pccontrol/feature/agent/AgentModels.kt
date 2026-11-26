package com.neekhar.pccontrol.feature.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AgentOption(
    val id: String,
    val label: String,
    val model: String = "",
)

@Serializable
data class AgentState(
    val engine: String = "claude", // "claude" | "gemini"
    @SerialName("claude_active") val claudeActive: Boolean = false,
    @SerialName("gemini_active") val geminiActive: Boolean = false,
    val available: Boolean = false,
    val switching: Boolean = false,
    val options: List<AgentOption> = emptyList(),
)

@Serializable
data class SetAgentEngineRequest(
    val engine: String,
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
data class UsageAlertsConfig(
    val enabled: Boolean = true,
    @SerialName("five_hour_pct") val fiveHourPct: Int = 20,
    @SerialName("weekly_pct") val weeklyPct: Int = 15,
)

@Serializable
data class ClaudeSettings(
    val account: String? = null,
    val model: String = "",
    val effort: String = "",
    val models: List<AgentModelOption> = emptyList(),
    val efforts: List<String> = emptyList(),
)

@Serializable
data class GeminiSettings(
    @SerialName("instance_id") val instanceId: String = "",
    val model: String = "",
    @SerialName("thought_level") val thoughtLevel: String = "",
    @SerialName("auto_rotate") val autoRotate: Boolean = false,
    val instances: List<GeminiInstanceOption> = emptyList(),
    val models: List<AgentModelOption> = emptyList(),
    val efforts: List<String> = emptyList(),
)

@Serializable
data class AgentSettings(
    val claude: ClaudeSettings = ClaudeSettings(),
    val gemini: GeminiSettings = GeminiSettings(),
    @SerialName("usage_alerts") val usageAlerts: UsageAlertsConfig? = null,
)

@Serializable
data class ClaudePatch(
    val model: String? = null,
    val effort: String? = null,
)

@Serializable
data class GeminiPatch(
    @SerialName("instance_id") val instanceId: String? = null,
    val model: String? = null,
    @SerialName("thought_level") val thoughtLevel: String? = null,
    @SerialName("auto_rotate") val autoRotate: Boolean? = null,
)

@Serializable
data class UsageAlertsPatch(
    val enabled: Boolean? = null,
    @SerialName("five_hour_pct") val fiveHourPct: Int? = null,
    @SerialName("weekly_pct") val weeklyPct: Int? = null,
)

@Serializable
data class AgentSettingsPatch(
    val claude: ClaudePatch? = null,
    val gemini: GeminiPatch? = null,
    @SerialName("usage_alerts") val usageAlerts: UsageAlertsPatch? = null,
)

@Serializable
data class ResetAgentResponse(
    val ok: Boolean = false,
    val message: String = "",
)
