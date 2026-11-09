package expo.modules.pcnative

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cams screen helpers: fullscreen landscape, keep the screen on, snapshots to the gallery. */
object CamsTools {
  private val STREAM = Regex("^[a-z0-9_]{1,32}$")

  /** Landscape + hidden system bars (swipe from an edge shows them briefly), or back to portrait. Main thread. */
  fun setFullscreen(activity: Activity, on: Boolean) {
    activity.requestedOrientation =
      if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (on) {
      c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      c.hide(WindowInsetsCompat.Type.systemBars())
    } else {
      c.show(WindowInsetsCompat.Type.systemBars())
    }
  }

  fun keepAwake(activity: Activity, on: Boolean) {
    if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  /**
   * Fetch go2rtc's current frame of [stream] from the configured server and save it as a JPEG in
   * Pictures/PC Control (MediaStore: no storage permission on Android 10+). Returns the content:// uri.
   * Background thread.
   */
  fun saveSnapshot(ctx: Context, stream: String, label: String): String {
    if (!STREAM.matches(stream)) throw PcException("E_SNAPSHOT", "bad stream name")
    val url = URL(Prefs.serverUrl(ctx) + "/api/frame.jpeg?src=" + stream)
    val conn = (url.openConnection() as HttpURLConnection).apply {
      connectTimeout = 8000
      readTimeout = 15000
      instanceFollowRedirects = false
    }
    val bytes = try {
      if (conn.responseCode != 200) throw PcException("E_SNAPSHOT", "the camera server answered HTTP ${conn.responseCode}")
      conn.inputStream.use { it.readBytes() }
    } finally {
      conn.disconnect()
    }
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) throw PcException("E_SNAPSHOT", "not a JPEG")
    return saveJpeg(ctx, bytes, label, stream)
  }

  /** Writes a JPEG to Pictures/PC Control as "<label>-<time>.jpg"; returns its content:// uri. */
  fun saveJpeg(ctx: Context, bytes: ByteArray, label: String, fallbackName: String): String {
    val safe = label.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { fallbackName }
    val name = "$safe-${SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ROOT).format(Date())}.jpg"
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PC Control")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val cr = ctx.contentResolver
    val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: throw PcException("E_SNAPSHOT", "the gallery refused the picture")
    try {
      cr.openOutputStream(uri)?.use { it.write(bytes) } ?: throw PcException("E_SNAPSHOT", "can't write the picture")
      cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
      cr.delete(uri, null, null)
      throw e
    }
    return uri.toString()
  }

  /** Share sheet for a picture saved by [saveSnapshot]. */
  fun share(activity: Activity, uri: String) {
    val u = Uri.parse(uri)
    if (u.scheme != "content" || u.authority != MediaStore.AUTHORITY) throw PcException("E_SHARE", "not a gallery picture")
    val send = Intent(Intent.ACTION_SEND).apply {
      type = "image/jpeg"
      putExtra(Intent.EXTRA_STREAM, u)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    activity.startActivity(Intent.createChooser(send, "Share snapshot"))
  }
}
