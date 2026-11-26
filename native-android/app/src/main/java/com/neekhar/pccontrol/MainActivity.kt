package com.neekhar.pccontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neekhar.pccontrol.core.platform.ShareIntentParser
import com.neekhar.pccontrol.core.platform.SharePayload
import com.neekhar.pccontrol.core.theme.PcControlTheme
import com.neekhar.pccontrol.core.theme.PcTheme
import com.neekhar.pccontrol.core.theme.ThemeMode
import com.neekhar.pccontrol.navigation.DeepLinkParser
import com.neekhar.pccontrol.navigation.DeepLinkTarget
import com.neekhar.pccontrol.navigation.PcAppShell
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single-activity Compose host extending [FragmentActivity] so AndroidX BiometricPrompt
 * works directly from any feature screen. Supports PiP for Cat Cams, deep links
 * (`pccontrol://...`, `https://app.t3.codes/threads/...`), and `ACTION_SEND` /
 * `ACTION_SEND_MULTIPLE` share intents.
 */
class MainActivity : FragmentActivity() {

    private val pendingDeepLink = MutableStateFlow<DeepLinkTarget?>(null)
    private val pendingShare = MutableStateFlow<SharePayload?>(null)
    private val inPipMode = MutableStateFlow(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            (application as? PcControlApp)?.container?.startBackgroundTasks()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeIntent(intent)
        maybeRequestNotificationPermission()

        val app = application as PcControlApp
        val container = app.container

        setContent {
            val themeMode by container.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val deepLink by pendingDeepLink.collectAsStateWithLifecycle()
            val share by pendingShare.collectAsStateWithLifecycle()
            val isPip by inPipMode.collectAsStateWithLifecycle()

            PcControlTheme(themeMode = themeMode) {
                val isDark = PcTheme.colors.isDark
                SideEffect {
                    val barStyle = if (isDark) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    }
                    enableEdgeToEdge(
                        statusBarStyle = barStyle,
                        navigationBarStyle = barStyle,
                    )
                }

                PcAppShell(
                    container = container,
                    activityProvider = { if (!isFinishing && !isDestroyed) this@MainActivity else null },
                    pendingDeepLink = deepLink,
                    onDeepLinkConsumed = { pendingDeepLink.value = null },
                    pendingShare = share,
                    onShareConsumed = { pendingShare.value = null },
                    isInPictureInPictureMode = isPip,
                    onOpenExternalUrl = ::openExternalUrl,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipMode.value = isInPictureInPictureMode
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val raw = intent.dataString ?: return
                DeepLinkParser.parseUri(raw)?.let { target ->
                    pendingDeepLink.value = target
                }
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                ShareIntentParser.parseIntent(intent, context = this, consume = true)?.let { payload ->
                    pendingShare.value = payload
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val status = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (status != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openExternalUrl(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val launchedCustomTab = runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(this, uri)
            true
        }.getOrDefault(false)

        if (!launchedCustomTab) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }
    }
}
