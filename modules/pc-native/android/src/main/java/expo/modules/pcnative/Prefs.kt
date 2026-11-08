package expo.modules.pcnative

import android.content.Context

object Prefs {
  const val DEFAULT_SERVER = "https://ayushlive-hp-pavilion.tail65e4a4.ts.net"
  private const val FILE = "pc_control"

  private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  fun serverUrl(ctx: Context): String = sp(ctx).getString("serverUrl", null) ?: DEFAULT_SERVER
  fun setServerUrl(ctx: Context, url: String) = sp(ctx).edit().putString("serverUrl", url.trimEnd('/')).apply()

  fun listenerEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("listenerEnabled", false)
  fun setListenerEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("listenerEnabled", on).apply()

  fun deviceFingerprint(ctx: Context): String? = sp(ctx).getString("deviceFingerprint", null)
  fun setDeviceFingerprint(ctx: Context, fp: String?) = sp(ctx).edit().putString("deviceFingerprint", fp).apply()
}
