package com.neekhar.pccontrol.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFormattersTest {

    @Test
    fun connectionHostSummary_prefersServerInfoAndFallsBackToUrlHost() {
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
                savedServerUrl = "https://hp-pavilion.tail1234.ts.net",
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
}
