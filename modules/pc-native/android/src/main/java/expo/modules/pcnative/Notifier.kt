package expo.modules.pcnative

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Notification channels + display for push messages from pc-api (see PushService). */
object Notifier {
  const val CH_REQUESTS = "root_requests"
  const val CH_RESULTS = "root_results"
  const val CH_MESSAGES = "messages"
  private const val ID_REQUEST = 2
  private const val ID_RESULT = 3
  private const val ID_MESSAGE = 4

  /** Router categories (pc-api CATEGORIES) that get their own Android channel, so each can be tuned in system
   *  settings too. Root approvals/results use the root channels above; anything else uses CH_MESSAGES. */
  private val CATEGORY_CHANNELS = linkedMapOf(
    "cats" to Pair("Cat alerts", NotificationManager.IMPORTANCE_HIGH),
    "grabbed" to Pair("Download started", NotificationManager.IMPORTANCE_LOW),
    "available" to Pair("Available to watch", NotificationManager.IMPORTANCE_DEFAULT),
    "problems" to Pair("Download problems", NotificationManager.IMPORTANCE_HIGH),
    "qwen_done" to Pair("Qwen downloads finished", NotificationManager.IMPORTANCE_DEFAULT),
    "system" to Pair("Backups & system alerts", NotificationManager.IMPORTANCE_HIGH),
  )

  fun ensureChannels(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.deleteNotificationChannel("listener")   // the old SSE listener's ongoing notification (removed in 1.1)
    nm.createNotificationChannel(NotificationChannel(CH_REQUESTS, "Root requests",
      NotificationManager.IMPORTANCE_HIGH).apply {
      description = "A command is waiting for your fingerprint approval"
      enableVibration(true)
    })
    nm.createNotificationChannel(NotificationChannel(CH_RESULTS, "Root results",
      NotificationManager.IMPORTANCE_DEFAULT).apply {
      description = "An approved command finished"
    })
    nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages",
      NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Notifications from the laptop, including test notifications"
    })
    for ((id, v) in CATEGORY_CHANNELS) {
      nm.createNotificationChannel(NotificationChannel("cat_$id", v.first, v.second))
    }
  }

  private fun open(ctx: Context, path: String, code: Int): PendingIntent {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse("pccontrol://$path")).setPackage(ctx.packageName)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  private fun post(ctx: Context, tag: String, id: Int, b: NotificationCompat.Builder) {
    ensureChannels(ctx)
    try { NotificationManagerCompat.from(ctx).notify(tag, id, b.build()) } catch (_: SecurityException) {}
  }

  private fun base(ctx: Context, ch: String, title: String, body: String) =
    NotificationCompat.Builder(ctx, ch)
      .setSmallIcon(R.drawable.ic_stat_pc)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setAutoCancel(true)

  /** Root request: tapping opens the Root tab on it; approving still needs the biometric signature in-app. */
  fun request(ctx: Context, id: String, title: String, body: String, expires: Long) {
    val left = expires * 1000 - System.currentTimeMillis()
    if (left <= 0) return
    post(ctx, id, ID_REQUEST, base(ctx, CH_REQUESTS, title, body)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setTimeoutAfter(left)
      .setContentIntent(open(ctx, "root/$id", id.hashCode())))
  }

  fun result(ctx: Context, id: String, title: String, body: String) {
    dismiss(ctx, id)
    post(ctx, id, ID_RESULT, base(ctx, CH_RESULTS, title, body)
      .setTimeoutAfter(10 * 60_000L)
      .setContentIntent(open(ctx, "root/$id", id.hashCode())))
  }

  fun message(ctx: Context, id: String, title: String, body: String, cat: String? = null, image: String? = null,
              link: String? = null) {
    val ch = if (cat != null && CATEGORY_CHANNELS.containsKey(cat)) "cat_$cat" else CH_MESSAGES
    val tap = if (link != null && (link.startsWith("https://") || link.startsWith("http://"))) {
      PendingIntent.getActivity(ctx, id.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(link))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    } else open(ctx, "routes", id.hashCode())
    val b = base(ctx, ch, title, body).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(tap)
    val pic = image?.let { fetchImage(ctx, it) }
    if (pic != null) b.setLargeIcon(pic).setStyle(NotificationCompat.BigPictureStyle().bigPicture(pic)
      .bigLargeIcon(null as Bitmap?).setSummaryText(body))
    post(ctx, id, ID_MESSAGE, b)
  }

  /** The notification's picture from pc-api ("/pc/api/notify/image/<id>"), over the tailnet; null on any failure
   *  (then it's a text notification). Only paths on the configured laptop are fetched. */
  private fun fetchImage(ctx: Context, path: String): Bitmap? = try {
    if (!path.startsWith("/pc/api/notify/image/")) null else {
      val c = URL(Prefs.serverUrl(ctx) + path).openConnection() as HttpURLConnection
      c.connectTimeout = 5000; c.readTimeout = 8000
      try {
        if (c.responseCode != 200) null else c.inputStream.use { s ->
          val bytes = s.readBytes()
          val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
          var sample = 1
          while (o.outWidth / sample > 1440) sample *= 2
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
      } finally { c.disconnect() }
    }
  } catch (_: Exception) { null }

  /** The request was decided, expired or cancelled elsewhere: drop its notification. */
  fun dismiss(ctx: Context, id: String) {
    NotificationManagerCompat.from(ctx).cancel(id, ID_REQUEST)
  }
}
