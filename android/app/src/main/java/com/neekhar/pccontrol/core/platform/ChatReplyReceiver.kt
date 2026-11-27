package com.neekhar.pccontrol.core.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
private data class InlineChatSendBody(
  val text: String,
  @SerialName("client_id") val clientId: String,
)

/**
 * Handles inline "Reply" actions from Android chat notifications ([RemoteInput]),
 * posting directly to `/pc/api/chat/send` with the stored chat bearer token even on a cold wakeup.
 */
class ChatReplyReceiver : BroadcastReceiver() {
  companion object {
    const val ACTION_CHAT_REPLY = "com.neekhar.pccontrol.ACTION_CHAT_REPLY"
    const val LEGACY_ACTION_CHAT_REPLY = "expo.modules.pcnative.ACTION_CHAT_REPLY"
    const val KEY_TEXT_REPLY = "key_chat_reply"
    const val EXTRA_NOTIF_TAG = "notif_tag"
    const val EXTRA_NOTIF_ID = "notif_id"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_CHAT_REPLY && intent.action != LEGACY_ACTION_CHAT_REPLY) return
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

    receiverScope.launch {
      try {
        val baseUrl = PlatformPrefs.chatBaseUrl(appCtx).trimEnd('/')
        val token = PlatformPrefs.chatToken(appCtx)
        val payload = PlatformHttp.json.encodeToString(
          InlineChatSendBody.serializer(),
          InlineChatSendBody(
            text = replyText,
            clientId = "notif-${UUID.randomUUID()}",
          ),
        )
        val client = PlatformHttp.defaultClient.newBuilder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(15, TimeUnit.SECONDS)
          .build()
        val reqBuilder = Request.Builder()
          .url("$baseUrl/pc/api/chat/send")
          .post(payload.toRequestBody(JSON_MEDIA))
          .header("Accept", "application/json")
        if (token.isNotEmpty()) {
          reqBuilder.header("Authorization", "Bearer $token")
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
          if (resp.isSuccessful) {
            PcNotifier.clearChatNotifications(appCtx)
            ChatForegroundState.notifyPush("reply", "You", replyText)
          } else {
            PcNotifier.showChatReplyError(
              appCtx,
              notifTag,
              notifId,
              "Could not send reply (HTTP ${resp.code})",
            )
          }
        }
      } catch (e: Exception) {
        val msg = e.message?.take(120)?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        PcNotifier.showChatReplyError(appCtx, notifTag, notifId, "Could not send reply: $msg")
      } finally {
        pending.finish()
      }
    }
  }
}
