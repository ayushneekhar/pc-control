package expo.modules.pcnative

import android.app.Application
import android.content.Context
import expo.modules.core.interfaces.ApplicationLifecycleListener
import expo.modules.core.interfaces.Package

/** Sets the cams player's WebRTC options (CamsWebrtc) at app start, before React Native creates WebRTCModule. */
class CamsPackage : Package {
  override fun createApplicationLifecycleListeners(context: Context): List<ApplicationLifecycleListener> =
    listOf(object : ApplicationLifecycleListener {
      override fun onCreate(application: Application) = CamsWebrtc.configure(application)
    })
}
