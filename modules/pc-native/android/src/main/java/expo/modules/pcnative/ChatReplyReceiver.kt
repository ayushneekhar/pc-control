package expo.modules.pcnative

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Handles inline "Reply" actions from Android chat notifications (RemoteInput) even on a cold background wakeup.
 */
class ChatReplyReceiver : BroadcastReceiver() {
  companion object {
    const val ACTION_CHAT_REPLY = "expo.modules.pcnative.ACTION_CHAT_REPLY"
    const val KEY_TEXT_REPLY = "key_chat_reply"
    const val EXTRA_NOTIF_TAG = "notif_tag"
    const val EXTRA_NOTIF_ID = "notif_id"
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_CHAT_REPLY) return
    val replyText = RemoteInput.getResultsFromIntent(intent)
      ?.getCharSequence(KEY_TEXT_REPLY)
      ?.toString()
      ?.trim()
      .orEmpty()
    if (replyText.isEmpty()) return

    val notifTag = intent.getStringExtra(EXTRA_NOTIF_TAG)
    val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
    val appCtx = context.applicationContext
    val pending = goAsync()

    Thread {
      try {
        val baseUrl = Prefs.chatBaseUrl(appCtx).trimEnd('/')
        val token = Prefs.chatToken(appCtx)
        val payload = JSONObject()
          .put("text", replyText)
          .put("client_id", "notif-${UUID.randomUUID()}")
          .toString()
          .toByteArray(Charsets.UTF_8)

        val conn = URL("$baseUrl/pc/api/chat/send").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        if (token.isNotEmpty()) {
          conn.setRequestProperty("Authorization", "Bearer $token")
        }
        try {
          conn.outputStream.use { it.write(payload) }
          val code = conn.responseCode
          if (code in 200..299) {
            Notifier.clearChatNotifications(appCtx)
            ChatForegroundState.notifyPush("reply", "You", replyText)
          } else {
            Notifier.chatReplyError(appCtx, notifTag, notifId, "Could not send reply (HTTP $code)")
          }
        } finally {
          conn.disconnect()
        }
      } catch (e: Exception) {
        val msg = e.message?.take(120)?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        Notifier.chatReplyError(appCtx, notifTag, notifId, "Could not send reply: $msg")
      } finally {
        pending.finish()
      }
    }.start()
  }
}
