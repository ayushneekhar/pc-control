package com.neekhar.pccontrol.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.platform.ApkUpdateState
import com.neekhar.pccontrol.core.storage.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelFcmTest {

    private class FakePreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private class FakeSettingsPlatformBridge(
        @Volatile var diag: FcmDiagnostics,
        private val onRegister: suspend () -> Boolean,
    ) : SettingsPlatformBridge {
        val registerCalls = AtomicInteger(0)
        override val apkUpdateState: StateFlow<ApkUpdateState> = MutableStateFlow(ApkUpdateState.Idle)
        override fun currentVersionName(): String = "2.1.2"
        override fun currentVersionCode(): Int = 29
        override fun keyringVaultSupported(): Boolean = false
        override fun keyringHasSaved(): Boolean = false
        override suspend fun keyringSave(password: String): VaultResult = VaultResult.Cancelled
        override suspend fun keyringDecrypt(): VaultResult = VaultResult.Cancelled
        override fun keyringForget() = Unit
        override fun fcmDiagnostics(): FcmDiagnostics = diag
        override suspend fun registerFcmToken(): Boolean {
            registerCalls.incrementAndGet()
            return onRegister()
        }
        override fun openNotificationSettings() = Unit
        override fun loadCrashReports(): List<CrashReportEntry> = emptyList()
        override fun clearCrashReports() = Unit
        override fun downloadAndInstallApk(apkUrl: String) = Unit
    }

    private fun jsonResponse(chain: Interceptor.Chain, body: String, code: Int = 200): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun autoRegistersOnceWhenTokenMissingAndVerifiesStatus() = runBlocking {
        val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prefs = AppPrefs(
                dataStore = FakePreferencesDataStore(
                    initial = preferencesOf(stringPreferencesKey("server_url") to "https://host.ts.net"),
                ),
                scope = prefsScope,
            )
            val statusCalls = AtomicInteger(0)
            val okHttp = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val path = chain.request().url.encodedPath
                    when {
                        path.startsWith("/pc/api/push/status") -> {
                            statusCalls.incrementAndGet()
                            val tok = chain.request().url.queryParameter("token")
                            jsonResponse(chain, """{"configured":true,"registered":${tok == "fcm-recovered"}}""")
                        }
                        else -> jsonResponse(chain, "{}")
                    }
                }
                .build()
            val api = PcApiClient(prefs = prefs, okHttpClient = okHttp, getRetryBackoffMs = 0L)

            lateinit var platform: FakeSettingsPlatformBridge
            platform = FakeSettingsPlatformBridge(
                diag = FcmDiagnostics(notificationsEnabled = true, token = null, error = "cold start"),
                onRegister = {
                    platform.diag = FcmDiagnostics(notificationsEnabled = true, token = "fcm-recovered", error = null)
                    prefs.setPushStatus("fcm-recovered", null)
                    true
                },
            )

            val vm = SettingsViewModel(api = api, prefs = prefs, platform = platform, autoLoad = true)

            val settled = withTimeout(5_000L) {
                vm.uiState.first { it.fcmServerRegistered == true && !it.fcmChecking }
            }
            assertEquals("fcm-recovered", settled.fcmDiagnostics.token)
            assertEquals(1, platform.registerCalls.get())
            assertEquals(1, statusCalls.get())
        } finally {
            prefsScope.cancel()
        }
    }

    @Test
    fun autoRegistersOnceWhenServerReportsUnregistered() = runBlocking {
        val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prefs = AppPrefs(
                dataStore = FakePreferencesDataStore(
                    initial = preferencesOf(
                        stringPreferencesKey("server_url") to "https://host.ts.net",
                        stringPreferencesKey("push_token") to "fcm-stale",
                    ),
                ),
                scope = prefsScope,
            )
            val statusCalls = AtomicInteger(0)
            val okHttp = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val path = chain.request().url.encodedPath
                    when {
                        path.startsWith("/pc/api/push/status") -> {
                            val callNum = statusCalls.incrementAndGet()
                            val registered = callNum >= 2
                            jsonResponse(chain, """{"configured":true,"registered":$registered}""")
                        }
                        else -> jsonResponse(chain, "{}")
                    }
                }
                .build()
            val api = PcApiClient(prefs = prefs, okHttpClient = okHttp, getRetryBackoffMs = 0L)

            lateinit var platform: FakeSettingsPlatformBridge
            platform = FakeSettingsPlatformBridge(
                diag = FcmDiagnostics(notificationsEnabled = true, token = "fcm-stale", error = null),
                onRegister = {
                    platform.diag = FcmDiagnostics(notificationsEnabled = true, token = "fcm-fresh", error = null)
                    prefs.setPushStatus("fcm-fresh", null)
                    true
                },
            )

            val vm = SettingsViewModel(api = api, prefs = prefs, platform = platform, autoLoad = true)

            val settled = withTimeout(5_000L) {
                vm.uiState.first { it.fcmServerRegistered == true && !it.fcmChecking }
            }
            assertEquals("fcm-fresh", settled.fcmDiagnostics.token)
            assertEquals(1, platform.registerCalls.get())
            assertEquals(2, statusCalls.get())
        } finally {
            prefsScope.cancel()
        }
    }

    @Test
    fun updatesFcmDiagnosticsAutomaticallyWhenBackgroundRegistrationCompletes() = runBlocking {
        val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prefs = AppPrefs(
                dataStore = FakePreferencesDataStore(
                    initial = preferencesOf(stringPreferencesKey("server_url") to "https://host.ts.net"),
                ),
                scope = prefsScope,
            )
            val okHttp = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val path = chain.request().url.encodedPath
                    when {
                        path.startsWith("/pc/api/push/status") -> {
                            val tok = chain.request().url.queryParameter("token")
                            jsonResponse(chain, """{"configured":true,"registered":${tok == "fcm-bg"}}""")
                        }
                        else -> jsonResponse(chain, "{}")
                    }
                }
                .build()
            val api = PcApiClient(prefs = prefs, okHttpClient = okHttp, getRetryBackoffMs = 0L)

            val platform = FakeSettingsPlatformBridge(
                diag = FcmDiagnostics(notificationsEnabled = true, token = null, error = null),
                onRegister = { true },
            )

            val vm = SettingsViewModel(api = api, prefs = prefs, platform = platform, autoLoad = false)
            // Wait for initial AppPrefs load before simulating background registration finishing
            withTimeout(5_000L) { prefs.awaitLoaded() }

            platform.diag = FcmDiagnostics(notificationsEnabled = true, token = "fcm-bg", error = null)
            prefs.setPushStatus("fcm-bg", null)

            val updated = withTimeout(5_000L) {
                vm.uiState.first { it.fcmServerRegistered == true }
            }
            assertEquals("fcm-bg", updated.fcmDiagnostics.token)
            assertTrue(updated.fcmServerRegistered == true)
            assertEquals(0, platform.registerCalls.get())
        } finally {
            prefsScope.cancel()
        }
    }
}
