package com.neekhar.pccontrol.feature.chat

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogicTest {

    // ---------------------------------------------------------------- Optimistic send & deduplication

    @Test
    fun mergeChatMessages_deduplicatesOptimisticByClientIdAndDropsPairedAssistantPlaceholder() {
        val optimisticUser = ChatMessage(
            id = "opt-u-1",
            role = ChatRoles.USER,
            engine = ChatEngines.GEMINI,
            text = "hello",
            createdAt = 100.0,
            status = ChatStatuses.DONE,
            clientId = "cid-1",
            optimistic = true,
        )
        val optimisticAsst = ChatMessage(
            id = "opt-a-1",
            role = ChatRoles.ASSISTANT,
            engine = ChatEngines.GEMINI,
            text = "",
            createdAt = 100.001,
            status = ChatStatuses.PENDING,
            replyTo = JsonPrimitive("cid-1"),
            optimistic = true,
        )

        val before = ChatLogic.mergeChatMessages(listOf(optimisticUser, optimisticAsst), emptyList())
        assertEquals(2, before.size)

        val serverMsgs = listOf(
            ChatMessage(
                id = "srv-u-1",
                role = ChatRoles.USER,
                engine = ChatEngines.GEMINI,
                text = "hello",
                createdAt = 100.2,
                status = ChatStatuses.DONE,
                clientId = "cid-1",
            ),
            ChatMessage(
                id = "srv-a-1",
                role = ChatRoles.ASSISTANT,
                engine = ChatEngines.GEMINI,
                text = "world",
                createdAt = 100.5,
                status = ChatStatuses.DONE,
                replyTo = JsonPrimitive("srv-u-1"),
            ),
        )
        val after = ChatLogic.mergeChatMessages(listOf(optimisticUser, optimisticAsst), serverMsgs)
        assertEquals(listOf("srv-u-1", "srv-a-1"), after.map { it.id })
        assertFalse(after[0].optimistic)
    }

    @Test
    fun getChatMessageKey_staysStableAcrossOptimisticAndServerConfirmedUserMessages() {
        val optimistic = ChatMessage(
            id = "opt-u-c-123",
            role = ChatRoles.USER,
            engine = ChatEngines.CLAUDE,
            text = "hi",
            createdAt = 10.0,
            status = ChatStatuses.DONE,
            clientId = "c-123",
            optimistic = true,
        )
        val confirmed = optimistic.copy(id = "msg-server-99", optimistic = false)
        val assistant = ChatMessage(
            id = "msg-asst-100",
            role = ChatRoles.ASSISTANT,
            engine = ChatEngines.CLAUDE,
            text = "hello",
            createdAt = 11.0,
            status = ChatStatuses.DONE,
            clientId = "c-123",
        )
        val legacyUser = ChatMessage(
            id = "msg-user-legacy",
            role = ChatRoles.USER,
            engine = ChatEngines.CLAUDE,
            text = "legacy",
            createdAt = 1.0,
            status = ChatStatuses.DONE,
        )

        assertEquals("u:c-123", ChatLogic.getChatMessageKey(optimistic))
        assertEquals("u:c-123", ChatLogic.getChatMessageKey(confirmed))
        assertEquals("msg-asst-100", ChatLogic.getChatMessageKey(assistant))
        assertEquals("msg-user-legacy", ChatLogic.getChatMessageKey(legacyUser))
    }

    @Test
    fun reducer_optimisticSendAndSuccessAndFailureRollback() {
        val initial = ChatUiState(
            engine = ChatEngines.GEMINI,
            draft = "check disk space",
            loadingInitial = false,
            running = false,
        )
        val optUser = ChatMessage(
            id = "opt-u-c1",
            role = ChatRoles.USER,
            engine = ChatEngines.GEMINI,
            text = "check disk space",
            createdAt = 50.0,
            status = ChatStatuses.DONE,
            clientId = "c1",
            optimistic = true,
            queued = false,
        )

        val sending = ChatReducer.onOptimisticSend(initial, optUser, clearComposer = true)
        assertTrue(sending.sending)
        assertTrue(sending.running)
        assertEquals("", sending.draft)
        assertEquals(listOf("opt-u-c1"), sending.messages.map { it.id })
        assertEquals(2, sending.listItems.size)
        assertEquals(THINKING_INDICATOR_ID, sending.listItems.last().id)

        val failed = ChatReducer.onSendFailure(
            state = sending,
            optUserId = optUser.id,
            wasRunning = false,
            restoreComposer = true,
            restoreDraft = "check disk space",
            restoreAttachments = emptyList(),
            restoreEdit = null,
            restoreReply = null,
            error = "Timed out",
        )
        assertFalse(failed.sending)
        assertFalse(failed.running)
        assertEquals("check disk space", failed.draft)
        assertTrue(failed.messages.isEmpty())
        assertEquals("Timed out", failed.error)

        val confirmed = ChatReducer.onSendSuccess(
            state = sending,
            optUserId = optUser.id,
            response = ChatSendResponse(
                ok = true,
                deduped = false,
                userMessage = optUser.copy(id = "srv-u-1", optimistic = false),
                assistantMessage = null,
                engine = ChatEngines.GEMINI,
                running = true,
            ),
        )
        assertFalse(confirmed.sending)
        assertTrue(confirmed.running)
        assertEquals(listOf("srv-u-1"), confirmed.messages.map { it.id })
    }

    // ---------------------------------------------------------------- Queued ordering & "Send now" steering

    @Test
    fun steeringLifecycle_optimisticSteerPreventsFlickerAndRollsBackCleanly() {
        val active = ChatMessage(
            id = "u1",
            role = ChatRoles.USER,
            engine = ChatEngines.CLAUDE,
            text = "first",
            createdAt = 10.0,
            status = ChatStatuses.DONE,
            queued = false,
        )
        val queued = ChatMessage(
            id = "u2",
            role = ChatRoles.USER,
            engine = ChatEngines.CLAUDE,
            text = "second",
            createdAt = 11.0,
            status = ChatStatuses.DONE,
            queued = true,
        )
        val thirdQueued = ChatMessage(
            id = "u3",
            role = ChatRoles.USER,
            engine = ChatEngines.CLAUDE,
            text = "third",
            createdAt = 12.0,
            status = ChatStatuses.DONE,
            queued = true,
        )

        assertTrue(ChatLogic.canSteerQueuedMessage(queued, running = true))
        assertFalse(ChatLogic.canSteerQueuedMessage(queued, running = false))
        assertFalse(ChatLogic.canSteerQueuedMessage(queued.copy(optimistic = true), running = true))
        assertFalse(ChatLogic.canSteerQueuedMessage(active, running = true))

        // Immediate optimistic transition on tapping "Send now"
        val afterTap = ChatLogic.applyOptimisticSteer(listOf(active, queued, thirdQueued), "u2")
        assertFalse(afterTap[1].queued)
        assertTrue(afterTap[1].steered)
        assertTrue(afterTap[2].queued)

        // In-flight steer set protects against polling/SSE snapshot flicker
        val inFlight = setOf("u2")
        val afterPoll = ChatLogic.preserveOptimisticSteers(
            ChatLogic.mergeChatMessages(afterTap, listOf(active, queued, thirdQueued)),
            inFlight,
        )
        assertFalse(afterPoll[1].queued)
        assertTrue(afterPoll[1].steered)
        assertTrue(afterPoll[2].queued)

        val afterSnapshot = ChatLogic.reconcileQueuedMessages(
            current = afterTap,
            queuedIds = listOf("u2", "u3"),
            running = true,
            optimisticSteeredIds = inFlight,
        )
        assertFalse(afterSnapshot[1].queued)
        assertTrue(afterSnapshot[2].queued)

        // Rollback when steer fails while still running restores queued=true
        val rolledBackRunning = ChatLogic.rollbackOptimisticSteer(afterTap, "u2", stillRunning = true)
        assertTrue(rolledBackRunning[1].queued)
        assertFalse(rolledBackRunning[1].steered)
        assertTrue(rolledBackRunning[2].queued)

        // Rollback when active turn finished clears stale queued flag
        val rolledBackIdle = ChatLogic.rollbackOptimisticSteer(afterTap, "u2", stillRunning = false)
        assertFalse(rolledBackIdle[1].queued)
        assertFalse(rolledBackIdle[1].steered)
    }

    @Test
    fun upsertChatMessage_preservesChronologicalQueueOrderAndRemovesSupersededPlaceholder() {
        val u1 = ChatMessage(id = "u1", role = ChatRoles.USER, text = "first", createdAt = 10.0, queued = false)
        val optU2 = ChatMessage(
            id = "opt-u-c2",
            role = ChatRoles.USER,
            text = "second",
            createdAt = 11.0,
            clientId = "c2",
            optimistic = true,
            queued = true,
        )
        val pendingA = ChatMessage(
            id = "a-pend",
            role = ChatRoles.ASSISTANT,
            text = "",
            createdAt = 12.0,
            status = ChatStatuses.PENDING,
            replyTo = JsonPrimitive("u2"),
        )
        val srvU2 = optU2.copy(id = "u2", optimistic = false, queued = true)

        val confirmed = ChatLogic.upsertChatMessage(listOf(u1, optU2, pendingA), srvU2)
        assertEquals(listOf("u1", "u2", "a-pend"), confirmed.map { it.id })
        assertFalse(confirmed[1].optimistic)

        val steered = ChatLogic.upsertChatMessage(
            current = confirmed,
            incoming = srvU2.copy(queued = false, steered = true),
            removedId = "a-pend",
        )
        assertEquals(listOf("u1", "u2"), steered.map { it.id })
        assertFalse(steered[1].queued)
        assertTrue(steered[1].steered)
    }

    // ---------------------------------------------------------------- SSE & Activity/Progress updates

    @Test
    fun parseChatSseBlocks_parsesSnapshotMessageProgressAndPreservesPartialTail() {
        val raw =
            ": ping\n\n" +
                "event: snapshot\ndata: {\"engine\":\"gemini\",\"running\":true,\"queued_ids\":[\"u2\"],\"ts\":100}\n\n" +
                "event: message\ndata: {\"message\":{\"id\":\"u2\",\"role\":\"user\",\"engine\":\"gemini\",\"text\":\"second\",\"created_at\":11,\"status\":\"done\",\"queued\":false,\"steered\":true},\"running\":true,\"steered\":true,\"ts\":101}\n\n" +
                "event: progress\ndata: {\"running\":true,\"progress\":{\"engine\":\"gemini\",\"started\":10,\"steps\":[{\"t\":11,\"text\":\"reading file\"}]},\"ts\":102}\n\n" +
                "event: message\ndata: {\"partial\":true"

        val parsed = ChatLogic.parseChatSseBlocks(raw)
        assertEquals(3, parsed.events.size)

        val snap = parsed.events[0] as ChatSseEvent.Snapshot
        assertEquals(ChatEngines.GEMINI, snap.engine)
        assertTrue(snap.running)
        assertEquals(listOf("u2"), snap.queuedIds)

        val msg = parsed.events[1] as ChatSseEvent.Message
        assertEquals(true, msg.steered)
        assertFalse(msg.message.queued)
        assertTrue(msg.message.steered)

        val prog = parsed.events[2] as ChatSseEvent.Progress
        assertTrue(prog.running)
        assertEquals("reading file", ChatLogic.currentProgressStepText(prog.progress))

        assertEquals("event: message\ndata: {\"partial\":true", parsed.rest)
    }

    @Test
    fun reducer_sseActivityProgressUpdatesAndUnreadCounterWhenScrolledUp() {
        val initial = ChatUiState(
            engine = ChatEngines.GEMINI,
            running = true,
            loadingInitial = false,
            unreadCount = 0,
        )

        val afterProgress = ChatReducer.onSseEvent(
            state = initial,
            event = ChatSseEvent.Progress(
                running = true,
                progress = ChatProgress(
                    engine = ChatEngines.GEMINI,
                    started = 100.0,
                    steps = listOf(
                        ChatProgressStep(101.0, "Reading ChatScreen.tsx"),
                        ChatProgressStep(102.0, "Running tests"),
                    ),
                ),
                ts = 102L,
            ),
            isNearBottom = false,
        )
        assertEquals("Running tests", ChatLogic.currentProgressStepText(afterProgress.activeProgress))
        assertEquals(2, afterProgress.activeProgress?.steps?.size)
        assertEquals(0, afterProgress.unreadCount)

        val afterReply = ChatReducer.onSseEvent(
            state = afterProgress,
            event = ChatSseEvent.Message(
                message = ChatMessage(
                    id = "a1",
                    role = ChatRoles.ASSISTANT,
                    engine = ChatEngines.GEMINI,
                    text = "All tests passed!",
                    createdAt = 105.0,
                    status = ChatStatuses.DONE,
                    progress = ChatProgress(
                        engine = ChatEngines.GEMINI,
                        started = 100.0,
                        durationS = 5.0,
                        stepCount = 2,
                        steps = listOf(ChatProgressStep(101.0, "Reading ChatScreen.tsx")),
                    ),
                ),
                running = false,
                engine = ChatEngines.GEMINI,
                progress = null,
                hasProgressField = true,
                steered = null,
                removedMessageId = null,
                ts = 105L,
            ),
            isNearBottom = false,
        )
        assertFalse(afterReply.running)
        assertNull(afterReply.activeProgress)
        assertEquals(1, afterReply.unreadCount)
        assertEquals("Worked for 5s · 2 steps", ChatLogic.formatProgressSummary(afterReply.messages.single().progress))
    }

    // ---------------------------------------------------------------- T3 & OG Link Extraction + Markdown

    @Test
    fun resolveT3ThreadUrl_andExtractT3ThreadLinks_handleCustomSchemeAndWebAppLinks() {
        assertEquals(
            "https://app.t3.codes/threads/env-123/thread-456",
            ChatLogic.resolveT3ThreadUrl("t3-thread://v2/env-123/thread-456"),
        )
        assertEquals(
            "https://app.t3.codes/threads/env%3A1/mcp%3A9a8fddd7-1c1a-4aab-a3ed-aed0696731e0",
            ChatLogic.resolveT3ThreadUrl("t3-thread://v2/env%3A1/mcp%3A9a8fddd7-1c1a-4aab-a3ed-aed0696731e0"),
        )
        assertNull(ChatLogic.resolveT3ThreadUrl("t3-thread://v2/only-one-segment"))
        assertNull(ChatLogic.resolveChatLinkUrl("javascript:alert(1)"))

        val links = ChatLogic.extractT3ThreadLinks(
            "Opened [Native Chat](t3-thread://v2/env-1/thr-2) and see https://app.t3.codes/threads/env-1/thr-3."
        )
        assertEquals(2, links.size)
        assertEquals("Native Chat" to "https://app.t3.codes/threads/env-1/thr-2", links[0])
        assertEquals("Open T3 thread" to "https://app.t3.codes/threads/env-1/thr-3", links[1])
    }

    @Test
    fun extractPreviewUrl_findsExternalHttpUrlAndSkipsCodeImagesAndInternalSchemes() {
        assertEquals(
            "https://docs.example.com/guide",
            ChatLogic.extractPreviewUrl("Check [Thread](t3-thread://v2/e/t) and [Docs](https://docs.example.com/guide)."),
        )
        assertEquals(
            "https://example.com/page",
            ChatLogic.extractPreviewUrl("Bare link: https://example.com/page, and https://second.example.com"),
        )
        assertNull(
            ChatLogic.extractPreviewUrl("```\nhttps://in-codeblock.example.com\n```\nInline `https://in-code.example.com`"),
        )
        assertNull(ChatLogic.extractPreviewUrl("![Wallpaper](https://cdn.example.com/wall.png)"))
        assertEquals(
            "https://unsplash.com/photos/abc",
            ChatLogic.extractPreviewUrl("![Wallpaper](https://cdn.example.com/wall.png)\nSource: [Unsplash](https://unsplash.com/photos/abc)"),
        )
    }

    @Test
    fun parseMessageSegments_extractsTablesCodeBlocksAndImagesCleanly() {
        val md = listOf(
            "Summary:",
            "",
            "| Service | Status | CPU |",
            "| :--- | :---: | ---: |",
            "| pc-api | up | 1.2% |",
            "",
            "```kotlin",
            "val x = 1",
            "```",
            "",
            "![AMOLED](https://cdn.example.com/wall.png)",
        ).joinToString("\n")

        val segs = ChatLogic.parseMessageSegments(md)
        assertEquals(4, segs.size)
        assertEquals(MessageSegment.Text("Summary:"), segs[0])

        val table = segs[1] as MessageSegment.Table
        assertEquals(listOf("Service", "Status", "CPU"), table.headers)
        assertEquals(listOf(TableAlignment.LEFT, TableAlignment.CENTER, TableAlignment.RIGHT), table.alignments)
        assertEquals(listOf(listOf("pc-api", "up", "1.2%")), table.rows)

        assertEquals(MessageSegment.CodeBlock("kotlin", "val x = 1"), segs[2])
        assertEquals(MessageSegment.Image("AMOLED", "https://cdn.example.com/wall.png"), segs[3])
    }

    @Test
    fun buildChatAttachmentSource_sendsBearerTokenOnlyToChatOrigin() {
        val customUrl = "https://agent.neekhar.dev/"
        val token = "tok-123"
        val defaultBase = "https://tailnet.ts.net/"

        val rel = ChatLogic.buildChatAttachmentSource("/pc/api/chat/file/0123456789abcdef", defaultBase, customUrl, token)
        assertEquals("https://agent.neekhar.dev/pc/api/chat/file/0123456789abcdef", rel.uri)
        assertEquals(mapOf("Authorization" to "Bearer tok-123"), rel.headers)

        val external = ChatLogic.buildChatAttachmentSource("https://cdn.example.com/photo.png", defaultBase, customUrl, token)
        assertEquals("https://cdn.example.com/photo.png", external.uri)
        assertTrue(external.headers.isEmpty())
    }

    @Test
    fun composerHelpers_formatModelEffortChipsAndListAutoFormat() {
        assertEquals("Neon · Med", ChatLogic.formatComposerChipLabel(ChatEngines.GEMINI, "gemini-4-neon", "Gemini 4 Neon", "medium"))
        assertEquals("Opus · High", ChatLogic.formatComposerChipLabel(ChatEngines.CLAUDE, "claude-opus-5-5", "Opus 5.5", "high"))

        val continued = ChatLogic.applyChatListAutoFormat("1. first", "1. first\n", 8, 8)
        assertTrue(continued.changed)
        assertEquals("1. first\n2. ", continued.text)
        assertEquals(12, continued.selectionStart)

        val disengaged = ChatLogic.applyChatListAutoFormat("1. a\n2. ", "1. a\n2. \n", 8, 8)
        assertTrue(disengaged.changed)
        assertEquals("1. a\n", disengaged.text)
        assertEquals(5, disengaged.selectionStart)
    }

    @Test
    fun parseInlineTokens_supportsNestedMarkdownAndMultiBacktickCodeSpans() {
        // Bold containing inline code
        assertEquals(
            listOf(
                InlineToken.Bold(
                    listOf(
                        InlineToken.Text("two "),
                        InlineToken.Code("exact"),
                        InlineToken.Text(" monthly rules"),
                    )
                )
            ),
            ChatLogic.parseInlineTokens("**two `exact` monthly rules**"),
        )

        // Bold wrapping inline code followed by plain text (e.g. heading content)
        assertEquals(
            listOf(
                InlineToken.Bold(listOf(InlineToken.Code("v7.12.0"))),
                InlineToken.Text(" (26 Nov)"),
            ),
            ChatLogic.parseInlineTokens("**`v7.12.0`** (26 Nov)"),
        )

        // CommonMark double-backtick inline code span preserving literal inner backticks
        assertEquals(
            listOf(InlineToken.Code("`code`")),
            ChatLogic.parseInlineTokens("`` `code` ``"),
        )

        // Link containing inline code
        assertEquals(
            listOf(
                InlineToken.Link(
                    children = listOf(InlineToken.Code("link")),
                    url = "https://example.com",
                    external = true,
                )
            ),
            ChatLogic.parseInlineTokens("[`link`](https://example.com)"),
        )

        // Code inside bold with asterisks stays verbatim and does not terminate bold prematurely
        assertEquals(
            listOf(
                InlineToken.Bold(
                    listOf(
                        InlineToken.Text("use "),
                        InlineToken.Code("a ** b"),
                        InlineToken.Text(" here"),
                    )
                )
            ),
            ChatLogic.parseInlineTokens("**use `a ** b` here**"),
        )

        // Nested link inside bold is also discovered by extractPreviewUrl
        assertEquals(
            "https://docs.example.com",
            ChatLogic.extractPreviewUrl("See **[`Docs`](https://docs.example.com)** now."),
        )

        // Strikethrough containing inline code and italic inside bold
        assertEquals(
            listOf(
                InlineToken.Strike(
                    listOf(
                        InlineToken.Text("old "),
                        InlineToken.Code("api"),
                    )
                ),
                InlineToken.Text(" "),
                InlineToken.Bold(
                    listOf(
                        InlineToken.Italic(listOf(InlineToken.Text("new"))),
                    )
                ),
            ),
            ChatLogic.parseInlineTokens("~~old `api`~~ ***new***"),
        )

        // Unpaired math asterisks stay plain text
        assertEquals(
            listOf(InlineToken.Text("2 * 3 * 4 = 24")),
            ChatLogic.parseInlineTokens("2 * 3 * 4 = 24"),
        )
    }

    @Test
    fun parseActivityStepTokens_formatsInlineCodeAndHandlesTruncatedBackticks() {
        assertEquals(
            listOf(
                InlineToken.Text("running "),
                InlineToken.Code("set -e"),
            ),
            ChatLogic.parseActivityStepTokens("running `set -e`"),
        )

        assertEquals(
            listOf(
                InlineToken.Text("running "),
                InlineToken.Code("rm -rf /tmp/light-screens"),
            ),
            ChatLogic.parseActivityStepTokens("running `rm -rf /tmp/light-screens`"),
        )

        // Server-truncated command with unclosed trailing backtick never leaves raw backticks
        assertEquals(
            listOf(
                InlineToken.Text("running "),
                InlineToken.Code("set -e; curl -sS http://localhost:8080…"),
            ),
            ChatLogic.parseActivityStepTokens("running `set -e; curl -sS http://localhost:8080…"),
        )

        // Markdown link inside activity step flattens cleanly to readable text/code
        assertEquals(
            listOf(
                InlineToken.Text("Inspecting "),
                InlineToken.Code("LiveTab.kt"),
            ),
            ChatLogic.parseActivityStepTokens("Inspecting [`LiveTab.kt`](https://example.com/LiveTab.kt)"),
        )
    }
}
