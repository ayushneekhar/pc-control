package expo.modules.pcnative

import android.app.Application
import android.content.Context
import expo.modules.core.interfaces.ApplicationLifecycleListener
import expo.modules.core.interfaces.Package

/**
 * App start: install the crash reporter and pick up the Cat cams crash marker. Nothing WebRTC happens here any more:
 * the cams options are applied by CamsWebrtc.prepare() right before the Cams screen loads react-native-webrtc.
 */
class CamsPackage : Package {
  override fun createApplicationLifecycleListeners(context: Context): List<ApplicationLifecycleListener> =
    listOf(object : ApplicationLifecycleListener {
      override fun onCreate(application: Application) {
        CrashReporter.install(application)
        CamsWebrtc.onAppStart(application)
      }
    })
}
