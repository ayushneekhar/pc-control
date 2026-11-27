package com.neekhar.pccontrol.core.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves the small notification icon without taking a hard compile-time dependency on `R.drawable`.
 * Prefers `ic_stat_pc` if bundled by the app module, falling back to the app icon or system sync icon.
 */
internal fun resolveNotificationIcon(ctx: Context): Int {
  val statIcon = ctx.resources.getIdentifier("ic_stat_pc", "drawable", ctx.packageName)
  if (statIcon != 0) return statIcon
  val appIcon = ctx.applicationInfo.icon
  if (appIcon != 0) return appIcon
  return android.R.drawable.stat_notify_sync
}

/**
 * Notification channels, deep-link `PendingIntent`s (`pccontrol://...`), inline Chat `RemoteInput`
 * reply actions, and notification image fetching for PC Control.
 *
 * Channel IDs match the previous Expo/RN build so users keep their per-channel Android settings.
 */
object PcNotifier {
  const val CH_REQUESTS = "root_requests"
  const val CH_RESULTS = "root_results"
  const val CH_MESSAGES = "messages"
  const val CH_CHAT = "cat_chat"
  const val CH_KEYRING = "cat_keyring"
  const val CH_CATS = "cat_cats"
  const val CH_SYSTEM = "cat_system"
  const val CH_AVAILABLE = "cat_available"
  const val CH_GRABBED = "cat_grabbed"
  const val CH_PROBLEMS = "cat_problems"
  const val CH_QWEN_DONE = "cat_qwen_done"
  const val CH_USAGE_LIMITS = "cat_usage_limits"

  private const val ID_REQUEST = 2
  private const val ID_RESULT = 3
  private const val ID_MESSAGE = 4
  private const val EXTRA_CATEGORY = "pc_control_cat"
  private const val TAG_CHAT_PREFIX = "chat_"

  private val IMAGE_PATH_RE = Regex("^/pc/api/notify/image/[0-9a-f]{16}$")

  /** Router categories (`pc-api` CATEGORIES) mapped to their dedicated Android channel label & importance. */
  private val CATEGORY_CHANNELS = linkedMapOf(
    "cats" to ("Cat alerts" to NotificationManager.IMPORTANCE_HIGH),
    "grabbed" to ("Download started" to NotificationManager.IMPORTANCE_LOW),
    "available" to ("Available to watch" to NotificationManager.IMPORTANCE_DEFAULT),
    "problems" to ("Download problems" to NotificationManager.IMPORTANCE_HIGH),
    "qwen_done" to ("Qwen downloads finished" to NotificationManager.IMPORTANCE_DEFAULT),
    "system" to ("Backups & system alerts" to NotificationManager.IMPORTANCE_HIGH),
    "usage_limits" to ("Usage limits" to NotificationManager.IMPORTANCE_HIGH),
    "keyring" to ("Keyring locked" to NotificationManager.IMPORTANCE_HIGH),
    "chat" to ("Agent chat" to NotificationManager.IMPORTANCE_HIGH),
  )

  fun ensureChannels(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
    nm.deleteNotificationChannel("listener") // legacy SSE listener channel
    nm.createNotificationChannel(
      NotificationChannel(CH_REQUESTS, "Root requests", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "A command is waiting for your fingerprint approval"
        enableVibration(true)
      },
    )
    nm.createNotificationChannel(
      NotificationChannel(CH_RESULTS, "Root results", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "An approved command finished"
      },
    )
    nm.createNotificationChannel(
      NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Notifications from the laptop, including test notifications"
      },
    )
    LiveDownloadsController.ensureChannel(ctx)
    for ((catId, meta) in CATEGORY_CHANNELS) {
      nm.createNotificationChannel(NotificationChannel("cat_$catId", meta.first, meta.second))
    }
  }

  private fun deepLinkIntent(ctx: Context, path: String, requestCode: Int): PendingIntent {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("pccontrol://$path"))
      .setPackage(ctx.packageName)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(
      ctx,
      requestCode,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  private fun post(ctx: Context, tag: String, id: Int, builder: NotificationCompat.Builder) {
    ensureChannels(ctx)
    try {
      NotificationManagerCompat.from(ctx).notify(tag, id, builder.build())
    } catch (_: SecurityException) {
    }
  }

  private fun baseBuilder(
    ctx: Context,
    channelId: String,
    title: String,
    body: String,
  ): NotificationCompat.Builder =
    NotificationCompat.Builder(ctx, channelId)
      .setSmallIcon(resolveNotificationIcon(ctx))
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setAutoCancel(true)

  /** Root approval request: tapping opens `pccontrol://root/<id>`; approval still requires biometric signing. */
  fun showRootRequest(ctx: Context, id: String, title: String, body: String, expiresEpochSec: Long) {
    val remainingMs = expiresEpochSec * 1000L - System.currentTimeMillis()
    if (remainingMs <= 0L) return
    post(
      ctx = ctx,
      tag = id,
      id = ID_REQUEST,
      builder = baseBuilder(ctx, CH_REQUESTS, title, body)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setTimeoutAfter(remainingMs)
        .setContentIntent(deepLinkIntent(ctx, "root/$id", id.hashCode())),
    )
  }

  fun showRootResult(ctx: Context, id: String, title: String, body: String) {
    dismissRootRequest(ctx, id)
    post(
      ctx = ctx,
      tag = id,
      id = ID_RESULT,
      builder = baseBuilder(ctx, CH_RESULTS, title, body)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setTimeoutAfter(10 * 60_000L)
        .setContentIntent(deepLinkIntent(ctx, "root/$id", id.hashCode())),
    )
  }

  /** Drops an active root request notification when decided, expired, or cancelled elsewhere. */
  fun dismissRootRequest(ctx: Context, id: String) {
    NotificationManagerCompat.from(ctx).cancel(id, ID_REQUEST)
  }

  internal fun isAllowedLink(ctx: Context, link: String): Boolean {
    val uri = try {
      Uri.parse(link)
    } catch (_: Exception) {
      return false
    }
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme == "pccontrol") return true
    if (scheme != "https" && scheme != "http") return false
    val host = uri.host?.lowercase() ?: return false
    val serverHost = try {
      Uri.parse(PlatformPrefs.serverUrl(ctx)).host?.lowercase()
    } catch (_: Exception) {
      null
    }
    return serverHost != null && host == serverHost
  }

  fun showMessage(
    ctx: Context,
    id: String,
    title: String,
    body: String,
    category: String? = null,
    imagePath: String? = null,
    link: String? = null,
    cam: String? = null,
    ts: Long? = null,
  ) {
    val channelId = if (category != null && CATEGORY_CHANNELS.containsKey(category)) {
      "cat_$category"
    } else {
      CH_MESSAGES
    }

    val sightingPath = if (cam != null && ts != null) {
      "cams?tab=replay&cam=${Uri.encode(cam)}&ts=$ts"
    } else {
      null
    }

    val tapIntent = when {
      link != null && isAllowedLink(ctx, link) -> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (link.lowercase().startsWith("pccontrol://")) {
          intent.setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        PendingIntent.getActivity(
          ctx,
          id.hashCode(),
          intent,
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
      }
      sightingPath != null -> deepLinkIntent(ctx, sightingPath, id.hashCode())
      category == "usage_limits" -> deepLinkIntent(ctx, "agent-settings", id.hashCode())
      category == "keyring" -> deepLinkIntent(ctx, "routes?unlockKeyring=1", id.hashCode())
      category == "chat" -> deepLinkIntent(ctx, "chat", id.hashCode())
      else -> deepLinkIntent(ctx, "routes", id.hashCode())
    }

    val isChatMessage = category == "chat" || (category == null && isLegacyChatTitle(title))
    val displayBody = if (isChatMessage) {
      MarkdownStripper.stripForNotification(body).ifBlank { body }
    } else {
      body
    }

    val builder = baseBuilder(ctx, channelId, title, displayBody)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setContentIntent(tapIntent)

    val tag = if (category == "chat") "$TAG_CHAT_PREFIX$id" else id

    if (category == "chat") {
      builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)
      builder.addExtras(Bundle().apply { putString(EXTRA_CATEGORY, "chat") })
      val remoteInput = RemoteInput.Builder(ChatReplyReceiver.KEY_TEXT_REPLY)
        .setLabel("Reply")
        .build()
      val replyIntent = Intent(ctx, ChatReplyReceiver::class.java).apply {
        action = ChatReplyReceiver.ACTION_CHAT_REPLY
        putExtra(ChatReplyReceiver.EXTRA_NOTIF_TAG, tag)
        putExtra(ChatReplyReceiver.EXTRA_NOTIF_ID, ID_MESSAGE)
      }
      val replyFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      } else {
        PendingIntent.FLAG_UPDATE_CURRENT
      }
      val replyPendingIntent = PendingIntent.getBroadcast(
        ctx,
        ("reply_$id").hashCode(),
        replyIntent,
        replyFlags,
      )
      val replyAction = NotificationCompat.Action.Builder(0, "Reply", replyPendingIntent)
        .addRemoteInput(remoteInput)
        .setAllowGeneratedReplies(true)
        .build()
      builder.addAction(replyAction)
    }

    val bitmap = imagePath?.let { fetchImage(ctx, it) }
    if (bitmap != null) {
      builder.setLargeIcon(bitmap)
        .setStyle(
          NotificationCompat.BigPictureStyle()
            .bigPicture(bitmap)
            .bigLargeIcon(null as Bitmap?)
            .setSummaryText(displayBody),
        )
    }

    post(ctx, tag, ID_MESSAGE, builder)
  }

  /** Replaces an inline-reply chat notification with an error so Android's reply spinner always resolves. */
  fun showChatReplyError(ctx: Context, tag: String?, notifId: Int, errorText: String) {
    val resolvedTag = tag?.takeIf { it.isNotEmpty() } ?: "${TAG_CHAT_PREFIX}reply_err"
    val resolvedId = if (notifId != 0) notifId else ID_MESSAGE
    val builder = baseBuilder(ctx, CH_CHAT, "Reply not sent", errorText)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setContentIntent(deepLinkIntent(ctx, "chat", resolvedTag.hashCode()))
      .addExtras(Bundle().apply { putString(EXTRA_CATEGORY, "chat") })
    post(ctx, resolvedTag, resolvedId, builder)
  }

  /** Fetches `/pc/api/notify/image/<16-hex>` from the configured server, downsampled to <= 1440px width. */
  private fun fetchImage(ctx: Context, path: String): Bitmap? = try {
    if (!IMAGE_PATH_RE.matches(path)) {
      null
    } else {
      val imageClient = PlatformHttp.defaultClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
      val req = Request.Builder()
        .url(PlatformPrefs.serverUrl(ctx) + path)
        .get()
        .build()
      imageClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          null
        } else {
          val bytes = resp.body?.bytes() ?: return null
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
          var sample = 1
          while (bounds.outWidth / sample > 1440) {
            sample *= 2
          }
          BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
          )
        }
      }
    }
  } catch (_: Exception) {
    null
  }

  /** Cancels every active chat notification without touching root approvals or live downloads. */
  fun clearChatNotifications(ctx: Context): Int {
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return 0
    var cleared = 0
    for (sbn in nm.activeNotifications) {
      val notif = sbn.notification ?: continue
      val ch = notif.channelId
      if (ch == CH_REQUESTS || ch == CH_RESULTS) continue
      val title = notif.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
      val isChat = ch == CH_CHAT ||
        sbn.tag?.startsWith(TAG_CHAT_PREFIX) == true ||
        notif.extras?.getString(EXTRA_CATEGORY) == "chat" ||
        (ch == CH_MESSAGES && sbn.id == ID_MESSAGE && isLegacyChatTitle(title))
      if (isChat) {
        nm.cancel(sbn.tag, sbn.id)
        cleared++
      }
    }
    return cleared
  }

  private fun isLegacyChatTitle(title: String?): Boolean {
    if (title.isNullOrBlank()) return false
    return title == "Gemini" ||
      title == "Claude" ||
      title.startsWith("Gemini ·") ||
      title.startsWith("Claude ·")
  }

  /** Returns true when an intent targets `pccontrol://chat`. */
  fun isChatDeepLink(intent: Intent?): Boolean {
    val uri = intent?.data ?: return false
    return uri.scheme?.equals("pccontrol", ignoreCase = true) == true &&
      uri.host?.equals("chat", ignoreCase = true) == true
  }
}
