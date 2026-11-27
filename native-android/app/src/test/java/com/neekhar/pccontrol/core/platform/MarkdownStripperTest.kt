package com.neekhar.pccontrol.core.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownStripperTest {

    @Test
    fun stripsBoldItalicStrikeAndInlineCode() {
        val raw = "**Fix Pass 5** is __rolling__ with *italic*, _subtle_, ~~old~~, and `PcScaffold`."
        assertEquals(
            "Fix Pass 5 is rolling with italic, subtle, old, and PcScaffold.",
            MarkdownStripper.stripForNotification(raw),
        )
    }

    @Test
    fun preservesBoldWrappedInlineCodeAndSnakeCase() {
        val raw = "Thread — **`antigravity-2`** · **`high`** · check `my_snake_var` and foo_bar_baz"
        assertEquals(
            "Thread — antigravity-2 · high · check my_snake_var and foo_bar_baz",
            MarkdownStripper.stripForNotification(raw),
        )
    }

    @Test
    fun replacesMarkdownAndT3ThreadLinksWithLabel() {
        val raw = "See [Run #45945125622](https://github.com/ayushneekhar/pc-control/actions/runs/45945125622) and " +
            "[Native Android Fix Pass 5](t3-thread://v2/6d4b6899-838e-4a89-bb41-17ea4f5896cb/mcp:9b8b451d-cb2c-45c3-9edc-51d1293ae840) " +
            "plus [**bold link**](https://example.com/wiki/Test_(page))."
        assertEquals(
            "See Run #45945125622 and Native Android Fix Pass 5 plus bold link.",
            MarkdownStripper.stripForNotification(raw),
        )
    }

    @Test
    fun stripsHeadingsBlockquotesAndConvertsBullets() {
        val raw = """
            ### Summary
            > Important note
            - First item
            * Second item
              - Nested bullet
        """.trimIndent()

        assertEquals(
            """
            Summary
            Important note
            • First item
            • Second item
              • Nested bullet
            """.trimIndent(),
            MarkdownStripper.stripForNotification(raw),
        )
    }

    @Test
    fun stripsFencedCodeBlocksAndPreservesInnerCodeVerbatim() {
        val raw = """
            Run this:
            ```kotlin
            val total = 2 * 3 * 4
            val snake_name = "**not bold**"
            ```
            Done!
        """.trimIndent()

        assertEquals(
            """
            Run this:
              val total = 2 * 3 * 4
              val snake_name = "**not bold**"
            Done!
            """.trimIndent(),
            MarkdownStripper.stripForNotification(raw),
        )
    }

    @Test
    fun handlesUnclosedTruncatedBacktickAndFenceGracefully() {
        val raw = "Checking `git status --short"
        assertEquals("Checking git status --short", MarkdownStripper.stripForNotification(raw))

        val truncatedFence = "Output:\n```sh\necho ok"
        assertEquals("Output:\n  echo ok", MarkdownStripper.stripForNotification(truncatedFence))
    }

    @Test
    fun collapsesExcessiveNewlinesAndTrims() {
        val raw = "\n\n# Title\n\n\n\nParagraph 1\n\n\n\n\nParagraph 2\n\n"
        assertEquals(
            "Title\n\nParagraph 1\n\nParagraph 2",
            MarkdownStripper.stripForNotification(raw),
        )
    }

    @Test
    fun leavesMathExpressionsUntouched() {
        assertEquals(
            "Compute 2 * 3 * 4 = 24",
            MarkdownStripper.stripForNotification("Compute 2 * 3 * 4 = 24"),
        )
    }

    @Test
    fun stripToSingleLineCompactsMultilineMarkdownForPreviewCards() {
        val raw = "**Fix Pass 5 is rolling**\n\n- **Thread:** [Fix Pass 5](t3-thread://v2/env/id) — `52d8580`"
        assertEquals(
            "Fix Pass 5 is rolling • Thread: Fix Pass 5 — 52d8580",
            MarkdownStripper.stripToSingleLine(raw),
        )
    }
}
