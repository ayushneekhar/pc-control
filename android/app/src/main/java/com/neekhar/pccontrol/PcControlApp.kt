package com.neekhar.pccontrol

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.platform.ApkInstallCoordinator
import com.neekhar.pccontrol.core.platform.ApkRelease
import com.neekhar.pccontrol.core.platform.ApkUpdateState
import com.neekhar.pccontrol.core.platform.ApkUpdater
import com.neekhar.pccontrol.core.platform.BiometricSigner
import com.neekhar.pccontrol.core.platform.BiometricStatus
import com.neekhar.pccontrol.core.platform.CrashReporter
import com.neekhar.pccontrol.core.platform.InstallActionResult
import com.neekhar.pccontrol.core.platform.InstallLaunchResult
import com.neekhar.pccontrol.core.platform.KeyringSaveResult
import com.neekhar.pccontrol.core.platform.KeyringVault
import com.neekhar.pccontrol.core.platform.LiveDownloadsController
import com.neekhar.pccontrol.core.platform.PcNotifier
import com.neekhar.pccontrol.core.platform.PlatformException
import com.neekhar.pccontrol.core.platform.PlatformPrefs
import com.neekhar.pccontrol.core.platform.PushManager
import com.neekhar.pccontrol.core.platform.PushRegisterResult
import com.neekhar.pccontrol.core.platform.RootApprovalRequest
import com.neekhar.pccontrol.core.platform.RootDecision
import com.neekhar.pccontrol.core.platform.RootSignOutcome
import com.neekhar.pccontrol.core.storage.AppPrefs
import com.neekhar.pccontrol.core.storage.LocalCache
import com.neekhar.pccontrol.feature.cams.CamsRepository
import com.neekhar.pccontrol.feature.cams.WebRtcRuntime
import com.neekhar.pccontrol.feature.chat.ChatRepository
import com.neekhar.pccontrol.feature.root.Decision
import com.neekhar.pccontrol.feature.root.RootRequest
import com.neekhar.pccontrol.feature.root.RootSignerBridge
import com.neekhar.pccontrol.feature.root.SignOutcome
import com.neekhar.pccontrol.feature.seerr.SeerrRepository
import com.neekhar.pccontrol.feature.settings.CrashReportEntry
import com.neekhar.pccontrol.feature.settings.FcmDiagnostics
import com.neekhar.pccontrol.feature.settings.SettingsPlatformBridge
import com.neekhar.pccontrol.feature.settings.VaultResult
import com.neekhar.pccontrol.feature.space.SpaceRepository
import com.neekhar.pccontrol.feature.tvremote.TvRemoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application-scoped dependency container providing core storage, networking,
 * native platform services, and feature repositories.
 */
class AppContainer(
    private val appContext: Context,
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val prefs: AppPrefs = AppPrefs(appContext)
    val apiClient: PcApiClient = PcApiClient(prefs)
    val cache: LocalCache = LocalCache(appContext, apiClient.json)

    // Platform services
    val biometricSigner: BiometricSigner = BiometricSigner
    val keyringVault: KeyringVault = KeyringVault
    val pushManager: PushManager = PushManager
    val notifier: PcNotifier = PcNotifier
    val crashReporter: CrashReporter = CrashReporter
    val apkUpdater: ApkUpdater = ApkUpdater(context = appContext)

    private val apkInstallCoordinator: ApkInstallCoordinator = ApkInstallCoordinator(
        currentState = { apkUpdater.state.value },
        setError = { msg ->
            Log.w("PcControlApp", "APK update error: $msg")
            apkUpdater.reportError(msg)
        },
        resolveRelease = { apkUrl ->
            val existingRelease = when (val st = apkUpdater.state.value) {
                is ApkUpdateState.UpdateAvailable -> st.release
                is ApkUpdateState.ReadyToInstall -> st.release
                else -> null
            }
            val release = existingRelease ?: apkUpdater.checkForUpdate(prefs.current.value.serverUrl)
                .getOrNull()?.release
                ?: ApkRelease(
                    versionName = "latest",
                    versionCode = apkUpdater.installedVersion().second.toInt() + 1,
                    sha256 = "",
                    size = 0L,
                    downloadUrl = apkUrl,
                )
            val targetUrl = release.downloadUrl?.takeIf { it.isNotBlank() } ?: apkUrl
            release to targetUrl
        },
        download = { release, targetUrl ->
            apkUpdater.downloadUpdate(release, targetUrl)
        },
        launchInstall = { apkFile ->
            withContext(Dispatchers.Main) {
                when (val launch = apkUpdater.launchInstall(apkFile)) {
                    is InstallLaunchResult.Launched -> InstallActionResult.Launched
                    is InstallLaunchResult.PermissionRequired -> InstallActionResult.PermissionRequired {
                        withContext(Dispatchers.Main) {
                            appContext.startActivity(launch.settingsIntent)
                        }
                    }
                    is InstallLaunchResult.Failed -> InstallActionResult.Failed(launch.message)
                }
            }
        },
    )

    // Feature repositories
    val chatRepository: ChatRepository = ChatRepository(apiClient, cache)
    val camsRepository: CamsRepository = CamsRepository(apiClient, cache)
    val seerrRepository: SeerrRepository = SeerrRepository(apiClient, cache)
    val spaceRepository: SpaceRepository = SpaceRepository(apiClient, cache)
    val tvRemoteRepository: TvRemoteRepository = TvRemoteRepository(apiClient)

    init {
        installPlatformPrefHooks()
        syncPrefsToPlatformStorage()
    }

    private fun installPlatformPrefHooks() {
        PlatformPrefs.serverUrlProvider = { prefs.current.value.serverUrl }
        PlatformPrefs.chatBaseUrlProvider = {
            prefs.current.value.chatUrl.takeIf { it.isNotBlank() } ?: prefs.current.value.serverUrl
        }
        PlatformPrefs.chatTokenProvider = { prefs.current.value.chatToken }
        PlatformPrefs.deviceFingerprintProvider = {
            prefs.current.value.deviceFingerprint ?: biometricSigner.info()?.fingerprint
        }
    }

    private fun syncPrefsToPlatformStorage() {
        appScope.launch(Dispatchers.IO) {
            prefs.snapshots.collect { snap ->
                PlatformPrefs.setServerUrl(appContext, snap.serverUrl)
                PlatformPrefs.setChatConfig(appContext, snap.chatUrl, snap.chatToken)
                if (!snap.deviceFingerprint.isNullOrBlank()) {
                    PlatformPrefs.setDeviceFingerprint(appContext, snap.deviceFingerprint)
                }
            }
        }
    }

    fun startBackgroundTasks() {
        notifier.ensureChannels(appContext)
        appScope.launch(Dispatchers.IO) {
            runCatching { crashReporter.uploadPending(appContext, apiClient.okHttpClient) }
        }
        appScope.launch(Dispatchers.IO) {
            runCatching {
                if (pushManager.isFirebaseAvailable(appContext)) {
                    pushManager.register(appContext, apiClient.okHttpClient)
                }
            }
        }
        appScope.launch(Dispatchers.IO) {
            runCatching { LiveDownloadsController.startIfDownloading(appContext, apiClient.okHttpClient) }
        }
        WebRtcRuntime.prewarmAsync(appContext)
    }

    fun createRootSignerBridge(
        activityProvider: () -> FragmentActivity?,
    ): RootSignerBridge = object : RootSignerBridge {
        override fun deviceFingerprint(): String? {
            return PlatformPrefs.deviceFingerprint(appContext)
                ?: biometricSigner.info()?.fingerprint
        }

        override fun hasKey(): Boolean = biometricSigner.exists()

        override suspend fun signDecision(decision: Decision, request: RootRequest): SignOutcome {
            val activity = activityProvider()
                ?: return SignOutcome.Error("Biometric prompt requires an active window.")
            val rootDecision = when (decision) {
                Decision.APPROVE -> RootDecision.APPROVE
                Decision.DENY -> RootDecision.DENY
            }
            val approvalReq = RootApprovalRequest(
                id = request.id,
                nonce = request.nonce,
                expires = request.expires.toLong(),
                argv = request.argv,
                timeout = request.timeout,
                reason = request.reason,
                snapSha256 = request.snapSha256.orEmpty(),
            )
            return when (val out = biometricSigner.approveRoot(activity, rootDecision, approvalReq)) {
                is RootSignOutcome.Signed -> SignOutcome.Signed(out.signatureBase64)
                is RootSignOutcome.Cancelled -> SignOutcome.Cancelled
                is RootSignOutcome.NeedsReenroll -> SignOutcome.Error(out.message, requiresReenroll = true)
                is RootSignOutcome.Lockout -> SignOutcome.Error(out.message, requiresReenroll = false)
                is RootSignOutcome.Failure -> SignOutcome.Error(out.message, requiresReenroll = false)
            }
        }
    }

    fun createSettingsPlatformBridge(
        activityProvider: () -> FragmentActivity?,
    ): SettingsPlatformBridge = object : SettingsPlatformBridge {
        override val apkUpdateState: StateFlow<ApkUpdateState>
            get() = apkUpdater.state

        override fun currentVersionName(): String = apkUpdater.installedVersion().first

        override fun currentVersionCode(): Int = apkUpdater.installedVersion().second.toInt()

        override fun keyringVaultSupported(): Boolean =
            biometricSigner.biometricStatus(appContext) == BiometricStatus.OK

        override fun keyringHasSaved(): Boolean = keyringVault.hasSaved(appContext)

        override suspend fun keyringSave(password: String): VaultResult {
            val activity = activityProvider()
                ?: return VaultResult.Error("Biometric prompt requires an active window.")
            return when (val res = keyringVault.save(activity, appContext, password)) {
                is KeyringSaveResult.Saved -> VaultResult.Success()
                is KeyringSaveResult.Failure -> if (res.cancelled) {
                    VaultResult.Cancelled
                } else {
                    VaultResult.Error(res.message, invalidated = res.invalidated)
                }
            }
        }

        override suspend fun keyringDecrypt(): VaultResult {
            val activity = activityProvider()
                ?: return VaultResult.Error("Biometric prompt requires an active window.")
            return keyringVault.decrypt(activity, appContext).fold(
                onSuccess = { plaintext -> VaultResult.Success(plaintext) },
                onFailure = { err ->
                    val pe = err as? PlatformException
                    when (pe?.code) {
                        "E_CANCELLED" -> VaultResult.Cancelled
                        "E_KEY_INVALIDATED", "E_NO_KEY" -> VaultResult.Error(
                            message = pe.message.ifBlank {
                                "Saved keyring password is no longer usable. Save it again."
                            },
                            invalidated = true,
                        )
                        else -> VaultResult.Error(pe?.message ?: err.message ?: "Biometric unlock failed.")
                    }
                },
            )
        }

        override fun keyringForget() {
            keyringVault.forget(appContext)
        }

        override fun fcmDiagnostics(): FcmDiagnostics {
            val st = pushManager.status(appContext)
            return FcmDiagnostics(
                notificationsEnabled = st.notificationsEnabled,
                token = st.token,
                error = st.error,
            )
        }

        override suspend fun registerFcmToken(): Boolean {
            return pushManager.register(appContext, apiClient.okHttpClient) is PushRegisterResult.Success
        }

        override fun openNotificationSettings() {
            pushManager.openNotificationSettings(appContext)
        }

        override fun loadCrashReports(): List<CrashReportEntry> {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            return crashReporter.pending(appContext).map { report ->
                val tsMs = if (report.ts > 0.0) (report.ts * 1000.0).toLong() else 0L
                val formattedTs = if (tsMs > 0L) fmt.format(Date(tsMs)) else "Unknown time"
                val summary = listOfNotNull(
                    report.kind.takeIf { it.isNotBlank() },
                    report.where.takeIf { it.isNotBlank() },
                    report.reason.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "Crash report" }
                CrashReportEntry(
                    id = report.id,
                    timestamp = formattedTs,
                    summary = summary,
                    stackTrace = report.text,
                )
            }
        }

        override fun clearCrashReports() {
            crashReporter.clearAll(appContext)
        }

        override fun downloadAndInstallApk(apkUrl: String) {
            appScope.launch(Dispatchers.IO) {
                apkInstallCoordinator.startOrInstall(apkUrl)
            }
        }
    }
}

/**
 * Application entry point holding [container] and configuring Coil 3 to share [PcApiClient.okHttpClient].
 */
class PcControlApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    val prefs: AppPrefs
        get() = container.prefs

    val cache: LocalCache
        get() = container.cache

    val apiClient: PcApiClient
        get() = container.apiClient

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReporter.install(this)
        WebRtcRuntime.onAppStart(this)
        container = AppContainer(this)
        container.startBackgroundTasks()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { container.apiClient.okHttpClient },
                    ),
                )
            }
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: PcControlApp
            private set
    }
}
