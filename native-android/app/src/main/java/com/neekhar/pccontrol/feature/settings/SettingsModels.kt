package com.neekhar.pccontrol.feature.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

enum class ThemeMode(val id: String, val label: String) {
    DARK("dark", "Dark"),
    LIGHT("light", "Light"),
    SYSTEM("system", "System");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.find { it.id == id } ?: DARK
    }
}

@Serializable
data class RootdInfo(
    val state: String = "unknown",
    val version: Int? = null,
    val devices: Int? = null,
)

@Serializable
data class ServerInfo(
    val host: String = "",
    val version: Int = 0,
    val rootd: RootdInfo = RootdInfo(),
)

@Serializable
data class WhoAmIResponse(
    val login: String? = null,
)

@Serializable
data class VpnState(
    val enabled: Boolean = false,
    @SerialName("tunnel_up") val tunnelUp: Boolean = false,
    @SerialName("forwarded_port") val forwardedPort: Int? = null,
    @SerialName("qbt_interface") val qbtInterface: String = "",
    @SerialName("public_ip") val publicIp: String? = null,
    @SerialName("last_error") val lastError: String? = null,
)

@Serializable
data class VpnToggleRequest(
    val enabled: Boolean,
)

@Serializable
data class KbdLockState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val active: String = "",
)

@Serializable
data class KbdLockToggleRequest(
    val enabled: Boolean,
)

@Serializable
data class KeyringStatus(
    val locked: Boolean = false,
    val rdp: String = "",
)

@Serializable
data class KeyringUnlockRequest(
    val password: String,
)

@Serializable
data class KeyringUnlockResponse(
    val ok: Boolean = false,
    val locked: Boolean = false,
    val rdp: String = "",
    val error: String? = null,
)

@Serializable
data class NotifCategory(
    val id: String,
    val label: String,
    val description: String = "",
    val icon: String = "",
    val telegram: Boolean = false,
    val app: Boolean = false,
    val channels: List<String>? = null,
)

@Serializable
data class NotifChannelStatus(
    val configured: Boolean = false,
    val devices: Int = 0,
)

@Serializable
data class NotifChannels(
    val telegram: NotifChannelStatus = NotifChannelStatus(),
    val app: NotifChannelStatus = NotifChannelStatus(),
)

@Serializable
data class NotifSettings(
    val masters: Map<String, Boolean> = emptyMap(),
    val categories: List<NotifCategory> = emptyList(),
    val channels: NotifChannels = NotifChannels(),
)

@Serializable
data class NotifPatchRequest(
    val masters: Map<String, Boolean>? = null,
    val categories: Map<String, Map<String, Boolean>>? = null,
)

@Serializable
data class PushStatusResponse(
    val configured: Boolean = false,
    val registered: Boolean = false,
)

@Serializable
data class AgentSummaryState(
    val engine: String = "claude",
    @SerialName("claude_active") val claudeActive: Boolean = false,
    @SerialName("gemini_active") val geminiActive: Boolean = false,
    val available: Boolean = false,
    val switching: Boolean = false,
)

@Serializable
data class ApkReleaseInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val sha256: String? = null,
    val size: Long? = null,
    val downloadUrl: String? = null,
    val releaseNotes: String? = null,
)

data class CrashReportEntry(
    val id: String,
    val timestamp: String,
    val summary: String,
    val stackTrace: String,
)

data class FcmDiagnostics(
    val notificationsEnabled: Boolean = false,
    val token: String? = null,
    val error: String? = null,
)

sealed interface VaultResult {
    data class Success(val plaintext: String = "") : VaultResult
    data object Cancelled : VaultResult
    data class Error(val message: String, val invalidated: Boolean = false) : VaultResult
}

/**
 * Platform bridge for biometric keyring vault, FCM diagnostics, crash logs, and APK installer.
 */
interface SettingsPlatformBridge {
    fun currentVersionName(): String
    fun currentVersionCode(): Int
    fun keyringVaultSupported(): Boolean
    fun keyringHasSaved(): Boolean
    suspend fun keyringSave(password: String): VaultResult
    suspend fun keyringDecrypt(): VaultResult
    fun keyringForget()
    fun fcmDiagnostics(): FcmDiagnostics
    suspend fun registerFcmToken(): Boolean
    fun openNotificationSettings()
    fun loadCrashReports(): List<CrashReportEntry>
    fun clearCrashReports()
    fun downloadAndInstallApk(apkUrl: String)
}

/**
 * Concise host label for the main Settings Connection row (e.g. "hp-pavilion").
 */
fun connectionHostSummary(serverInfo: ServerInfo?, savedServerUrl: String): String {
    val reportedHost = serverInfo?.host?.trim()
    if (!reportedHost.isNullOrEmpty()) return reportedHost
    val trimmedUrl = savedServerUrl.trim()
    if (trimmedUrl.isEmpty()) return "Not set"
    val parsedHost = runCatching { URI(trimmedUrl).host }.getOrNull()?.trim()
    if (!parsedHost.isNullOrEmpty()) {
        return if (parsedHost.endsWith(".ts.net", ignoreCase = true)) {
            parsedHost.substringBefore('.')
        } else {
            parsedHost
        }
    }
    return trimmedUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')
        .ifBlank { "Not set" }
}

/**
 * Single deduplicated status line for the Keyring row.
 */
fun keyringStatusSummary(status: KeyringStatus?, hasSaved: Boolean): String = when {
    status == null -> "Checking…"
    status.locked && hasSaved -> "Locked · fingerprint saved"
    status.locked -> if (status.rdp.isNotBlank()) "Locked · RDP ${status.rdp}" else "Locked · RDP unavailable"
    else -> buildList {
        add("Unlocked")
        if (status.rdp.isNotBlank()) add("RDP ${status.rdp}")
        if (hasSaved) add("saved on phone")
    }.joinToString(" · ")
}

/**
 * Single concise summary line for the Notification routing header.
 */
fun notifRoutingSummary(settings: NotifSettings?): String {
    if (settings == null) return "Loading…"
    val tgOn = settings.masters["telegram"] == true
    val appOn = settings.masters["app"] == true
    val activeChannels = buildList {
        if (tgOn) add("Telegram")
        if (appOn) add("App")
    }
    if (activeChannels.isEmpty()) return "Muted"
    val enabledCategories = settings.categories.count { cat ->
        (tgOn && cat.telegram && (cat.channels == null || "telegram" in cat.channels)) ||
            (appOn && cat.app)
    }
    return if (settings.categories.isNotEmpty()) {
        "${activeChannels.joinToString(" · ")} · $enabledCategories/${settings.categories.size} categories"
    } else {
        activeChannels.joinToString(" · ")
    }
}
