package com.neekhar.pccontrol.feature.chat

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val queuedIds: Set<String> = emptySet(),
    val engine: String = ChatEngines.CLAUDE,
    val hasMore: Boolean = false,
    val running: Boolean = false,
    val activeProgress: ChatProgress? = null,
    val sseConnected: Boolean = false,
    val loadingInitial: Boolean = true,
    val loadingOlder: Boolean = false,
    val sending: Boolean = false,
    val resetting: Boolean = false,
    val draft: String = "",
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val uploadingCount: Int = 0,
    val replyTo: ChatReplyRef? = null,
    val editingState: EditingMessageState? = null,
    val editingQueuedMessageId: String? = null,
    val error: String? = null,
    val agentSettings: ChatAgentSettings? = null,
    val settingsLoading: Boolean = false,
    val settingsError: String? = null,
    val applyingModel: Boolean = false,
    val applyModelError: String? = null,
    val unreadCount: Int = 0,
    val linkPreviews: Map<String, LinkPreview?> = emptyMap(),
) {
    val listItems: List<ChatMessage>
        get() = ChatLogic.buildChatListItems(
            messages = messages,
            running = running,
            sending = sending,
            engine = engine,
            activeProgress = activeProgress,
            queuedIds = queuedIds,
        )

    val canSend: Boolean
        get() = (draft.isNotBlank() || pendingAttachments.isNotEmpty()) && !sending && uploadingCount == 0

    val hasActiveDraft: Boolean
        get() = draft.isNotBlank() || pendingAttachments.isNotEmpty() || uploadingCount > 0

    val modelChipLabel: String
        get() {
            val s = agentSettings ?: return ChatLogic.formatEngineLabel(engine)
            return if (engine == ChatEngines.CLAUDE) {
                val modelLabel = s.claude.models.firstOrNull { it.id == s.claude.model }?.label
                ChatLogic.formatComposerChipLabel(
                    engine = ChatEngines.CLAUDE,
                    modelId = s.claude.model,
                    modelLabel = modelLabel,
                    effort = s.claude.effort,
                )
            } else {
                val modelLabel = s.gemini.models.firstOrNull { it.id == s.gemini.model }?.label
                ChatLogic.formatComposerChipLabel(
                    engine = ChatEngines.GEMINI,
                    modelId = s.gemini.model,
                    modelLabel = modelLabel,
                    effort = s.gemini.thoughtLevel,
                )
            }
        }
}

/**
 * Pure state reducer for ChatScreen so every optimistic send, steer, SSE update, and pagination
 * transition is deterministic and unit-testable.
 */
object ChatReducer {

    private fun deriveQueuedIds(messages: List<ChatMessage>, running: Boolean): Set<String> {
        if (!running) return emptySet()
        return messages.filter { it.isUser && it.queued }.mapTo(LinkedHashSet()) { it.id }
    }

    fun onMessagesLoaded(
        state: ChatUiState,
        response: ChatMessagesResponse,
        optimisticSteeredIds: Set<String> = emptySet(),
    ): ChatUiState {
        val merged = ChatLogic.preserveOptimisticSteers(
            ChatLogic.mergeChatMessages(state.messages, response.messages),
            optimisticSteeredIds,
        )
        return state.copy(
            messages = merged,
            queuedIds = deriveQueuedIds(merged, response.running),
            engine = ChatEngines.normalize(response.engine),
            running = response.running,
            activeProgress = if (response.running) ChatLogic.mergeProgress(state.activeProgress, response.progress) else null,
            hasMore = response.hasMore,
            loadingInitial = false,
            error = null,
        )
    }

    fun onOlderMessagesLoaded(
        state: ChatUiState,
        older: List<ChatMessage>,
        hasMore: Boolean,
    ): ChatUiState = state.copy(
        messages = ChatLogic.prependOlderMessages(older, state.messages),
        hasMore = hasMore,
        loadingOlder = false,
    )

    fun onOptimisticSend(
        state: ChatUiState,
        optUser: ChatMessage,
        clearComposer: Boolean,
    ): ChatUiState {
        val wasRunning = state.running
        val nextMessages = state.messages + optUser
        return state.copy(
            messages = nextMessages,
            queuedIds = if (optUser.queued) state.queuedIds + optUser.id else state.queuedIds,
            sending = true,
            running = true,
            activeProgress = if (!wasRunning) {
                ChatProgress(engine = state.engine, started = optUser.createdAt, steps = emptyList())
            } else {
                state.activeProgress
            },
            draft = if (clearComposer) "" else state.draft,
            pendingAttachments = if (clearComposer) emptyList() else state.pendingAttachments,
            editingState = if (clearComposer) null else state.editingState,
            editingQueuedMessageId = if (clearComposer) null else state.editingQueuedMessageId,
            replyTo = if (clearComposer) null else state.replyTo,
            unreadCount = 0,
            error = null,
        )
    }

    fun onSendSuccess(
        state: ChatUiState,
        optUserId: String,
        response: ChatSendResponse,
        optimisticSteeredIds: Set<String> = emptySet(),
    ): ChatUiState {
        val confirmed = buildList {
            add(response.userMessage)
            response.assistantMessage?.let { add(it) }
        }
        val withoutOpt = state.messages.filterNot { it.id == optUserId }
        val nonOptimistic = withoutOpt.filterNot { it.optimistic }
        val nextMessages = ChatLogic.preserveOptimisticSteers(
            ChatLogic.mergeChatMessages(withoutOpt, nonOptimistic + confirmed),
            optimisticSteeredIds,
        )
        return state.copy(
            messages = nextMessages,
            queuedIds = deriveQueuedIds(nextMessages, response.running),
            engine = ChatEngines.normalize(response.engine),
            running = response.running,
            activeProgress = if (response.running) state.activeProgress else null,
            sending = false,
        )
    }

    fun onSendFailure(
        state: ChatUiState,
        optUserId: String,
        wasRunning: Boolean,
        restoreComposer: Boolean,
        restoreDraft: String,
        restoreAttachments: List<ChatAttachment>,
        restoreEdit: EditingMessageState?,
        restoreReply: ChatReplyRef?,
        error: String,
        restoreEditingQueuedId: String? = null,
    ): ChatUiState {
        val nextMessages = state.messages.filterNot { it.id == optUserId }
        val nextRunning = if (!wasRunning) false else state.running
        return state.copy(
            messages = nextMessages,
            queuedIds = deriveQueuedIds(nextMessages, nextRunning),
            draft = if (restoreComposer) restoreDraft else state.draft,
            pendingAttachments = if (restoreComposer) restoreAttachments else state.pendingAttachments,
            editingState = if (restoreComposer) restoreEdit else state.editingState,
            editingQueuedMessageId = if (restoreComposer) restoreEditingQueuedId else state.editingQueuedMessageId,
            replyTo = if (restoreComposer) restoreReply else state.replyTo,
            running = nextRunning,
            activeProgress = if (!wasRunning) null else state.activeProgress,
            sending = false,
            error = error,
        )
    }

    fun onOptimisticSteer(
        state: ChatUiState,
        messageId: String,
    ): ChatUiState = state.copy(
        messages = ChatLogic.applyOptimisticSteer(state.messages, messageId),
        queuedIds = state.queuedIds - messageId,
        error = null,
    )

    fun onSteerSuccess(
        state: ChatUiState,
        response: ChatSteerResponse,
        optimisticSteeredIds: Set<String> = emptySet(),
    ): ChatUiState {
        val nextMessages = ChatLogic.preserveOptimisticSteers(
            ChatLogic.upsertChatMessage(state.messages, response.userMessage),
            optimisticSteeredIds,
        )
        return state.copy(
            messages = nextMessages,
            queuedIds = deriveQueuedIds(nextMessages, response.running),
            running = response.running,
            activeProgress = if (response.running) state.activeProgress else null,
        )
    }

    fun onSteerFailure(
        state: ChatUiState,
        messageId: String,
        fallbackUserMessage: ChatMessage?,
        serverRunning: Boolean?,
        error: String,
        optimisticSteeredIds: Set<String> = emptySet(),
    ): ChatUiState {
        val nextRunning = serverRunning ?: state.running
        val restored = if (fallbackUserMessage != null) {
            ChatLogic.upsertChatMessage(state.messages, fallbackUserMessage)
        } else {
            ChatLogic.rollbackOptimisticSteer(state.messages, messageId, stillRunning = nextRunning)
        }
        val nextMessages = ChatLogic.preserveOptimisticSteers(restored, optimisticSteeredIds)
        return state.copy(
            messages = nextMessages,
            queuedIds = deriveQueuedIds(nextMessages, nextRunning),
            running = nextRunning,
            activeProgress = if (nextRunning) state.activeProgress else null,
            error = error,
        )
    }

    fun onMessageDeleted(
        state: ChatUiState,
        deletedId: String,
        serverQueuedIds: List<String>? = null,
        serverRunning: Boolean? = null,
    ): ChatUiState {
        val nextRunning = serverRunning ?: state.running
        val filtered = state.messages.filterNot { it.id == deletedId }
        val nextQueuedIds = serverQueuedIds?.toSet() ?: (state.queuedIds - deletedId)
        val reconciled = if (serverQueuedIds != null) {
            ChatLogic.reconcileQueuedMessages(
                current = filtered,
                queuedIds = serverQueuedIds,
                running = nextRunning,
            )
        } else {
            filtered
        }
        val clearEdit = state.editingQueuedMessageId == deletedId || state.editingState?.messageId == deletedId
        return state.copy(
            messages = reconciled,
            queuedIds = if (nextRunning) nextQueuedIds else emptySet(),
            running = nextRunning,
            activeProgress = if (nextRunning) state.activeProgress else null,
            editingState = if (clearEdit) null else state.editingState,
            editingQueuedMessageId = if (clearEdit) null else state.editingQueuedMessageId,
        )
    }

    fun onSseEvent(
        state: ChatUiState,
        event: ChatSseEvent,
        optimisticSteeredIds: Set<String> = emptySet(),
        isNearBottom: Boolean = true,
    ): ChatUiState = when (event) {
        is ChatSseEvent.Snapshot -> {
            val reconciled = ChatLogic.reconcileQueuedMessages(
                current = state.messages,
                queuedIds = event.queuedIds,
                running = event.running,
                optimisticSteeredIds = optimisticSteeredIds,
            )
            state.copy(
                engine = ChatEngines.normalize(event.engine),
                running = event.running,
                activeProgress = if (event.running) {
                    ChatLogic.mergeProgress(state.activeProgress, event.progress)
                } else {
                    null
                },
                messages = reconciled,
                queuedIds = if (event.running) event.queuedIds.toSet() else emptySet(),
            )
        }

        is ChatSseEvent.Message -> {
            val hadMessage = state.messages.any { it.id == event.message.id }
            val nextEngine = event.engine?.let { ChatEngines.normalize(it) } ?: state.engine
            val nextProgress = when {
                !event.running -> null
                event.hasProgressField -> ChatLogic.mergeProgress(state.activeProgress, event.progress)
                else -> state.activeProgress
            }
            val nextMessages = ChatLogic.preserveOptimisticSteers(
                ChatLogic.upsertChatMessage(state.messages, event.message, event.removedMessageId),
                optimisticSteeredIds,
            )
            val shouldIncrementUnread = !isNearBottom &&
                !hadMessage &&
                event.message.role == ChatRoles.ASSISTANT &&
                event.message.status == ChatStatuses.DONE
            state.copy(
                engine = nextEngine,
                running = event.running,
                activeProgress = nextProgress,
                messages = nextMessages,
                queuedIds = deriveQueuedIds(nextMessages, event.running),
                unreadCount = if (shouldIncrementUnread) state.unreadCount + 1 else state.unreadCount,
            )
        }

        is ChatSseEvent.Progress -> state.copy(
            running = event.running,
            activeProgress = if (event.running) {
                ChatLogic.mergeProgress(state.activeProgress, event.progress)
            } else {
                null
            },
            queuedIds = if (event.running) state.queuedIds else emptySet(),
        )

        is ChatSseEvent.MessageDeleted -> onMessageDeleted(
            state = state,
            deletedId = event.id,
        )

        is ChatSseEvent.State -> {
            val reconciled = ChatLogic.reconcileQueuedMessages(
                current = state.messages,
                queuedIds = event.queuedIds,
                running = event.running,
                optimisticSteeredIds = optimisticSteeredIds,
            )
            state.copy(
                engine = event.engine?.let { ChatEngines.normalize(it) } ?: state.engine,
                running = event.running,
                activeProgress = if (event.running) state.activeProgress else null,
                messages = reconciled,
                queuedIds = if (event.running) event.queuedIds.toSet() else emptySet(),
            )
        }
    }

    fun onNewChatSuccess(
        state: ChatUiState,
        response: ChatNewResponse,
    ): ChatUiState {
        val nonOptimistic = state.messages.filterNot { it.optimistic }
        val nextMessages = ChatLogic.mergeChatMessages(
            state.messages,
            nonOptimistic + response.boundaryMessage,
        )
        return state.copy(
            engine = ChatEngines.normalize(response.engine),
            running = response.running,
            activeProgress = if (response.running) state.activeProgress else null,
            messages = nextMessages,
            resetting = false,
            unreadCount = 0,
            error = null,
        )
    }
}
