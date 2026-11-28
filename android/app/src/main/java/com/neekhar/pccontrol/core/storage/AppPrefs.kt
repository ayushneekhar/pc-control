package com.neekhar.pccontrol.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.neekhar.pccontrol.core.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.pcControlDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pc_control_prefs",
)

data class AppPrefsSnapshot(
    val serverUrl: String = AppPrefs.DEFAULT_SERVER_URL,
    val apiUrl: String = AppPrefs.DEFAULT_API_URL,
    val apiToken: String = "",
    val cfAccessClientId: String = "",
    val cfAccessClientSecret: String = "",
    val chatUrl: String = "",
    val chatToken: String = "",
    val themeMode: ThemeMode = ThemeMode.System,
    val notificationsEnabled: Boolean = true,
    val rootNotificationsEnabled: Boolean = true,
    val chatNotificationsEnabled: Boolean = true,
    val downloadNotificationsEnabled: Boolean = true,
    val usageAlertsNotificationsEnabled: Boolean = true,
    val lastSelectedCam: String = AppPrefs.DEFAULT_CAM,
    val deviceFingerprint: String? = null,
    val pushToken: String? = null,
    val pushError: String? = null,
    val liveDismissedIds: Set<String> = emptySet(),
    val keyringCiphertext: String? = null,
    val keyringIv: String? = null,
) {
    /** True when the unified off-Tailscale Cloudflare Public API has both URL and Bearer token set. */
    val isPublicApiConfigured: Boolean
        get() = apiUrl.isNotBlank() && apiToken.isNotBlank()
}

/**
 * Persistent application preferences backed by Jetpack DataStore Preferences.
 * Exposes both reactive [Flow]s for Compose and a live [current] snapshot for network/service layers.
 */
class AppPrefs internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(context: Context) : this(context.applicationContext.pcControlDataStore)

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_URL = stringPreferencesKey("api_url")
        val API_TOKEN = stringPreferencesKey("api_token")
        val CF_ACCESS_CLIENT_ID = stringPreferencesKey("cf_access_client_id")
        val CF_ACCESS_CLIENT_SECRET = stringPreferencesKey("cf_access_client_secret")
        val CHAT_URL = stringPreferencesKey("chat_url")
        val CHAT_TOKEN = stringPreferencesKey("chat_token")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val ROOT_NOTIFICATIONS = booleanPreferencesKey("root_notifications_enabled")
        val CHAT_NOTIFICATIONS = booleanPreferencesKey("chat_notifications_enabled")
        val DOWNLOAD_NOTIFICATIONS = booleanPreferencesKey("download_notifications_enabled")
        val USAGE_ALERTS_NOTIFICATIONS = booleanPreferencesKey("usage_alerts_notifications_enabled")
        val LAST_SELECTED_CAM = stringPreferencesKey("last_selected_cam")
        val DEVICE_FINGERPRINT = stringPreferencesKey("device_fingerprint")
        val PUSH_TOKEN = stringPreferencesKey("push_token")
        val PUSH_ERROR = stringPreferencesKey("push_error")
        val LIVE_DISMISSED_IDS = stringSetPreferencesKey("live_dismissed_ids")
        val KEYRING_CIPHERTEXT = stringPreferencesKey("keyring_ciphertext")
        val KEYRING_IV = stringPreferencesKey("keyring_iv")
    }

    private val _current = MutableStateFlow(AppPrefsSnapshot())
    private val loaded = CompletableDeferred<AppPrefsSnapshot>()

    /** In-memory state synced with DataStore for fast synchronous reads. */
    val current: StateFlow<AppPrefsSnapshot> = _current.asStateFlow()

    val snapshots: Flow<AppPrefsSnapshot> = dataStore.data
        .catch { err ->
            if (err is IOException) emit(emptyPreferences()) else throw err
        }
        .map { prefs -> prefs.toSnapshot() }
        .distinctUntilChanged()

    val serverUrl: Flow<String> = snapshots.map { it.serverUrl }.distinctUntilChanged()
    val apiUrl: Flow<String> = snapshots.map { it.apiUrl }.distinctUntilChanged()
    val apiToken: Flow<String> = snapshots.map { it.apiToken }.distinctUntilChanged()
    val cfAccessClientId: Flow<String> = snapshots.map { it.cfAccessClientId }.distinctUntilChanged()
    val cfAccessClientSecret: Flow<String> = snapshots.map { it.cfAccessClientSecret }.distinctUntilChanged()
    val chatUrl: Flow<String> = snapshots.map { it.chatUrl }.distinctUntilChanged()
    val chatToken: Flow<String> = snapshots.map { it.chatToken }.distinctUntilChanged()
    val themeMode: Flow<ThemeMode> = snapshots.map { it.themeMode }.distinctUntilChanged()
    val notificationsEnabled: Flow<Boolean> = snapshots.map { it.notificationsEnabled }.distinctUntilChanged()
    val rootNotificationsEnabled: Flow<Boolean> = snapshots.map { it.rootNotificationsEnabled }.distinctUntilChanged()
    val chatNotificationsEnabled: Flow<Boolean> = snapshots.map { it.chatNotificationsEnabled }.distinctUntilChanged()
    val downloadNotificationsEnabled: Flow<Boolean> = snapshots.map { it.downloadNotificationsEnabled }.distinctUntilChanged()
    val usageAlertsNotificationsEnabled: Flow<Boolean> = snapshots.map { it.usageAlertsNotificationsEnabled }.distinctUntilChanged()
    val lastSelectedCam: Flow<String> = snapshots.map { it.lastSelectedCam }.distinctUntilChanged()
    val deviceFingerprint: Flow<String?> = snapshots.map { it.deviceFingerprint }.distinctUntilChanged()
    val pushToken: Flow<String?> = snapshots.map { it.pushToken }.distinctUntilChanged()
    val pushError: Flow<String?> = snapshots.map { it.pushError }.distinctUntilChanged()
    val liveDismissedIds: Flow<Set<String>> = snapshots.map { it.liveDismissedIds }.distinctUntilChanged()

    init {
        scope.launch {
            snapshots.collect { snap ->
                _current.value = snap
                if (!loaded.isCompleted) {
                    loaded.complete(snap)
                }
            }
        }
    }

    /**
     * Suspends until the first DataStore emission has loaded persisted preferences from disk,
     * then returns the latest in-memory snapshot.
     */
    suspend fun awaitLoaded(): AppPrefsSnapshot {
        loaded.await()
        return _current.value
    }

    /** Suspends until DataStore has read persisted preferences from disk. */
    suspend fun snapshot(): AppPrefsSnapshot {
        val snap = snapshots.first()
        _current.value = snap
        if (!loaded.isCompleted) {
            loaded.complete(snap)
        }
        return snap
    }

    suspend fun setServerUrl(rawUrl: String) {
        val normalized = normalizeServerUrl(rawUrl)
        _current.value = _current.value.copy(serverUrl = normalized)
        dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = normalized
        }
    }

    suspend fun setPublicApiConfig(
        rawUrl: String,
        rawApiToken: String,
        rawCfClientId: String,
        rawCfClientSecret: String,
    ) {
        val cleanUrl = normalizeApiUrl(rawUrl)
        val cleanToken = rawApiToken.trim()
        val cleanCfId = rawCfClientId.trim()
        val cleanCfSecret = rawCfClientSecret.trim()
        _current.value = _current.value.copy(
            apiUrl = cleanUrl,
            apiToken = cleanToken,
            cfAccessClientId = cleanCfId,
            cfAccessClientSecret = cleanCfSecret,
        )
        dataStore.edit { prefs ->
            prefs[Keys.API_URL] = cleanUrl
            if (cleanToken.isEmpty()) prefs.remove(Keys.API_TOKEN) else prefs[Keys.API_TOKEN] = cleanToken
            if (cleanCfId.isEmpty()) prefs.remove(Keys.CF_ACCESS_CLIENT_ID) else prefs[Keys.CF_ACCESS_CLIENT_ID] = cleanCfId
            if (cleanCfSecret.isEmpty()) prefs.remove(Keys.CF_ACCESS_CLIENT_SECRET) else prefs[Keys.CF_ACCESS_CLIENT_SECRET] = cleanCfSecret
        }
    }

    @Deprecated("Replaced by unified setPublicApiConfig")
    suspend fun setChatConfig(rawUrl: String, rawToken: String) {
        val cleanUrl = normalizeChatUrl(rawUrl)
        val cleanToken = rawToken.trim()
        _current.value = _current.value.copy(chatUrl = cleanUrl, chatToken = cleanToken)
        dataStore.edit { prefs ->
            if (cleanUrl.isEmpty()) prefs.remove(Keys.CHAT_URL) else prefs[Keys.CHAT_URL] = cleanUrl
            if (cleanToken.isEmpty()) prefs.remove(Keys.CHAT_TOKEN) else prefs[Keys.CHAT_TOKEN] = cleanToken
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        _current.value = _current.value.copy(themeMode = mode)
        dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.storageValue
        }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        _current.value = _current.value.copy(notificationsEnabled = enabled)
        dataStore.edit { prefs ->
            prefs[Keys.NOTIFICATIONS_ENABLED] = enabled
        }
    }

    suspend fun setNotificationChannels(
        root: Boolean? = null,
        chat: Boolean? = null,
        downloads: Boolean? = null,
        usageAlerts: Boolean? = null,
    ) {
        _current.value = _current.value.copy(
            rootNotificationsEnabled = root ?: _current.value.rootNotificationsEnabled,
            chatNotificationsEnabled = chat ?: _current.value.chatNotificationsEnabled,
            downloadNotificationsEnabled = downloads ?: _current.value.downloadNotificationsEnabled,
            usageAlertsNotificationsEnabled = usageAlerts ?: _current.value.usageAlertsNotificationsEnabled,
        )
        dataStore.edit { prefs ->
            if (root != null) prefs[Keys.ROOT_NOTIFICATIONS] = root
            if (chat != null) prefs[Keys.CHAT_NOTIFICATIONS] = chat
            if (downloads != null) prefs[Keys.DOWNLOAD_NOTIFICATIONS] = downloads
            if (usageAlerts != null) prefs[Keys.USAGE_ALERTS_NOTIFICATIONS] = usageAlerts
        }
    }

    suspend fun setLastSelectedCam(cam: String) {
        val clean = cam.trim().ifEmpty { DEFAULT_CAM }
        _current.value = _current.value.copy(lastSelectedCam = clean)
        dataStore.edit { prefs ->
            prefs[Keys.LAST_SELECTED_CAM] = clean
        }
    }

    suspend fun setDeviceFingerprint(fingerprint: String?) {
        val clean = fingerprint?.trim()?.takeIf { it.isNotEmpty() }
        _current.value = _current.value.copy(deviceFingerprint = clean)
        dataStore.edit { prefs ->
            if (clean == null) prefs.remove(Keys.DEVICE_FINGERPRINT) else prefs[Keys.DEVICE_FINGERPRINT] = clean
        }
    }

    suspend fun setPushStatus(token: String?, error: String?) {
        _current.value = _current.value.copy(pushToken = token, pushError = error)
        dataStore.edit { prefs ->
            if (token == null) prefs.remove(Keys.PUSH_TOKEN) else prefs[Keys.PUSH_TOKEN] = token
            if (error == null) prefs.remove(Keys.PUSH_ERROR) else prefs[Keys.PUSH_ERROR] = error
        }
    }

    suspend fun setLiveDismissedIds(ids: Set<String>) {
        _current.value = _current.value.copy(liveDismissedIds = ids)
        dataStore.edit { prefs ->
            prefs[Keys.LIVE_DISMISSED_IDS] = ids
        }
    }

    suspend fun setKeyringBlob(ciphertext: String, iv: String) {
        _current.value = _current.value.copy(keyringCiphertext = ciphertext, keyringIv = iv)
        dataStore.edit { prefs ->
            prefs[Keys.KEYRING_CIPHERTEXT] = ciphertext
            prefs[Keys.KEYRING_IV] = iv
        }
    }

    suspend fun clearKeyringBlob() {
        _current.value = _current.value.copy(keyringCiphertext = null, keyringIv = null)
        dataStore.edit { prefs ->
            prefs.remove(Keys.KEYRING_CIPHERTEXT)
            prefs.remove(Keys.KEYRING_IV)
        }
    }

    private fun Preferences.toSnapshot(): AppPrefsSnapshot = AppPrefsSnapshot(
        serverUrl = normalizeServerUrl(this[Keys.SERVER_URL] ?: DEFAULT_SERVER_URL),
        apiUrl = normalizeApiUrl(this[Keys.API_URL] ?: DEFAULT_API_URL),
        apiToken = (this[Keys.API_TOKEN] ?: "").trim(),
        cfAccessClientId = (this[Keys.CF_ACCESS_CLIENT_ID] ?: "").trim(),
        cfAccessClientSecret = (this[Keys.CF_ACCESS_CLIENT_SECRET] ?: "").trim(),
        chatUrl = normalizeChatUrl(this[Keys.CHAT_URL] ?: ""),
        chatToken = (this[Keys.CHAT_TOKEN] ?: "").trim(),
        themeMode = ThemeMode.fromStorage(this[Keys.THEME_MODE]),
        notificationsEnabled = this[Keys.NOTIFICATIONS_ENABLED] ?: true,
        rootNotificationsEnabled = this[Keys.ROOT_NOTIFICATIONS] ?: true,
        chatNotificationsEnabled = this[Keys.CHAT_NOTIFICATIONS] ?: true,
        downloadNotificationsEnabled = this[Keys.DOWNLOAD_NOTIFICATIONS] ?: true,
        usageAlertsNotificationsEnabled = this[Keys.USAGE_ALERTS_NOTIFICATIONS] ?: true,
        lastSelectedCam = this[Keys.LAST_SELECTED_CAM]?.takeIf { it.isNotBlank() } ?: DEFAULT_CAM,
        deviceFingerprint = this[Keys.DEVICE_FINGERPRINT],
        pushToken = this[Keys.PUSH_TOKEN],
        pushError = this[Keys.PUSH_ERROR],
        liveDismissedIds = this[Keys.LIVE_DISMISSED_IDS] ?: emptySet(),
        keyringCiphertext = this[Keys.KEYRING_CIPHERTEXT],
        keyringIv = this[Keys.KEYRING_IV],
    )

    companion object {
        const val DEFAULT_SERVER_URL = "https://ayushlive-hp-pavilion.tail65e4a4.ts.net"
        const val DEFAULT_API_URL = "https://api.neekhar.dev"
        const val DEFAULT_CAM = "cat"

        private val PC_API_SUFFIX_REGEX = Regex("/pc/api(?:/.*)?$", RegexOption.IGNORE_CASE)
        private val CHAT_SUFFIX_REGEX = Regex("/pc/api/chat.*$", RegexOption.IGNORE_CASE)

        fun normalizeServerUrl(raw: String): String {
            val cleaned = raw.trim().trimEnd('/')
            return cleaned.ifEmpty { DEFAULT_SERVER_URL }
        }

        /**
         * Strips trailing slashes and any pasted `/pc/api...` suffix so the stored public API URL is
         * always the bare origin (defaulting to [DEFAULT_API_URL]).
         */
        fun normalizeApiUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return DEFAULT_API_URL
            return PC_API_SUFFIX_REGEX.replace(trimmed, "").trimEnd('/').ifEmpty { DEFAULT_API_URL }
        }

        /**
         * Strips trailing slashes and any pasted `/pc/api/chat...` suffix so legacy stored values
         * remain bare origins.
         */
        fun normalizeChatUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return CHAT_SUFFIX_REGEX.replace(trimmed, "").trimEnd('/')
        }
    }
}
