package expo.modules.pcnative

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import expo.modules.kotlin.Promise
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.ByteArrayOutputStream

class PcNativeModule : Module() {
  companion object {
    private const val REQ_PICK_ATTACHMENTS = 9417
    private const val MAX_PICK_FILES = 4
    private const val MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024
  }

  private val ctx: Context
    get() = appContext.reactContext ?: throw PcException("E_NO_CONTEXT", "React context is gone")

  @Volatile
  private var pendingPickPromise: Promise? = null

  private fun readPickedUri(context: Context, uri: Uri): Map<String, Any> {
    val resolver = context.contentResolver
    var displayName: String? = null
    var reportedSize = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
          displayName = cursor.getString(nameIdx)
        }
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
          reportedSize = cursor.getLong(sizeIdx)
        }
      }
    }
    val safeName = displayName?.trim()?.takeIf { it.isNotEmpty() }
      ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }
      ?: "attachment"
    if (reportedSize > MAX_ATTACHMENT_BYTES) {
      throw PcException("E_FILE_TOO_LARGE", "$safeName exceeds 15 MB limit")
    }
    val mimeType = resolver.getType(uri)?.trim()?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
    val bytes = resolver.openInputStream(uri)?.use { input ->
      val out = ByteArrayOutputStream()
      val buf = ByteArray(16 * 1024)
      var total = 0
      while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        total += n
        if (total > MAX_ATTACHMENT_BYTES) {
          throw PcException("E_FILE_TOO_LARGE", "$safeName exceeds 15 MB limit")
        }
        out.write(buf, 0, n)
      }
      out.toByteArray()
    } ?: throw PcException("E_PICK_READ", "Could not open $safeName")

    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    return mapOf(
      "name" to safeName,
      "mimeType" to mimeType,
      "sizeBytes" to bytes.size,
      "base64" to b64,
    )
  }

  override fun definition() = ModuleDefinition {
    Name("PcNative")

    OnCreate {
      appContext.reactContext?.let { Notifier.ensureChannels(it) }
    }

    OnDestroy {
      pendingPickPromise?.resolve(emptyList<Map<String, Any>>())
      pendingPickPromise = null
    }

    OnNewIntent { intent ->
      if (Notifier.isChatDeepLink(intent)) {
        appContext.reactContext?.let { Notifier.clearChatNotifications(it) }
      }
    }

    OnActivityResult { _, payload ->
      if (payload.requestCode != REQ_PICK_ATTACHMENTS) return@OnActivityResult
      val promise = pendingPickPromise ?: return@OnActivityResult
      pendingPickPromise = null
      val data = payload.data
      if (payload.resultCode != Activity.RESULT_OK || data == null) {
        promise.resolve(emptyList<Map<String, Any>>())
        return@OnActivityResult
      }
      val uris = ArrayList<Uri>()
      val clip = data.clipData
      if (clip != null) {
        for (i in 0 until clip.itemCount) {
          clip.getItemAt(i)?.uri?.let { uris.add(it) }
        }
      } else {
        data.data?.let { uris.add(it) }
      }
      val selected = uris.take(MAX_PICK_FILES)
      if (selected.isEmpty()) {
        promise.resolve(emptyList<Map<String, Any>>())
        return@OnActivityResult
      }
      val appCtx = appContext.reactContext?.applicationContext
      if (appCtx == null) {
        promise.reject(PcException("E_NO_CONTEXT", "React context is gone"))
        return@OnActivityResult
      }
      Thread {
        try {
          val items = selected.map { uri -> readPickedUri(appCtx, uri) }
          promise.resolve(items)
        } catch (e: PcException) {
          promise.reject(e)
        } catch (e: Exception) {
          promise.reject(PcException("E_PICK_READ", e.message ?: "Failed to read attachment", e))
        }
      }.start()
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
    Function("clearChatNotifications") { Notifier.clearChatNotifications(ctx) }

    // ---- chat attachment picker (Android SAF ACTION_OPEN_DOCUMENT; zero extra permissions)
    AsyncFunction("pickAttachments") { kind: String, promise: Promise ->
      val activity = appContext.currentActivity
      if (activity == null) {
        promise.reject(PcException("E_NO_ACTIVITY", "Open the app to pick attachments"))
        return@AsyncFunction
      }
      pendingPickPromise?.resolve(emptyList<Map<String, Any>>())
      pendingPickPromise = promise
      val mime = if (kind == "image") "image/*" else "*/*"
      val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mime
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      try {
        @Suppress("DEPRECATION")
        activity.startActivityForResult(intent, REQ_PICK_ATTACHMENTS)
      } catch (e: Exception) {
        pendingPickPromise = null
        promise.reject(PcException("E_PICK_LAUNCH", e.message ?: "Could not open file picker", e))
      }
    }.runOnQueue(Queues.MAIN)

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

    // Routes app tiles (Jellyfin): the package must be listed in this module's manifest <queries>
    Function("launchApp") { pkg: String ->
      val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return@Function false
      try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
      } catch (e: android.content.ActivityNotFoundException) {
        false
      }
    }

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

    // Live cat-cam video on a TextureView (so PinchZoom can transform it); see LivePlayerView
    View(LivePlayerView::class) {
      Name("LivePlayer")
      Prop("streamURL") { view: LivePlayerView, url: String? -> view.setStreamURL(url) }
      OnViewDestroys { view: LivePlayerView -> view.release() }
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
    AsyncFunction("approveRoot") { req: Map<String, Any?>, promise: Promise ->
      val activity = appContext.currentActivity as? FragmentActivity
      if (activity == null) {
        promise.reject(PcException("E_NO_ACTIVITY", "Open the app to approve"))
        return@AsyncFunction
      }
      val decision = req["decision"] as? String ?: ""
      val id = req["id"] as? String ?: ""
      val nonce = req["nonce"] as? String ?: ""
      val expires = (req["expires"] as? Number)?.toLong() ?: 0L
      val rawArgv = req["argv"] as? List<*>
      val argv = if (rawArgv != null && rawArgv.all { it is String }) rawArgv.filterIsInstance<String>() else emptyList()
      val reason = req["reason"] as? String ?: ""
      val timeout = (req["timeout"] as? Number)?.toInt() ?: 0
      val snapSha256 = req["snapSha256"] as? String ?: ""
      Signer.approveRoot(activity, decision, id, nonce, expires, argv, reason, timeout, snapSha256) { r ->
        r.fold({ promise.resolve(it) }, { e ->
          if (e is PcException) promise.reject(e) else promise.reject(PcException("E_SIGN", e.message ?: "failed", e))
        })
      }
    }.runOnQueue(Queues.MAIN)

    // ---- GNOME keyring password vault (Keystore AES-GCM + BiometricPrompt CryptoObject)
    Function("keyringHasSaved") { KeyringVault.hasSaved(ctx) }
    Function("keyringForget") { KeyringVault.forget(ctx) }
    AsyncFunction("keyringSave") { password: String, title: String, subtitle: String, promise: Promise ->
      val activity = appContext.currentActivity as? FragmentActivity
      if (activity == null) {
        promise.reject(PcException("E_NO_ACTIVITY", "Open the app to save the keyring password"))
        return@AsyncFunction
      }
      KeyringVault.save(activity, ctx, password, title, subtitle) { r ->
        r.fold({ promise.resolve(null) }, { e ->
          if (e is PcException) promise.reject(e) else promise.reject(PcException("E_ENCRYPT", e.message ?: "failed", e))
        })
      }
    }.runOnQueue(Queues.MAIN)
    AsyncFunction("keyringDecrypt") { title: String, subtitle: String, promise: Promise ->
      val activity = appContext.currentActivity as? FragmentActivity
      if (activity == null) {
        promise.reject(PcException("E_NO_ACTIVITY", "Open the app to unlock the keyring"))
        return@AsyncFunction
      }
      KeyringVault.decrypt(activity, ctx, title, subtitle) { r ->
        r.fold({ promise.resolve(it) }, { e ->
          if (e is PcException) promise.reject(e) else promise.reject(PcException("E_DECRYPT", e.message ?: "failed", e))
        })
      }
    }.runOnQueue(Queues.MAIN)
  }
}
