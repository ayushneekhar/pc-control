package expo.modules.pcnative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/** Notification channels + display for push messages from pc-api (see PushService). */
object Notifier {
  const val CH_REQUESTS = "root_requests"
  const val CH_RESULTS = "root_results"
  const val CH_MESSAGES = "messages"
  const val CH_CHAT = "cat_chat"
  private const val ID_REQUEST = 2
  private const val ID_RESULT = 3
  private const val ID_MESSAGE = 4
  private const val EXTRA_CATEGORY = "pc_control_cat"
  private const val TAG_CHAT_PREFIX = "chat_"

  /** Router categories (pc-api CATEGORIES) that get their own Android channel, so each can be tuned in system
   *  settings too. Root approvals/results use the root channels above; anything else uses CH_MESSAGES. */
  private val CATEGORY_CHANNELS = linkedMapOf(
    "cats" to Pair("Cat alerts", NotificationManager.IMPORTANCE_HIGH),
    "grabbed" to Pair("Download started", NotificationManager.IMPORTANCE_LOW),
    "available" to Pair("Available to watch", NotificationManager.IMPORTANCE_DEFAULT),
    "problems" to Pair("Download problems", NotificationManager.IMPORTANCE_HIGH),
    "qwen_done" to Pair("Qwen downloads finished", NotificationManager.IMPORTANCE_DEFAULT),
    "system" to Pair("Backups & system alerts", NotificationManager.IMPORTANCE_HIGH),
    "usage_limits" to Pair("Usage limits", NotificationManager.IMPORTANCE_HIGH),
    "keyring" to Pair("Keyring locked", NotificationManager.IMPORTANCE_HIGH),
    "chat" to Pair("Agent chat", NotificationManager.IMPORTANCE_HIGH),
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
    LiveDownloads.ensureChannel(ctx)
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

  private val IMAGE_PATH_RE = Regex("^/pc/api/notify/image/[0-9a-f]{16}$")

  private fun isAllowedLink(ctx: Context, link: String): Boolean {
    val uri = try { Uri.parse(link) } catch (_: Exception) { return false }
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme == "pccontrol") return true
    if (scheme != "https" && scheme != "http") return false
    val host = uri.host?.lowercase() ?: return false
    val serverHost = try { Uri.parse(Prefs.serverUrl(ctx)).host?.lowercase() } catch (_: Exception) { null }
    return serverHost != null && host == serverHost
  }

  fun result(ctx: Context, id: String, title: String, body: String) {
    dismiss(ctx, id)
    post(ctx, id, ID_RESULT, base(ctx, CH_RESULTS, title, body)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setTimeoutAfter(10 * 60_000L)
      .setContentIntent(open(ctx, "root/$id", id.hashCode())))
  }

  fun message(ctx: Context, id: String, title: String, body: String, cat: String? = null, image: String? = null,
              link: String? = null, cam: String? = null, ts: Long? = null) {
    val ch = if (cat != null && CATEGORY_CHANNELS.containsKey(cat)) "cat_$cat" else CH_MESSAGES
    // A cat sighting carries its camera + frame time: tapping opens the Cams screen on Replay, seeked there.
    val sighting = if (cam != null && ts != null)
      "cams?tab=replay&cam=${Uri.encode(cam)}&ts=$ts" else null
    val tap = when {
      link != null && isAllowedLink(ctx, link) -> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (link.lowercase().startsWith("pccontrol://")) {
          intent.setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        PendingIntent.getActivity(ctx, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      }
      sighting != null -> open(ctx, sighting, id.hashCode())
      cat == "usage_limits" -> open(ctx, "agent-settings", id.hashCode())
      cat == "keyring" -> open(ctx, "routes?unlockKeyring=1", id.hashCode())
      cat == "chat" -> open(ctx, "chat", id.hashCode())
      else -> open(ctx, "routes", id.hashCode())
    }
    val b = base(ctx, ch, title, body).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(tap)
    val tag = if (cat == "chat") "$TAG_CHAT_PREFIX$id" else id
    if (cat == "chat") {
      b.setCategory(NotificationCompat.CATEGORY_MESSAGE)
      b.addExtras(Bundle().apply { putString(EXTRA_CATEGORY, "chat") })
      val remoteInput = RemoteInput.Builder(ChatReplyReceiver.KEY_TEXT_REPLY)
        .setLabel("Reply")
        .build()
      val replyIntent = Intent(ctx, ChatReplyReceiver::class.java).apply {
        action = ChatReplyReceiver.ACTION_CHAT_REPLY
        putExtra(ChatReplyReceiver.EXTRA_NOTIF_TAG, tag)
        putExtra(ChatReplyReceiver.EXTRA_NOTIF_ID, ID_MESSAGE)
      }
      val replyPendingIntent = PendingIntent.getBroadcast(
        ctx,
        ("reply_$id").hashCode(),
        replyIntent,
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
      val replyAction = NotificationCompat.Action.Builder(0, "Reply", replyPendingIntent)
        .addRemoteInput(remoteInput)
        .setAllowGeneratedReplies(true)
        .build()
      b.addAction(replyAction)
    }
    val pic = image?.let { fetchImage(ctx, it) }
    if (pic != null) b.setLargeIcon(pic).setStyle(NotificationCompat.BigPictureStyle().bigPicture(pic)
      .bigLargeIcon(null as Bitmap?).setSummaryText(body))
    post(ctx, tag, ID_MESSAGE, b)
  }

  /** Replace an inline-reply chat notification with a concise error so Android's reply spinner always resolves. */
  fun chatReplyError(ctx: Context, tag: String?, notifId: Int, errorText: String) {
    val resolvedTag = tag?.takeIf { it.isNotEmpty() } ?: "${TAG_CHAT_PREFIX}reply_err"
    val resolvedId = if (notifId != 0) notifId else ID_MESSAGE
    val b = base(ctx, CH_CHAT, "Reply not sent", errorText)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setContentIntent(open(ctx, "chat", resolvedTag.hashCode()))
      .addExtras(Bundle().apply { putString(EXTRA_CATEGORY, "chat") })
    post(ctx, resolvedTag, resolvedId, b)
  }

  /** The notification's picture from pc-api ("/pc/api/notify/image/<id>"), over the tailnet; null on any failure
   *  (then it's a text notification). Only paths on the configured laptop are fetched. */
  private fun fetchImage(ctx: Context, path: String): Bitmap? = try {
    if (!IMAGE_PATH_RE.matches(path)) null else {
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

  /** Cancel every active chat notification without touching root approvals or download notifications. */
  fun clearChatNotifications(ctx: Context): Int {
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return 0
    var cleared = 0
    for (sbn in nm.activeNotifications) {
      val notif = sbn.notification ?: continue
      val ch = notif.channelId
      if (ch == CH_REQUESTS || ch == CH_RESULTS) continue
      val isChat =
        ch == CH_CHAT ||
        sbn.tag?.startsWith(TAG_CHAT_PREFIX) == true ||
        notif.extras?.getString(EXTRA_CATEGORY) == "chat" ||
        (ch == CH_MESSAGES && sbn.id == ID_MESSAGE && isLegacyChatTitle(notif.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()))
      if (isChat) {
        nm.cancel(sbn.tag, sbn.id)
        cleared++
      }
    }
    return cleared
  }

  private fun isLegacyChatTitle(title: String?): Boolean {
    if (title.isNullOrBlank()) return false
    return title == "Gemini" || title == "Claude" || title.startsWith("Gemini ·") || title.startsWith("Claude ·")
  }

  /** Returns true when an intent is the pccontrol://chat deep link (e.g. tapping a chat notification). */
  fun isChatDeepLink(intent: Intent?): Boolean {
    val uri = intent?.data ?: return false
    return uri.scheme?.equals("pccontrol", ignoreCase = true) == true &&
      uri.host?.equals("chat", ignoreCase = true) == true
  }
}
