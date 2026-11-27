package com.neekhar.pccontrol.feature.settings

import com.neekhar.pccontrol.core.platform.ApkRelease
import com.neekhar.pccontrol.core.platform.ApkUpdateState
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFormattersTest {

    @Test
    fun connectionHostSummary_prefersServerInfoAndStripsUserPrefixToMatchHome() {
        assertEquals(
            "hp-pavilion",
            connectionHostSummary(
                serverInfo = ServerInfo(host = "ayushlive-HP-Pavilion"),
                savedServerUrl = "https://other.tail1234.ts.net",
            ),
        )
        assertEquals(
            "hp-pavilion",
            connectionHostSummary(
                serverInfo = ServerInfo(host = "hp-pavilion"),
                savedServerUrl = "https://other.tail1234.ts.net",
            ),
        )
        assertEquals(
            "hp-pavilion",
            connectionHostSummary(
                serverInfo = null,
                savedServerUrl = "https://ayushlive-hp-pavilion.tail1234.ts.net",
            ),
        )
        assertEquals(
            "192.168.1.42",
            connectionHostSummary(
                serverInfo = null,
                savedServerUrl = "http://192.168.1.42:8080",
            ),
        )
        assertEquals(
            "Not set",
            connectionHostSummary(serverInfo = null, savedServerUrl = ""),
        )
    }

    @Test
    fun keyringStatusSummary_producesSingleNonDuplicatedStatusLine() {
        assertEquals("Checking…", keyringStatusSummary(status = null, hasSaved = false))
        assertEquals(
            "Locked · fingerprint saved",
            keyringStatusSummary(status = KeyringStatus(locked = true, rdp = "inactive"), hasSaved = true),
        )
        assertEquals(
            "Locked · RDP inactive",
            keyringStatusSummary(status = KeyringStatus(locked = true, rdp = "inactive"), hasSaved = false),
        )
        assertEquals(
            "Unlocked · RDP active · saved on phone",
            keyringStatusSummary(status = KeyringStatus(locked = false, rdp = "active"), hasSaved = true),
        )
        assertEquals(
            "Unlocked · RDP active",
            keyringStatusSummary(status = KeyringStatus(locked = false, rdp = "active"), hasSaved = false),
        )
    }

    @Test
    fun notifRoutingSummary_summarizesActiveChannelsAndCategories() {
        assertEquals("Loading…", notifRoutingSummary(null))

        val muted = NotifSettings(
            masters = mapOf("telegram" to false, "app" to false),
            categories = listOf(NotifCategory(id = "root", label = "Root", telegram = true, app = true)),
        )
        assertEquals("Muted", notifRoutingSummary(muted))

        val both = NotifSettings(
            masters = mapOf("telegram" to true, "app" to true),
            categories = listOf(
                NotifCategory(id = "root", label = "Root", telegram = true, app = true),
                NotifCategory(id = "cats", label = "Cats", telegram = false, app = false),
                NotifCategory(id = "chat", label = "Chat", telegram = false, app = true, channels = listOf("app")),
            ),
        )
        assertEquals("Telegram · App · 2/3 categories", notifRoutingSummary(both))
    }

    @Test
    fun formatApkDownloadSubtitle_formatsPercentAndMegabytes() {
        val release = ApkRelease(versionName = "2.1.1", versionCode = 28)
        val totalBytes = (24.2 * 1024.0 * 1024.0).toLong()
        val downloadedBytes = (10.2 * 1024.0 * 1024.0).toLong()
        val state = ApkUpdateState.Downloading(
            release = release,
            bytesDownloaded = downloadedBytes,
            totalBytes = totalBytes,
            progress = 0.42f,
        )
        assertEquals("Downloading v2.1.1 · 42% (10.2 / 24.2 MB)", formatApkDownloadSubtitle(state))

        val unknownTotal = ApkUpdateState.Downloading(
            release = ApkRelease(versionName = "v2.1.1", versionCode = 28),
            bytesDownloaded = (5.5 * 1024.0 * 1024.0).toLong(),
            totalBytes = 0L,
            progress = 0f,
        )
        assertEquals("Downloading v2.1.1 · 5.5 MB", formatApkDownloadSubtitle(unknownTotal))

        val starting = ApkUpdateState.Downloading(
            release = ApkRelease(versionName = "latest", versionCode = 28),
            bytesDownloaded = 0L,
            totalBytes = 0L,
            progress = 0f,
        )
        assertEquals("Downloading update…", formatApkDownloadSubtitle(starting))
    }

    @Test
    fun formatApkReadySubtitle_formatsVersionCleanly() {
        assertEquals(
            "v2.1.1 ready to install",
            formatApkReadySubtitle(ApkRelease(versionName = "2.1.1", versionCode = 28)),
        )
        assertEquals(
            "v2.1.1 ready to install",
            formatApkReadySubtitle(ApkRelease(versionName = "v2.1.1", versionCode = 28)),
        )
        assertEquals(
            "Update ready to install",
            formatApkReadySubtitle(ApkRelease(versionName = "latest", versionCode = 28)),
        )
    }
}
