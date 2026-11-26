package com.neekhar.pccontrol.feature.home

import com.neekhar.pccontrol.feature.system.HealthProblem
import com.neekhar.pccontrol.feature.system.VpnState

enum class HomeAttentionTone {
    WARN,
    BAD,
}

enum class HomeAttentionAction {
    UNLOCK_KEYRING,
    OPEN_SERVICES,
    OPEN_SETTINGS,
}

data class HomeAttentionItem(
    val label: String,
    val tone: HomeAttentionTone,
    val action: HomeAttentionAction,
)

data class HomeAttentionSummary(
    val items: List<HomeAttentionItem>,
    val summaryText: String,
    val tone: HomeAttentionTone,
    val primaryAction: HomeAttentionAction,
)

fun formatHomeTitle(host: String?): String {
    val raw = host?.trim().orEmpty()
    if (raw.isEmpty()) return "PC Control"
    return PcFormatters.formatShortHost(raw).ifBlank { "PC Control" }
}

fun formatHomeUptimeSubtitle(uptimeSeconds: Double?): String? {
    if (uptimeSeconds == null || !uptimeSeconds.isFinite() || uptimeSeconds <= 0.0) return null
    return "up ${PcFormatters.formatUptimeShort(uptimeSeconds)}"
}

/**
 * Surfaces only genuine exceptions on Home (locked keyring, disconnected VPN, or health problems).
 * Returns null when everything is healthy so no always-on status row clutters the screen.
 */
fun buildHomeAttentionSummary(
    keyring: KeyringStatus?,
    vpn: VpnState?,
    problems: List<HealthProblem>,
): HomeAttentionSummary? {
    val items = mutableListOf<HomeAttentionItem>()

    if (keyring?.locked == true) {
        items.add(
            HomeAttentionItem(
                label = "Keyring locked",
                tone = HomeAttentionTone.WARN,
                action = HomeAttentionAction.UNLOCK_KEYRING,
            ),
        )
    }

    val vpnDown = vpn != null && vpn.enabled && !vpn.tunnelUp
    if (vpnDown) {
        items.add(
            HomeAttentionItem(
                label = "VPN disconnected",
                tone = HomeAttentionTone.WARN,
                action = HomeAttentionAction.OPEN_SETTINGS,
            ),
        )
    }

    val remainingProblems = if (vpnDown) {
        problems.filterNot { it.kind == "vpn" }
    } else {
        problems
    }

    if (remainingProblems.isNotEmpty()) {
        val hasBad = remainingProblems.any { it.severity == "bad" }
        val tone = if (hasBad) HomeAttentionTone.BAD else HomeAttentionTone.WARN
        val label = when {
            remainingProblems.size == 1 -> {
                remainingProblems.first().title.takeIf { it.isNotBlank() } ?: "1 service issue"
            }
            remainingProblems.all { it.kind == "service" } -> {
                "${remainingProblems.size} service issues"
            }
            else -> {
                "${remainingProblems.size} issues"
            }
        }
        items.add(
            HomeAttentionItem(
                label = label,
                tone = tone,
                action = HomeAttentionAction.OPEN_SERVICES,
            ),
        )
    }

    if (items.isEmpty()) return null

    val worstTone = if (items.any { it.tone == HomeAttentionTone.BAD }) {
        HomeAttentionTone.BAD
    } else {
        HomeAttentionTone.WARN
    }
    val primaryAction = items.firstOrNull { it.tone == HomeAttentionTone.BAD }?.action
        ?: items.first().action

    return HomeAttentionSummary(
        items = items,
        summaryText = items.joinToString(" · ") { it.label },
        tone = worstTone,
        primaryAction = primaryAction,
    )
}
