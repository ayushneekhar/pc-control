package expo.modules.pcnative

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** FCM token handling: fetch the token and register it with pc-api over the tailnet (same auth as the app). */
object Push {
  private const val TAG = "PcPush"

  fun available(ctx: Context): Boolean = try {
    FirebaseApp.getApps(ctx).isNotEmpty()
  } catch (_: Exception) { false }

  /** Blocking: call off the main thread. */
  fun token(ctx: Context): String {
    if (!available(ctx)) throw PcException("E_NO_FIREBASE", "This build has no Firebase config")
    return Tasks.await(FirebaseMessaging.getInstance().token, 30, TimeUnit.SECONDS)
  }

  /** Blocking POST /pc/api/push/register. Records the outcome in Prefs for the Settings screen. */
  fun register(ctx: Context, token: String): Boolean {
    try {
      val c = (URL(Prefs.serverUrl(ctx) + "/pc/api/push/register").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 15_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
      }
      try {
        val body = JSONObject().put("token", token).put("name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code != 200) throw IllegalStateException("HTTP $code")
      } finally { c.disconnect() }
    } catch (e: Exception) {
      Log.i(TAG, "register failed: ${e.message}")
      Prefs.setPush(ctx, null, e.message ?: e.javaClass.simpleName)
      return false
    }
    Prefs.setPush(ctx, token, null)
    return true
  }
}
