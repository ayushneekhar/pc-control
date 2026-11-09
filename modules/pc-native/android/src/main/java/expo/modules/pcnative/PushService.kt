package expo.modules.pcnative

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM entry point. pc-api sends data-only messages (kind, id, title, body[, expires]), so this runs in the
 * background too and the app picks the channel and tap action. Payloads never contain commands or secrets.
 */
class PushService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    Push.register(applicationContext, token)   // runs on FCM's worker thread; retried on next app start if offline
  }

  override fun onMessageReceived(msg: RemoteMessage) {
    val d = msg.data
    val id = d["id"]?.takeIf { it.isNotEmpty() } ?: return
    val title = d["title"].orEmpty()
    val body = d["body"].orEmpty()
    when (d["kind"]) {
      "request" -> Notifier.request(this, id, title.ifEmpty { "Root approval needed" }, body, d["expires"]?.toLongOrNull() ?: 0)
      "result" -> Notifier.result(this, id, title.ifEmpty { "Command finished" }, body)
      "dismiss" -> Notifier.dismiss(this, id)
      "notify" -> Notifier.message(this, id, title.ifEmpty { "PC Control" }, body)
    }
  }
}
