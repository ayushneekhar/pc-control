package com.neekhar.pccontrol.core.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChatPushEvent(
  val id: String,
  val title: String,
  val body: String,
  val receivedAtMs: Long = System.currentTimeMillis(),
)

/**
 * In-process thread-safe tracker for whether the Chat screen is currently visible in the foreground,
 * enabling WhatsApp-style notification suppression when the user is actively reading Chat while
 * still immediately waking the Chat UI via [pushEvents] (or [onPush]).
 */
object ChatForegroundState {
  private val _visible = MutableStateFlow(false)
  val isVisibleFlow: StateFlow<Boolean> = _visible.asStateFlow()

  @Volatile
  var visible: Boolean = false
    set(value) {
      field = value
      _visible.value = value
    }

  private val _pushEvents = MutableSharedFlow<ChatPushEvent>(extraBufferCapacity = 32)
  val pushEvents: SharedFlow<ChatPushEvent> = _pushEvents.asSharedFlow()

  @Volatile
  var onPush: ((id: String, title: String, body: String) -> Unit)? = null

  fun notifyPush(id: String, title: String, body: String) {
    _pushEvents.tryEmit(ChatPushEvent(id = id, title = title, body = body))
    try {
      onPush?.invoke(id, title, body)
    } catch (_: Exception) {
    }
  }
}
