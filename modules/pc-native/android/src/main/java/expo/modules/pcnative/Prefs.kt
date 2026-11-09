package expo.modules.pcnative

import android.content.Context

object Prefs {
  const val DEFAULT_SERVER = "https://ayushlive-hp-pavilion.tail65e4a4.ts.net"
  private const val FILE = "pc_control"

  private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  fun serverUrl(ctx: Context): String = sp(ctx).getString("serverUrl", null) ?: DEFAULT_SERVER
  fun setServerUrl(ctx: Context, url: String) = sp(ctx).edit().putString("serverUrl", url.trimEnd('/')).apply()

  fun deviceFingerprint(ctx: Context): String? = sp(ctx).getString("deviceFingerprint", null)
  fun setDeviceFingerprint(ctx: Context, fp: String?) = sp(ctx).edit().putString("deviceFingerprint", fp).apply()

  /** Downloads (pc-api ids) the user stopped live progress for: opening the app doesn't bring those back. */
  fun liveDismissed(ctx: Context): Set<String> = sp(ctx).getStringSet("liveDismissed", null)?.toSet() ?: emptySet()
  fun setLiveDismissed(ctx: Context, ids: Set<String>) = sp(ctx).edit().putStringSet("liveDismissed", ids).apply()

  /** Last FCM token pc-api accepted (null if the last attempt failed) and the last error. */
  fun pushToken(ctx: Context): String? = sp(ctx).getString("pushToken", null)
  fun pushError(ctx: Context): String? = sp(ctx).getString("pushError", null)
  fun setPush(ctx: Context, token: String?, error: String?) =
    sp(ctx).edit().putString("pushToken", token).putString("pushError", error)
      .remove("listenerEnabled").apply()
}
