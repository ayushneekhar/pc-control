package com.neekhar.pccontrol.feature.home

import com.neekhar.pccontrol.feature.system.HealthProblem
import com.neekhar.pccontrol.feature.system.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeAttentionTest {

    @Test
    fun `formatHomeTitle strips prefix and falls back to PC Control`() {
        assertEquals("hp-pavilion", formatHomeTitle("ayushlive-HP-Pavilion"))
        assertEquals("workstation", formatHomeTitle("workstation"))
        assertEquals("PC Control", formatHomeTitle(null))
        assertEquals("PC Control", formatHomeTitle(""))
        assertEquals("PC Control", formatHomeTitle("   "))
        assertEquals("PC Control", formatHomeTitle("ayushlive-"))
    }

    @Test
    fun `formatHomeUptimeSubtitle formats positive uptime and hides invalid values`() {
        assertNull(formatHomeUptimeSubtitle(null))
        assertNull(formatHomeUptimeSubtitle(0.0))
        assertNull(formatHomeUptimeSubtitle(-10.0))
        assertNull(formatHomeUptimeSubtitle(Double.NaN))
        assertEquals("up 4 h", formatHomeUptimeSubtitle(4 * 3600.0))
        assertEquals("up 1 day", formatHomeUptimeSubtitle(86_400.0))
        assertEquals("up 12 days", formatHomeUptimeSubtitle(12 * 86_400.0))
    }

    @Test
    fun `buildHomeAttentionSummary returns null when healthy`() {
        assertNull(
            buildHomeAttentionSummary(
                keyring = KeyringStatus(locked = false),
                vpn = VpnState(enabled = true, tunnelUp = true),
                problems = emptyList(),
            ),
        )
        assertNull(
            buildHomeAttentionSummary(
                keyring = KeyringStatus(locked = false),
                vpn = VpnState(enabled = false, tunnelUp = false),
                problems = emptyList(),
            ),
        )
    }

    @Test
    fun `buildHomeAttentionSummary surfaces locked keyring`() {
        val summary = buildHomeAttentionSummary(
            keyring = KeyringStatus(locked = true),
            vpn = VpnState(enabled = true, tunnelUp = true),
            problems = emptyList(),
        )
        assertEquals("Keyring locked", summary?.summaryText)
        assertEquals(HomeAttentionTone.WARN, summary?.tone)
        assertEquals(HomeAttentionAction.UNLOCK_KEYRING, summary?.primaryAction)
    }

    @Test
    fun `buildHomeAttentionSummary surfaces disconnected VPN once without duplicating vpn problem`() {
        val summary = buildHomeAttentionSummary(
            keyring = KeyringStatus(locked = false),
            vpn = VpnState(enabled = true, tunnelUp = false),
            problems = listOf(
                HealthProblem(kind = "vpn", severity = "warn", title = "Torrent VPN is down"),
            ),
        )
        assertEquals("VPN disconnected", summary?.summaryText)
        assertEquals(1, summary?.items?.size)
        assertEquals(HomeAttentionTone.WARN, summary?.tone)
        assertEquals(HomeAttentionAction.OPEN_SETTINGS, summary?.primaryAction)
    }

    @Test
    fun `buildHomeAttentionSummary combines multiple exceptions and prioritizes bad severity`() {
        val summary = buildHomeAttentionSummary(
            keyring = KeyringStatus(locked = true),
            vpn = VpnState(enabled = true, tunnelUp = false),
            problems = listOf(
                HealthProblem(kind = "vpn", severity = "warn", title = "Torrent VPN is down"),
                HealthProblem(kind = "service", severity = "bad", title = "jellyfin failed", unit = "jellyfin.service"),
                HealthProblem(kind = "service", severity = "bad", title = "sonarr failed", unit = "sonarr.service"),
            ),
        )
        assertEquals("Keyring locked · VPN disconnected · 2 service issues", summary?.summaryText)
        assertEquals(HomeAttentionTone.BAD, summary?.tone)
        assertEquals(HomeAttentionAction.OPEN_SERVICES, summary?.primaryAction)
    }
}
