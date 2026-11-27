package com.neekhar.pccontrol.feature.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentViewRulesTest {

    @Test
    fun splitUrlHandlesSchemePortQueryAndDropsFragments() {
        assertEquals(
            SplitUrlResult("example.com:8080", "/docs/api?v=2"),
            AgentViewRules.splitUrl("https://example.com:8080/docs/api?v=2#section"),
        )
        assertEquals(
            SplitUrlResult("example.com", "/"),
            AgentViewRules.splitUrl("https://example.com"),
        )
        assertEquals(
            SplitUrlResult("news.ycombinator.com", "/item?id=1"),
            AgentViewRules.splitUrl("news.ycombinator.com/item?id=1"),
        )
    }

    @Test
    fun buildExactAndSiteMatchesRoundTripWithUrlFromMatch() {
        val url = "https://example.com/a/b?x=1&y=2"
        val exact = AgentViewRules.buildExactMatch(url)
        assertEquals("regex", exact.matchType)
        assertEquals(url, AgentViewRules.urlFromMatch(exact.match, exact.matchType))
        assertEquals(MatchMode.EXACT, AgentViewRules.matchModeFor(exact.match, url))

        val site = AgentViewRules.buildSiteMatch(url)
        assertEquals("*://example.com/*", site.match)
        assertEquals("glob", site.matchType)
        assertEquals("https://example.com/", AgentViewRules.urlFromMatch(site.match, site.matchType))
        assertEquals(MatchMode.SITE, AgentViewRules.matchModeFor(site.match, url))
    }

    @Test
    fun buildRulePayloadIncludesOnlyActionSpecificFields() {
        val payload = AgentViewRules.buildRulePayload(
            RuleDraft(
                action = "replace_text",
                name = "",
                matchMode = MatchMode.SITE,
                url = "https://example.com/page",
                replacements = listOf(
                    Replacement(find = "foo", replace = "bar", regex = false),
                    Replacement(find = "", replace = "ignored", regex = false),
                ),
                selectors = listOf(".ad"),
                html = "<p>ignored</p>",
            ),
        )
        assertEquals("example.com/page", payload.name)
        assertEquals("*://example.com/*", payload.match)
        assertEquals("glob", payload.matchType)
        assertEquals(1, payload.replacements?.size)
        assertNull(payload.html)
        assertNull(payload.selectors)
        assertNull(payload.blockMode)
    }
}
