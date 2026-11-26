package com.neekhar.pccontrol.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

data class SettingsUiState(
    // Agent summary
    val agentSummary: AgentSummaryState? = null,
    // Connection
    val serverUrlInput: String = "",
    val chatBaseUrlInput: String = "",
    val chatTokenInput: String = "",
    val savedServerUrl: String = "",
    val savedChatBaseUrl: String = "",
    val savedChatToken: String = "",
    val serverInfo: ServerInfo? = null,
    val whoAmI: String? = null,
    val connectionTesting: Boolean = false,
    val connectionError: String? = null,
    // PC Controls: VPN
    val vpnState: VpnState? = null,
    val vpnPending: Boolean? = null,
    val vpnBusy: Boolean = false,
    val vpnError: String? = null,
    // PC Controls: Keyboard lock
    val kbdLockState: KbdLockState? = null,
    val kbdLockPending: Boolean? = null,
    val kbdLockBusy: Boolean = false,
    val kbdLockError: String? = null,
    // PC Controls: Keyring
    val keyringStatus: KeyringStatus? = null,
    val keyringVaultSupported: Boolean = false,
    val keyringHasSaved: Boolean = false,
    val keyringBusy: Boolean = false,
    val keyringError: String? = null,
    val keyringNotice: String? = null,
    val showKeyringSheet: Boolean = false,
    val keyringSheetMode: KeyringSheetMode = KeyringSheetMode.UNLOCK,
    // Notifications
    val notifSettings: NotifSettings? = null,
    val notifExpanded: Boolean = false,
    val notifError: String? = null,
    // Appearance
    val themeMode: ThemeMode = ThemeMode.DARK,
    // App Updates (Native GitHub Releases APK)
    val currentVersionName: String = "",
    val currentVersionCode: Int = 0,
    val latestRelease: ApkReleaseInfo? = null,
    val updateChecking: Boolean = false,
    val updateError: String? = null,
    // Diagnostics
    val fcmDiagnostics: FcmDiagnostics = FcmDiagnostics(),
    val fcmServerRegistered: Boolean? = null,
    val crashReports: List<CrashReportEntry> = emptyList(),
    val showCrashViewer: Boolean = false,
    val isRefreshing: Boolean = false,
) {
    val hasNewerApk: Boolean
        get() = latestRelease != null && latestRelease.versionCode > currentVersionCode

    val connectionDirty: Boolean
        get() = serverUrlInput.trim().trimEnd('/') != savedServerUrl ||
            chatBaseUrlInput.trim().trimEnd('/') != savedChatBaseUrl ||
            chatTokenInput.trim() != savedChatToken
}

enum class KeyringSheetMode {
    UNLOCK,
    SAVE_PASSWORD,
}

class SettingsViewModel(
    private val api: PcApiClient,
    private val prefs: AppPrefs,
    private val platform: SettingsPlatformBridge,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            serverUrlInput = api.baseUrl,
            chatBaseUrlInput = api.chatBaseUrl,
            chatTokenInput = api.chatToken,
            savedServerUrl = api.baseUrl,
            savedChatBaseUrl = api.chatBaseUrl,
            savedChatToken = api.chatToken,
            keyringVaultSupported = platform.keyringVaultSupported(),
            keyringHasSaved = platform.keyringHasSaved(),
            themeMode = ThemeMode.fromId(prefs.themeMode),
            currentVersionName = platform.currentVersionName(),
            currentVersionCode = platform.currentVersionCode(),
            fcmDiagnostics = platform.fcmDiagnostics(),
            crashReports = platform.loadCrashReports(),
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            launch { testConnection() }
            launch { loadAgentSummary() }
            launch { loadVpn() }
            launch { loadKbdLock() }
            launch { loadKeyring() }
            launch { loadNotifSettings() }
            launch { loadPushDiagnostics() }
            launch { checkForApkUpdate() }
            _uiState.update {
                it.copy(
                    crashReports = platform.loadCrashReports(),
                    keyringHasSaved = platform.keyringHasSaved(),
                    isRefreshing = false,
                )
            }
        }
    }

    // ---- Connection ----

    fun onServerUrlChange(v: String) {
        _uiState.update { it.copy(serverUrlInput = v) }
    }

    fun onChatBaseUrlChange(v: String) {
        _uiState.update { it.copy(chatBaseUrlInput = v) }
    }

    fun onChatTokenChange(v: String) {
        _uiState.update { it.copy(chatTokenInput = v) }
    }

    fun saveConnection() {
        val cleanServer = _uiState.value.serverUrlInput.trim().trimEnd('/')
        val cleanChat = _uiState.value.chatBaseUrlInput.trim().trimEnd('/')
        val cleanToken = _uiState.value.chatTokenInput.trim()

        if (!cleanServer.startsWith("https://") && !cleanServer.startsWith("http://")) {
            _uiState.update { it.copy(connectionError = "Server URL must start with https:// or http://") }
            return
        }

        prefs.serverUrl = cleanServer
        prefs.chatBaseUrl = cleanChat
        prefs.chatToken = cleanToken

        _uiState.update {
            it.copy(
                serverUrlInput = cleanServer,
                chatBaseUrlInput = cleanChat,
                chatTokenInput = cleanToken,
                savedServerUrl = cleanServer,
                savedChatBaseUrl = cleanChat,
                savedChatToken = cleanToken,
                connectionError = null,
            )
        }
        viewModelScope.launch {
            testConnection()
            platform.registerFcmToken()
            loadPushDiagnostics()
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(connectionTesting = true, connectionError = null) }
            val infoRes = api.getJson<ServerInfo>("/pc/api/info")
            val whoRes = api.getJson<WhoAmIResponse>("/pc/api/whoami")
            when (infoRes) {
                is ApiResult.Success -> {
                    val login = (whoRes as? ApiResult.Success)?.data?.login
                    _uiState.update {
                        it.copy(
                            serverInfo = infoRes.data,
                            whoAmI = login,
                            connectionTesting = false,
                            connectionError = null,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            connectionTesting = false,
                            connectionError = infoRes.message,
                        )
                    }
                }
            }
        }
    }

    // ---- Agent Summary ----

    private suspend fun loadAgentSummary() {
        when (val res = api.getJson<AgentSummaryState>("/pc/api/agent")) {
            is ApiResult.Success -> _uiState.update { it.copy(agentSummary = res.data) }
            is ApiResult.Error -> Unit
        }
    }

    // ---- VPN ----

    private suspend fun loadVpn() {
        when (val res = api.getJson<VpnState>("/pc/api/vpn")) {
            is ApiResult.Success -> _uiState.update { it.copy(vpnState = res.data, vpnError = null) }
            is ApiResult.Error -> _uiState.update { it.copy(vpnError = res.message) }
        }
    }

    fun toggleVpn(enabled: Boolean) {
        if (_uiState.value.vpnBusy) return
        val previous = _uiState.value.vpnState
        _uiState.update { it.copy(vpnPending = enabled, vpnBusy = true, vpnError = null) }
        viewModelScope.launch {
            when (val res = api.postJson<VpnToggleRequest, VpnState>("/pc/api/vpn", VpnToggleRequest(enabled))) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(vpnState = res.data, vpnPending = null, vpnBusy = false, vpnError = null)
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(vpnState = previous, vpnPending = null, vpnBusy = false, vpnError = res.message)
                    }
                }
            }
        }
    }

    // ---- Keyboard Lock ----

    private suspend fun loadKbdLock() {
        when (val res = api.getJson<KbdLockState>("/pc/api/kbd-lock")) {
            is ApiResult.Success -> _uiState.update { it.copy(kbdLockState = res.data, kbdLockError = null) }
            is ApiResult.Error -> _uiState.update { it.copy(kbdLockError = res.message) }
        }
    }

    fun toggleKbdLock(enabled: Boolean) {
        if (_uiState.value.kbdLockBusy) return
        val previous = _uiState.value.kbdLockState
        _uiState.update { it.copy(kbdLockPending = enabled, kbdLockBusy = true, kbdLockError = null) }
        viewModelScope.launch {
            when (val res = api.postJson<KbdLockToggleRequest, KbdLockState>("/pc/api/kbd-lock", KbdLockToggleRequest(enabled))) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(kbdLockState = res.data, kbdLockPending = null, kbdLockBusy = false, kbdLockError = null)
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(kbdLockState = previous, kbdLockPending = null, kbdLockBusy = false, kbdLockError = res.message)
                    }
                }
            }
        }
    }

    // ---- Keyring ----

    private suspend fun loadKeyring() {
        val hasSaved = platform.keyringHasSaved()
        when (val res = api.getJson<KeyringStatus>("/pc/api/keyring")) {
            is ApiResult.Success -> _uiState.update {
                it.copy(keyringStatus = res.data, keyringHasSaved = hasSaved)
            }
            is ApiResult.Error -> _uiState.update { it.copy(keyringHasSaved = hasSaved) }
        }
    }

    fun openKeyringUnlockSheet() {
        _uiState.update {
            it.copy(
                showKeyringSheet = true,
                keyringSheetMode = KeyringSheetMode.UNLOCK,
                keyringError = null,
                keyringNotice = null,
            )
        }
    }

    fun openKeyringSaveSheet() {
        _uiState.update {
            it.copy(
                showKeyringSheet = true,
                keyringSheetMode = KeyringSheetMode.SAVE_PASSWORD,
                keyringError = null,
                keyringNotice = null,
            )
        }
    }

    fun closeKeyringSheet() {
        if (_uiState.value.keyringBusy) return
        _uiState.update { it.copy(showKeyringSheet = false) }
    }

    fun clearKeyringFeedback() {
        _uiState.update { it.copy(keyringError = null, keyringNotice = null) }
    }

    fun unlockKeyringWithBiometricsOrPrompt() {
        if (_uiState.value.keyringBusy) return
        if (!platform.keyringHasSaved()) {
            openKeyringUnlockSheet()
            return
        }
        _uiState.update { it.copy(keyringBusy = true, keyringError = null, keyringNotice = null) }
        viewModelScope.launch {
            when (val vault = platform.keyringDecrypt()) {
                is VaultResult.Cancelled -> {
                    _uiState.update { it.copy(keyringBusy = false, keyringHasSaved = platform.keyringHasSaved()) }
                }
                is VaultResult.Error -> {
                    _uiState.update {
                        it.copy(
                            keyringBusy = false,
                            keyringHasSaved = platform.keyringHasSaved(),
                            keyringError = vault.message,
                            showKeyringSheet = vault.invalidated,
                            keyringSheetMode = KeyringSheetMode.UNLOCK,
                        )
                    }
                }
                is VaultResult.Success -> {
                    submitUnlockPassword(vault.plaintext, saveAfter = false)
                }
            }
        }
    }

    fun submitKeyringManualUnlock(password: String, saveOnPhone: Boolean) {
        if (_uiState.value.keyringBusy) return
        if (password.isEmpty()) {
            _uiState.update { it.copy(keyringError = "Enter your keyring password.") }
            return
        }
        _uiState.update { it.copy(keyringBusy = true, keyringError = null, keyringNotice = null) }
        viewModelScope.launch {
            submitUnlockPassword(password, saveAfter = saveOnPhone)
        }
    }

    fun saveKeyringPassword(password: String) {
        if (_uiState.value.keyringBusy) return
        if (password.isEmpty()) {
            _uiState.update { it.copy(keyringError = "Enter your keyring password.") }
            return
        }
        _uiState.update { it.copy(keyringBusy = true, keyringError = null, keyringNotice = null) }
        viewModelScope.launch {
            when (val res = platform.keyringSave(password)) {
                is VaultResult.Success -> {
                    _uiState.update {
                        it.copy(
                            keyringBusy = false,
                            keyringHasSaved = platform.keyringHasSaved(),
                            keyringNotice = "Keyring password saved on this phone.",
                            keyringError = null,
                            showKeyringSheet = false,
                        )
                    }
                }
                is VaultResult.Cancelled -> {
                    _uiState.update { it.copy(keyringBusy = false) }
                }
                is VaultResult.Error -> {
                    _uiState.update { it.copy(keyringBusy = false, keyringError = res.message) }
                }
            }
        }
    }

    fun forgetKeyringPassword() {
        platform.keyringForget()
        _uiState.update {
            it.copy(
                keyringHasSaved = platform.keyringHasSaved(),
                keyringError = null,
                keyringNotice = "Saved keyring password removed.",
            )
        }
    }

    private suspend fun submitUnlockPassword(password: String, saveAfter: Boolean) {
        when (val res = api.postJson<KeyringUnlockRequest, KeyringUnlockResponse>(
            "/pc/api/keyring/unlock",
            KeyringUnlockRequest(password),
        )) {
            is ApiResult.Success -> {
                val status = KeyringStatus(locked = res.data.locked, rdp = res.data.rdp)
                var notice = if (status.locked) "Keyring is still locked." else "Keyring unlocked · RDP ${status.rdp}"
                if (saveAfter && !status.locked && platform.keyringVaultSupported()) {
                    when (val saved = platform.keyringSave(password)) {
                        is VaultResult.Success -> notice = "$notice · saved on phone"
                        is VaultResult.Error -> notice = "$notice (not saved: ${saved.message})"
                        is VaultResult.Cancelled -> Unit
                    }
                }
                _uiState.update {
                    it.copy(
                        keyringStatus = status,
                        keyringHasSaved = platform.keyringHasSaved(),
                        keyringBusy = false,
                        keyringError = null,
                        keyringNotice = notice,
                        showKeyringSheet = false,
                    )
                }
            }
            is ApiResult.Error -> {
                val msg = when (res.statusCode) {
                    401 -> "Wrong keyring password."
                    429 -> res.message.ifBlank { "Too many failed unlock attempts. Try again later." }
                    else -> res.message
                }
                _uiState.update { it.copy(keyringBusy = false, keyringError = msg) }
            }
        }
    }

    // ---- Notifications ----

    private suspend fun loadNotifSettings() {
        when (val res = api.getJson<NotifSettings>("/pc/api/notify/settings")) {
            is ApiResult.Success -> _uiState.update { it.copy(notifSettings = res.data, notifError = null) }
            is ApiResult.Error -> _uiState.update { it.copy(notifError = res.message) }
        }
    }

    fun toggleNotifExpanded() {
        _uiState.update { it.copy(notifExpanded = !it.notifExpanded) }
    }

    fun setNotifMaster(channel: String, enabled: Boolean) {
        val current = _uiState.value.notifSettings ?: return
        val optimistic = current.copy(masters = current.masters + (channel to enabled))
        applyNotifPatch(NotifPatchRequest(masters = mapOf(channel to enabled)), current, optimistic)
    }

    fun setNotifCategory(categoryId: String, channel: String, enabled: Boolean) {
        val current = _uiState.value.notifSettings ?: return
        val optimistic = current.copy(
            categories = current.categories.map { cat ->
                if (cat.id != categoryId) cat
                else if (channel == "telegram") cat.copy(telegram = enabled)
                else cat.copy(app = enabled)
            },
        )
        applyNotifPatch(
            NotifPatchRequest(categories = mapOf(categoryId to mapOf(channel to enabled))),
            current,
            optimistic,
        )
    }

    private fun applyNotifPatch(
        patch: NotifPatchRequest,
        previous: NotifSettings,
        optimistic: NotifSettings,
    ) {
        _uiState.update { it.copy(notifSettings = optimistic, notifError = null) }
        viewModelScope.launch {
            when (val res = api.postJson<NotifPatchRequest, NotifSettings>("/pc/api/notify/settings", patch)) {
                is ApiResult.Success -> _uiState.update { it.copy(notifSettings = res.data, notifError = null) }
                is ApiResult.Error -> _uiState.update {
                    it.copy(notifSettings = previous, notifError = "Not saved: ${res.message}")
                }
            }
        }
    }

    // ---- Appearance ----

    fun setThemeMode(mode: ThemeMode) {
        prefs.themeMode = mode.id
        _uiState.update { it.copy(themeMode = mode) }
    }

    // ---- App Updates (Native GitHub Releases APK) ----

    fun checkForApkUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(updateChecking = true, updateError = null) }
            val result = withContext(Dispatchers.IO) {
                fetchApkReleaseJson(GITHUB_VERSION_URL)
                    ?: fetchApkReleaseJson("${api.baseUrl}/pc/api/apk/version.json")
            }
            if (result != null) {
                _uiState.update {
                    it.copy(latestRelease = result, updateChecking = false, updateError = null)
                }
            } else {
                _uiState.update {
                    it.copy(updateChecking = false, updateError = "Could not check GitHub Releases for updates.")
                }
            }
        }
    }

    private fun fetchApkReleaseJson(url: String): ApkReleaseInfo? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            api.okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                api.json.decodeFromString<ApkReleaseInfo>(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun installLatestApk() {
        val url = _uiState.value.latestRelease?.downloadUrl?.takeIf { it.isNotBlank() } ?: GITHUB_APK_URL
        platform.downloadAndInstallApk(url)
    }

    // ---- Diagnostics ----

    private suspend fun loadPushDiagnostics() {
        val diag = platform.fcmDiagnostics()
        _uiState.update { it.copy(fcmDiagnostics = diag) }
        val token = diag.token
        if (diag.notificationsEnabled && !token.isNullOrBlank()) {
            val encoded = withContext(Dispatchers.IO) { URLEncoder.encode(token, "UTF-8") }
            when (val res = api.getJson<PushStatusResponse>("/pc/api/push/status?token=$encoded")) {
                is ApiResult.Success -> _uiState.update { it.copy(fcmServerRegistered = res.data.registered) }
                is ApiResult.Error -> _uiState.update { it.copy(fcmServerRegistered = null) }
            }
        } else {
            _uiState.update { it.copy(fcmServerRegistered = null) }
        }
    }

    fun retryPushRegistration() {
        viewModelScope.launch {
            platform.registerFcmToken()
            loadPushDiagnostics()
        }
    }

    fun openNotificationSettings() {
        platform.openNotificationSettings()
    }

    fun setCrashViewerVisible(visible: Boolean) {
        _uiState.update {
            it.copy(
                showCrashViewer = visible,
                crashReports = if (visible) platform.loadCrashReports() else it.crashReports,
            )
        }
    }

    fun clearCrashReports() {
        platform.clearCrashReports()
        _uiState.update { it.copy(crashReports = emptyList()) }
    }

    companion object {
        const val GITHUB_VERSION_URL = "https://github.com/ayushneekhar/pc-control/releases/latest/download/version.json"
        const val GITHUB_APK_URL = "https://github.com/ayushneekhar/pc-control/releases/latest/download/pc-control.apk"
    }
}
