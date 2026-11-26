package com.neekhar.pccontrol

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neekhar.pccontrol.core.theme.PcControlTheme
import com.neekhar.pccontrol.core.theme.PcTheme
import com.neekhar.pccontrol.core.theme.ThemeMode
import com.neekhar.pccontrol.navigation.DeepLinkParser
import com.neekhar.pccontrol.navigation.DeepLinkTarget
import com.neekhar.pccontrol.navigation.PcAppShell
import com.neekhar.pccontrol.navigation.SharedContent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single-activity Compose host extending [FragmentActivity] so AndroidX BiometricPrompt
 * works directly from any feature screen.
 */
class MainActivity : FragmentActivity() {

    private val pendingDeepLink = MutableStateFlow<DeepLinkTarget?>(null)
    private val pendingShare = MutableStateFlow<SharedContent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeIntent(intent)

        val app = application as PcControlApp

        setContent {
            val themeMode by app.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val deepLink by pendingDeepLink.collectAsStateWithLifecycle()
            val share by pendingShare.collectAsStateWithLifecycle()

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
                    pendingDeepLink = deepLink,
                    onDeepLinkConsumed = { pendingDeepLink.value = null },
                    pendingShare = share,
                    onShareConsumed = { pendingShare.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
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
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    DeepLinkParser.parseSharedText(text)?.let { content ->
                        pendingShare.value = content
                    }
                }
            }
        }
    }
}
