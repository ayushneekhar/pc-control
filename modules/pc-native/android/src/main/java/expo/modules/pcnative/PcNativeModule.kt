package expo.modules.pcnative

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import expo.modules.kotlin.Promise
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class PcNativeModule : Module() {
  private val ctx: Context
    get() = appContext.reactContext ?: throw PcException("E_NO_CONTEXT", "React context is gone")

  override fun definition() = ModuleDefinition {
    Name("PcNative")

    OnCreate {
      appContext.reactContext?.let { Notifier.ensureChannels(it) }
    }

    // ---- settings shared with the push service
    Function("getSettings") {
      mapOf(
        "serverUrl" to Prefs.serverUrl(ctx),
        "deviceFingerprint" to Prefs.deviceFingerprint(ctx),
        "defaultServerUrl" to Prefs.DEFAULT_SERVER,
      )
    }
    Function("setServerUrl") { url: String -> Prefs.setServerUrl(ctx, url) }
    Function("setDeviceFingerprint") { fp: String? -> Prefs.setDeviceFingerprint(ctx, fp) }
    Function("deviceName") { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }

    // ---- push (FCM)
    Function("pushStatus") {
      mapOf("available" to Push.available(ctx), "token" to Prefs.pushToken(ctx), "error" to Prefs.pushError(ctx))
    }
    // Fetch the FCM token and (re-)register it with pc-api. Runs on the module's background queue.
    AsyncFunction("pushRegister") {
      val c = ctx
      val token = try { Push.token(c) } catch (e: PcException) { throw e } catch (e: Exception) {
        Prefs.setPush(c, null, e.message ?: "could not get an FCM token")
        throw PcException("E_FCM_TOKEN", e.message ?: "could not get an FCM token", e)
      }
      val ok = Push.register(c, token)
      mapOf("token" to token, "registered" to ok, "error" to Prefs.pushError(c))
    }
    Function("notificationsEnabled") { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    Function("openNotificationSettings") {
      val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(i)
    }

    // ---- live download progress (Android 16 Live Updates / Now Bar; see LiveDownloads)
    Function("liveStatus") {
      val c = ctx
      mapOf(
        "sdk" to Build.VERSION.SDK_INT,
        "supported" to (Build.VERSION.SDK_INT >= 36),
        "canPromote" to LiveDownloads.canPromote(c),
        "channelEnabled" to LiveDownloads.channelEnabled(c),
        "running" to LiveDownloads.running,
      )
    }
    Function("liveStart") { activeIds: List<String> -> LiveDownloads.start(ctx, fromPush = false, activeIds = activeIds) }
    Function("liveStop") { LiveDownloads.stop(ctx) }
    // Android 16's per-app "Live notifications" switch; falls back to the app's notification settings.
    Function("openLiveSettings") {
      val c = ctx
      val pkg = c.packageName
      val tries = buildList {
        if (Build.VERSION.SDK_INT >= 36) add(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS))
        add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS))
      }
      for (i in tries) {
        try {
          c.startActivity(i.putExtra(Settings.EXTRA_APP_PACKAGE, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          return@Function true
        } catch (_: Exception) {}
      }
      false
    }

    // ---- OTA (react-native-nitro-ota + our own signature check, see OtaVerifier)
    Function("otaCheck") { path: String -> OtaVerifier.check(ctx, path) }
    Function("otaBinaryTarget") { OtaVerifier.binaryTarget(ctx) }
    Function("otaRecoveryStatus") {
      val f = OtaVerifier.recoveryFile(ctx)
      if (f.isFile) f.readText() else null
    }
    Function("clearOtaRecoveryStatus") { OtaVerifier.recoveryFile(ctx).delete() }

    // ---- window insets (dp), measured on the activity's decor view. Edge-to-edge is mandatory on
    // targetSdk 35+, so content always draws under the status/navigation bars; these are the absolute
    // system-bar + cutout insets, independent of where any React view happens to be laid out.
    // ---- cams player (react-native-webrtc; options + crash guard in CamsWebrtc)
    Function("camsHardware") { CamsWebrtc.hardwareDecoders(ctx) }
    // App.tsx, right before the Cams screen loads react-native-webrtc: crash marker + this process's options
    Function("camsPrepare") { CamsWebrtc.prepare(ctx) }
    Function("camsStatus") { CamsWebrtc.status(ctx) }
    Function("camsMarkOpening") { on: Boolean -> CamsWebrtc.markOpening(ctx, on) }
    Function("camsSetSetting") { key: String, on: Boolean -> CamsWebrtc.setSetting(ctx, key, on) }
    Function("camsCrashSeen") { CamsWebrtc.clearCrashedWhileOpening() }

    // ---- crash reports (CrashReporter), uploaded by src/lib/crash.ts
    AsyncFunction("crashPending") { CrashReporter.pending(ctx) }
    Function("crashAck") { ids: List<String> -> CrashReporter.ack(ctx, ids) }
    AsyncFunction("camsSetFullscreen") { on: Boolean ->
      appContext.currentActivity?.let { CamsTools.setFullscreen(it, on) }
      Unit
    }.runOnQueue(Queues.MAIN)
    AsyncFunction("camsKeepAwake") { on: Boolean ->
      appContext.currentActivity?.let { CamsTools.keepAwake(it, on) }
      Unit
    }.runOnQueue(Queues.MAIN)
    AsyncFunction("camsSaveSnapshot") { stream: String, label: String -> CamsTools.saveSnapshot(ctx, stream, label) }
    AsyncFunction("camsShare") { uri: String ->
      val a = appContext.currentActivity ?: throw PcException("E_SHARE", "no activity")
      CamsTools.share(a, uri)
    }.runOnQueue(Queues.MAIN)

    // "Share → PC Control" text (SharePackage), once
    Function("takeSharedText") { SharedText.take() }

    // Replay player (Media3, see RecPlayerView)
    View(RecPlayerView::class) {
      Events("onProgress", "onPlayerError")
      Prop("source") { view: RecPlayerView, src: Map<String, Any?>? -> view.setSource(src) }
      Prop("playing") { view: RecPlayerView, on: Boolean -> view.setPlaying(on) }
      Prop("rate") { view: RecPlayerView, r: Double -> view.setRate(r) }
      AsyncFunction("seek") { view: RecPlayerView, seconds: Double, exact: Boolean -> view.seek(seconds, exact) }.runOnQueue(Queues.MAIN)
      AsyncFunction("snapshot") { view: RecPlayerView, label: String -> view.snapshot(label) }.runOnQueue(Queues.MAIN)
      OnViewDestroys { view: RecPlayerView -> view.release() }
    }

    Function("windowInsets") {
      val activity = appContext.currentActivity ?: return@Function null
      val raw = activity.window?.decorView?.rootWindowInsets ?: return@Function null
      val i = WindowInsetsCompat.toWindowInsetsCompat(raw)
        .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      val d = activity.resources.displayMetrics.density
      mapOf("top" to i.top / d, "bottom" to i.bottom / d, "left" to i.left / d, "right" to i.right / d)
    }

    // ---- root-approval key
    Function("biometricStatus") { Signer.biometricStatus(ctx) }
    Function("keyInfo") { Signer.info() }
    AsyncFunction("createKey") {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw PcException("E_OLD_ANDROID", "Android 11 or newer is required")
      Signer.create()
    }
    Function("deleteKey") {
      Signer.delete()
      Prefs.setDeviceFingerprint(ctx, null)
    }
    AsyncFunction("sign") { payload: String, title: String, subtitle: String, description: String, promise: Promise ->
      val activity = appContext.currentActivity as? FragmentActivity
      if (activity == null) {
        promise.reject(PcException("E_NO_ACTIVITY", "Open the app to approve"))
        return@AsyncFunction
      }
      Signer.sign(activity, payload.toByteArray(Charsets.UTF_8), title, subtitle, description) { r ->
        r.fold({ promise.resolve(it) }, { e ->
          if (e is PcException) promise.reject(e) else promise.reject(PcException("E_SIGN", e.message ?: "failed", e))
        })
      }
    }.runOnQueue(Queues.MAIN)
  }
}
