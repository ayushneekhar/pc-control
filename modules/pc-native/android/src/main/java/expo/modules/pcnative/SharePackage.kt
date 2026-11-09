package expo.modules.pcnative

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import expo.modules.core.interfaces.Package
import expo.modules.core.interfaces.ReactActivityLifecycleListener

/**
 * "Share → PC Control" (text, e.g. a YouTube link from the YouTube app): the activity's ACTION_SEND intent is kept
 * here until JS takes it (PcNative.takeSharedText(), on start and whenever the app becomes active) and plays it
 * on the laptop speakers. The intent is consumed, so a later recreation of the activity doesn't replay it.
 */
object SharedText {
  @Volatile private var pending: String? = null

  fun from(intent: Intent?) {
    if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.take(2000)
    if (!text.isNullOrEmpty()) pending = text
    intent.action = null
    intent.removeExtra(Intent.EXTRA_TEXT)
  }

  fun take(): String? = pending.also { pending = null }
}

class SharePackage : Package {
  override fun createReactActivityLifecycleListeners(activityContext: Context): List<ReactActivityLifecycleListener> =
    listOf(object : ReactActivityLifecycleListener {
      override fun onCreate(activity: Activity, savedInstanceState: Bundle?) {
        if (savedInstanceState == null) SharedText.from(activity.intent)
      }
      override fun onNewIntent(intent: Intent): Boolean {
        SharedText.from(intent)
        return false // let React Native see it too (deep links)
      }
    })
}
