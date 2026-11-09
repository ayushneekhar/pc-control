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

    // ---- window insets (dp), measured on the activity's decor view. Edge-to-edge is mandatory on
    // targetSdk 35+, so content always draws under the status/navigation bars; these are the absolute
    // system-bar + cutout insets, independent of where any React view happens to be laid out.
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
