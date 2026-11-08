package expo.modules.pcnative

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
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
      appContext.reactContext?.let { ListenerService.ensureChannels(it) }
    }

    // ---- settings shared with the background service
    Function("getSettings") {
      mapOf(
        "serverUrl" to Prefs.serverUrl(ctx),
        "listenerEnabled" to Prefs.listenerEnabled(ctx),
        "deviceFingerprint" to Prefs.deviceFingerprint(ctx),
        "defaultServerUrl" to Prefs.DEFAULT_SERVER,
      )
    }
    Function("setServerUrl") { url: String -> Prefs.setServerUrl(ctx, url) }
    Function("setDeviceFingerprint") { fp: String? -> Prefs.setDeviceFingerprint(ctx, fp) }
    Function("deviceName") { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }

    // ---- background listener
    Function("setListenerEnabled") { on: Boolean ->
      Prefs.setListenerEnabled(ctx, on)
      if (on) ListenerService.start(ctx) else ListenerService.stop(ctx)
    }
    Function("restartListener") {
      if (Prefs.listenerEnabled(ctx)) {
        ListenerService.stop(ctx)
        ListenerService.start(ctx)
      }
    }
    Function("listenerStatus") {
      mapOf("running" to ListenerService.running, "connected" to ListenerService.connected)
    }
    Function("notificationsEnabled") { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    Function("openNotificationSettings") {
      val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(i)
    }

    // ---- battery optimization (Doze)
    Function("isIgnoringBatteryOptimizations") {
      (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    Function("requestIgnoreBatteryOptimizations") {
      @SuppressLint("BatteryLife")
      val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
        ctx.startActivity(i)
      } catch (_: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
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
