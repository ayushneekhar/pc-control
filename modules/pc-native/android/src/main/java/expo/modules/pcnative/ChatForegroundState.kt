package expo.modules.pcnative

/**
 * In-process thread-safe tracker for whether the Chat screen is currently visible in the foreground,
 * plus a push callback so PushService can immediately wake the JS Chat screen on incoming chat FCMs.
 */
object ChatForegroundState {
  @Volatile
  var visible: Boolean = false

  @Volatile
  var onPush: ((id: String, title: String, body: String) -> Unit)? = null

  fun notifyPush(id: String, title: String, body: String) {
    try {
      onPush?.invoke(id, title, body)
    } catch (_: Exception) {
    }
  }
}
