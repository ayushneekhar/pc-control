package expo.modules.pcnative

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts the listener after boot / app update when the user turned it on (specialUse FGS may start from boot). */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    if (!Prefs.listenerEnabled(context)) return
    try {
      ListenerService.start(context)
    } catch (e: Exception) {
      Log.w("PcListener", "could not start listener on $action", e)
    }
  }
}
