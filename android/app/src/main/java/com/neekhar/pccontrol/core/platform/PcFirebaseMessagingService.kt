package com.neekhar.pccontrol.core.platform

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM entry point for PC Control.
 *
 * `pc-api` sends data-only messages (`kind`, `id`, `title`, `body`, and optional metadata such as
 * `expires`, `cat`, `image`, `link`, `cam`, `ts`), so this service runs in the background as well
 * as the foreground and selects the appropriate notification channel and deep-link action.
 * Payloads never contain commands or secrets.
 */
class PcFirebaseMessagingService : FirebaseMessagingService() {

  override fun onNewToken(token: String) {
    // Runs on FCM's worker thread; retried on next app launch if offline.
    PushManager.registerTokenBlocking(applicationContext, token)
    try {
      PushManager.onTokenRefreshed?.invoke(token)
    } catch (_: Exception) {
    }
  }

  override fun onMessageReceived(msg: RemoteMessage) {
    val d = msg.data
    val id = d["id"]?.takeIf { it.isNotEmpty() } ?: return
    val title = d["title"].orEmpty()
    val body = d["body"].orEmpty()

    when (d["kind"]) {
      "request" -> {
        val resolvedTitle = title.ifEmpty { "Root approval needed" }
        val expires = d["expires"]?.toLongOrNull() ?: 0L
        PushManager.emitIncoming(PushRouteEvent.RootRequest(id, resolvedTitle, body, expires))
        PcNotifier.showRootRequest(this, id, resolvedTitle, body, expires)
      }

      "result" -> {
        val resolvedTitle = title.ifEmpty { "Command finished" }
        PushManager.emitIncoming(PushRouteEvent.RootResult(id, resolvedTitle, body))
        PcNotifier.showRootResult(this, id, resolvedTitle, body)
      }

      "dismiss" -> {
        PushManager.emitIncoming(PushRouteEvent.RootDismiss(id))
        PcNotifier.dismissRootRequest(this, id)
      }

      "downloads" -> {
        // High-priority push lets Android start the dataSync foreground service from the background.
        PushManager.emitIncoming(PushRouteEvent.DownloadsStarted(id))
        LiveDownloadsController.start(this, fromPush = true)
      }

      "notify" -> {
        val cat = d["cat"]?.takeIf { it.isNotEmpty() }
        val image = d["image"]?.takeIf { it.isNotEmpty() }
        val link = d["link"]?.takeIf { it.isNotEmpty() }
        val cam = d["cam"]?.takeIf { it.isNotEmpty() }
        val ts = d["ts"]?.toLongOrNull()
        val resolvedTitle = title.ifEmpty { "PC Control" }

        PushManager.emitIncoming(
          PushRouteEvent.Notify(
            id = id,
            title = resolvedTitle,
            body = body,
            category = cat,
            imagePath = image,
            link = link,
            cam = cam,
            ts = ts,
          ),
        )

        if (cat == "chat") {
          ChatForegroundState.notifyPush(id, title, body)
          // WhatsApp-style foreground suppression while the Chat screen is actively visible.
          if (ChatForegroundState.visible) return
        }

        PcNotifier.showMessage(
          ctx = this,
          id = id,
          title = resolvedTitle,
          body = body,
          category = cat,
          imagePath = image,
          link = link,
          cam = cam,
          ts = ts,
        )
      }
    }
  }
}
