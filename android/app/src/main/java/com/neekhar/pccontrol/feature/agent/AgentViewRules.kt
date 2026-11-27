package com.neekhar.pccontrol.feature.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
data class Replacement(
    val find: String = "",
    val replace: String = "",
    val regex: Boolean = false,
)

@Serializable
data class AgentRule(
    val id: String = "",
    val enabled: Boolean = true,
    val name: String = "",
    val match: String = "",
    val matchType: String = "glob", // "glob" | "regex"
    val action: String = "replace_page", // "replace_page" | "replace_text" | "hide" | "block"
    val html: String? = null,
    val replacements: List<Replacement>? = null,
    val selectors: List<String>? = null,
    val blockMode: String? = null, // "404" | "reset"
)

@Serializable
data class AgentViewState(
    val enabled: Boolean = true,
    val rules: List<AgentRule> = emptyList(),
)

@Serializable
data class AgentViewMasterState(
    val enabled: Boolean = false,
    val state: String = "off",
    @SerialName("web_reachable") val webReachable: Boolean = false,
    val services: Map<String, String>? = null,
)

@Serializable
data class AgentViewToggleRequest(
    val enabled: Boolean,
)

@Serializable
data class AgentUrl(
    val url: String,
    val host: String = "",
    @SerialName("last_seen") val lastSeen: String = "",
    val count: Int = 0,
    @SerialName("rule_id") val ruleId: String? = null,
)

@Serializable
data class OriginalMeta(
    val available: Boolean? = null,
    @SerialName("captured_at") val capturedAt: String? = null,
    val truncated: Boolean? = null,
    val size: Long? = null,
)

@Serializable
data class OriginalHtml(
    val url: String = "",
    val html: String = "",
    @SerialName("captured_at") val capturedAt: String? = null,
    val size: Long? = null,
    val truncated: Boolean? = null,
)

@Serializable
data class FromUrlRequest(
    val url: String,
    val action: String,
)

@Serializable
data class FromUrlResponse(
    val rule: AgentRule,
    val original: OriginalMeta? = null,
)

@Serializable
data class ReorderRulesRequest(
    val ids: List<String>,
)

@Serializable
data class RuleUpdatePayload(
    val name: String? = null,
    val match: String? = null,
    val matchType: String? = null,
    val action: String? = null,
    val enabled: Boolean? = null,
    val html: String? = null,
    val replacements: List<Replacement>? = null,
    val selectors: List<String>? = null,
    val blockMode: String? = null,
)

enum class MatchMode(val id: String, val label: String) {
    EXACT("exact", "Exact URL"),
    SITE("site", "Whole site"),
    CUSTOM("custom", "Custom"),
}

data class SplitUrlResult(val host: String, val path: String)

data class BuiltMatch(val match: String, val matchType: String)

data class RuleDraft(
    val action: String,
    val name: String = "",
    val matchMode: MatchMode = MatchMode.EXACT,
    val url: String? = null,
    val customMatch: String = "",
    val customMatchType: String = "glob",
    val html: String = "",
    val replacements: List<Replacement> = emptyList(),
    val selectors: List<String> = emptyList(),
    val blockMode: String = "404",
    val enabled: Boolean = true,
)

/**
 * Pure AgentView rule & URL helpers, ported from src/lib/agentviewRules.ts.
 */
object AgentViewRules {
    private val WITH_SCHEME_RE = Regex("""^[a-z][a-z0-9+.-]*://([^/?#]+)([^#]*)""", RegexOption.IGNORE_CASE)
    private val BARE_RE = Regex("""^([^/?#]+)([^#]*)""")
    private val REGEX_META_RE = Regex("""[.*+?^${'$'}()|\[\]\\]""")
    private val GLOB_SITE_RE = Regex("""^\*://([^/*]+)/\*$""")
    private val UNESCAPE_RE = Regex("""\\(.)""")
    private val HTTP_SCHEME_RE = Regex("""^https?://""", RegexOption.IGNORE_CASE)

    val ACTION_LABELS: Map<String, String> = mapOf(
        "replace_page" to "Show my page",
        "replace_text" to "Replace text",
        "hide" to "Hide elements",
        "block" to "Block",
    )

    fun actionLabel(action: String): String = ACTION_LABELS[action] ?: action

    private fun withPath(p: String): String = when {
        p.isEmpty() -> "/"
        p.startsWith("/") -> p
        else -> "/$p"
    }

    /** Host (with port, if any) and path+query for display. Fragments are dropped. */
    fun splitUrl(raw: String?): SplitUrlResult {
        val s = (raw ?: "").trim()
        val withScheme = WITH_SCHEME_RE.find(s)
        if (withScheme != null) {
            return SplitUrlResult(withScheme.groupValues[1], withPath(withScheme.groupValues[2]))
        }
        val bare = BARE_RE.find(s) ?: return SplitUrlResult("", "/")
        return SplitUrlResult(bare.groupValues[1], withPath(bare.groupValues[2]))
    }

    fun escapeRegex(s: String): String = REGEX_META_RE.replace(s) { "\\${it.value}" }

    fun buildExactMatch(url: String): BuiltMatch =
        BuiltMatch(match = "^${escapeRegex(url.trim())}$", matchType = "regex")

    fun buildSiteMatch(url: String): BuiltMatch {
        val host = splitUrl(url).host
        return BuiltMatch(
            match = if (host.isNotEmpty()) "*://$host/*" else "*://*/*",
            matchType = "glob",
        )
    }

    fun matchModeFor(match: String, url: String?): MatchMode {
        if (url.isNullOrBlank()) return MatchMode.CUSTOM
        if (match == buildExactMatch(url).match) return MatchMode.EXACT
        if (match == buildSiteMatch(url).match) return MatchMode.SITE
        return MatchMode.CUSTOM
    }

    fun urlFromMatch(match: String, matchType: String): String? {
        if (matchType == "glob") {
            val m = GLOB_SITE_RE.find(match) ?: return null
            return "https://${m.groupValues[1]}/"
        }
        if (match.startsWith("^") && match.endsWith("$") && match.length >= 2) {
            val raw = UNESCAPE_RE.replace(match.substring(1, match.length - 1)) { it.groupValues[1] }
            return if (HTTP_SCHEME_RE.containsMatchIn(raw)) raw else null
        }
        return null
    }

    fun defaultRuleName(url: String): String {
        val (host, path) = splitUrl(url)
        return if (host.isNotEmpty()) "$host$path" else "New rule"
    }

    fun buildRulePayload(draft: RuleDraft): RuleUpdatePayload {
        val url = draft.url
        val built = when {
            draft.matchMode == MatchMode.EXACT && !url.isNullOrBlank() -> buildExactMatch(url)
            draft.matchMode == MatchMode.SITE && !url.isNullOrBlank() -> buildSiteMatch(url)
            else -> BuiltMatch(match = draft.customMatch.trim(), matchType = draft.customMatchType)
        }
        val resolvedName = draft.name.trim().ifEmpty { defaultRuleName(url ?: "") }
        return RuleUpdatePayload(
            name = resolvedName,
            match = built.match,
            matchType = built.matchType,
            action = draft.action,
            enabled = draft.enabled,
            html = if (draft.action == "replace_page") draft.html else null,
            replacements = if (draft.action == "replace_text") draft.replacements.filter { it.find.isNotEmpty() } else null,
            selectors = if (draft.action == "hide") draft.selectors.map { it.trim() }.filter { it.isNotEmpty() } else null,
            blockMode = if (draft.action == "block") (if (draft.blockMode == "reset") "reset" else "404") else null,
        )
    }

    fun relativeTime(iso: String, nowMs: Long = System.currentTimeMillis()): String {
        val instant = FormatResetTime.parseIsoInstant(iso) ?: return ""
        val secs = max(0L, (nowMs - instant.toEpochMilli()) / 1000L)
        if (secs < 10L) return "just now"
        if (secs < 60L) return "${secs}s ago"
        val mins = secs / 60L
        if (mins < 60L) return "${mins}m ago"
        val hours = mins / 60L
        if (hours < 24L) return "${hours}h ago"
        val days = hours / 24L
        if (days < 30L) return "${days}d ago"
        return "${days / 30L}mo ago"
    }
}
