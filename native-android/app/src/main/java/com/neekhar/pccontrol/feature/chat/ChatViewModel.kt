package com.neekhar.pccontrol.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections

sealed interface ChatUiEvent {
    data class ScrollToBottom(val animated: Boolean) : ChatUiEvent
    data class ScrollToMessageIndex(val index: Int) : ChatUiEvent
    data class ShowToast(val message: String) : ChatUiEvent
    data object FocusComposer : ChatUiEvent
    data object CloseModelPicker : ChatUiEvent
}

class ChatViewModel(
    private val repository: ChatRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(buildInitialState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ChatUiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ChatUiEvent> = _events.asSharedFlow()

    private val optimisticSteeredIds: MutableSet<String> =
        Collections.synchronizedSet(HashSet())

    @Volatile
    private var isForeground: Boolean = false

    @Volatile
    private var isNearBottom: Boolean = true

    private var sseJob: Job? = null
    private var pollJob: Job? = null

    private fun buildInitialState(): ChatUiState {
        val cached = repository.loadCachedChatState()
        val cachedSettings = repository.loadCachedAgentSettings()
        return ChatUiState(
            messages = cached?.messages.orEmpty(),
            engine = ChatEngines.normalize(cached?.engine),
            hasMore = cached?.hasMore ?: false,
            loadingInitial = cached == null,
            agentSettings = cachedSettings,
        )
    }

    // ---------------------------------------------------------------- Foreground & lifecycle

    fun setForeground(visible: Boolean) {
        if (isForeground == visible) return
        isForeground = visible
        if (visible) {
            refreshMessages(silent = true)
            refreshAgentSettings()
            startSseStream()
            updatePollingLoop()
        } else {
            sseJob?.cancel()
            sseJob = null
            pollJob?.cancel()
            pollJob = null
            _uiState.update { it.copy(sseConnected = false) }
        }
    }

    fun onPushReceived(pushId: String) {
        if (pushId.startsWith("ota-trigger-")) return
        refreshMessages(silent = true)
    }

    fun onNearBottomChanged(nearBottom: Boolean) {
        isNearBottom = nearBottom
        if (nearBottom && _uiState.value.unreadCount > 0) {
            _uiState.update { it.copy(unreadCount = 0) }
        }
    }

    fun resolveAttachmentSource(url: String): ChatAttachmentSource =
        repository.resolveAttachmentSource(url)

    // ---------------------------------------------------------------- SSE & fallback polling

    private fun startSseStream() {
        sseJob?.cancel()
        sseJob = viewModelScope.launch {
            var hasConnectedOnce = false
            repository.observeChatEvents().collect { item ->
                when (item) {
                    is ChatEventStreamItem.ConnectionChanged -> {
                        _uiState.update { it.copy(sseConnected = item.connected) }
                        if (item.connected) {
                            if (hasConnectedOnce) {
                                refreshMessages(silent = true)
                            }
                            hasConnectedOnce = true
                        }
                        updatePollingLoop()
                    }

                    is ChatEventStreamItem.Event -> {
                        val steeredSnapshot = snapshotSteeredIds()
                        val next = ChatReducer.onSseEvent(
                            state = _uiState.value,
                            event = item.event,
                            optimisticSteeredIds = steeredSnapshot,
                            isNearBottom = isNearBottom,
                        )
                        _uiState.value = next
                        persistIfRelevant(item.event, next)
                        updatePollingLoop()
                    }
                }
            }
        }
    }

    private fun persistIfRelevant(event: ChatSseEvent, state: ChatUiState) {
        when (event) {
            is ChatSseEvent.Message,
            is ChatSseEvent.Snapshot,
            -> repository.saveCachedChatState(state.messages, state.engine, state.hasMore)

            is ChatSseEvent.Progress -> Unit
        }
    }

    private fun updatePollingLoop() {
        val s = _uiState.value
        val shouldPoll = ChatLogic.shouldPollChat(s.messages, s.running, isForeground)
        if (!shouldPoll) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val cur = _uiState.value
                if (!ChatLogic.shouldPollChat(cur.messages, cur.running, isForeground)) break
                val intervalMs = if (cur.sseConnected) SSE_FALLBACK_POLL_MS else ACTIVE_POLL_MS
                delay(intervalMs)
                if (!isActive || !isForeground) break
                refreshMessages(silent = true)
            }
        }
    }

    private fun snapshotSteeredIds(): Set<String> = synchronized(optimisticSteeredIds) {
        optimisticSteeredIds.toHashSet()
    }

    // ---------------------------------------------------------------- Messages loading & pagination

    fun refreshMessages(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(error = null) }
            }
            try {
                val res = repository.fetchMessages(before = null, limit = 50)
                val next = ChatReducer.onMessagesLoaded(
                    state = _uiState.value,
                    response = res,
                    optimisticSteeredIds = snapshotSteeredIds(),
                )
                _uiState.value = next
                repository.saveCachedChatState(next.messages, next.engine, next.hasMore)
                updatePollingLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loadingInitial = false,
                        error = e.message ?: "Failed to load messages",
                    )
                }
            }
        }
    }

    fun loadOlderMessages() {
        val state = _uiState.value
        if (state.loadingOlder || !state.hasMore || state.messages.isEmpty()) return
        val oldest = state.messages.firstOrNull() ?: return
        if (oldest.optimistic) return

        viewModelScope.launch {
            _uiState.update { it.copy(loadingOlder = true) }
            try {
                val res = repository.fetchMessages(before = oldest.id, limit = 50)
                _uiState.update { cur ->
                    ChatReducer.onOlderMessagesLoaded(cur, res.messages, res.hasMore)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loadingOlder = false,
                        error = e.message ?: "Failed to load older messages",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- Composer state & sending

    fun onDraftChanged(nextDraft: String) {
        _uiState.update { it.copy(draft = nextDraft) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun removeAttachment(id: String) {
        _uiState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.id == id })
        }
    }

    fun uploadFiles(files: List<PickedLocalFile>) {
        if (files.isEmpty()) return
        val currentState = _uiState.value
        val slots = MAX_ATTACHMENTS - (currentState.pendingAttachments.size + currentState.uploadingCount)
        if (slots <= 0) {
            _uiState.update { it.copy(error = "Up to $MAX_ATTACHMENTS attachments per message.") }
            return
        }
        val batch = files.take(slots)
        val overflowError = if (files.size > slots) "Up to $MAX_ATTACHMENTS attachments per message." else null
        _uiState.update {
            it.copy(
                uploadingCount = it.uploadingCount + batch.size,
                error = overflowError,
            )
        }
        viewModelScope.launch {
            for (file in batch) {
                try {
                    val uploaded = repository.uploadAttachment(file)
                    _uiState.update { state ->
                        val nextAtts = if (state.pendingAttachments.size < MAX_ATTACHMENTS) {
                            state.pendingAttachments + uploaded
                        } else {
                            state.pendingAttachments
                        }
                        state.copy(
                            pendingAttachments = nextAtts,
                            uploadingCount = (state.uploadingCount - 1).coerceAtLeast(0),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiState.update { state ->
                        state.copy(
                            uploadingCount = (state.uploadingCount - 1).coerceAtLeast(0),
                            error = e.message ?: "Failed to upload ${file.name}",
                        )
                    }
                }
            }
        }
    }

    fun sendCurrentDraft() {
        val state = _uiState.value
        if (!state.canSend) return
        sendMessagePayload(
            text = state.draft,
            attachments = state.pendingAttachments,
            clearComposer = true,
            replyTo = state.replyTo,
            restoreEditOnError = state.editingState,
        )
    }

    fun resendMessage(msg: ChatMessage) {
        if (_uiState.value.sending) return
        sendMessagePayload(
            text = msg.text,
            attachments = msg.attachments.orEmpty(),
            clearComposer = false,
            replyTo = null,
            restoreEditOnError = null,
        )
    }

    fun retryAssistantError(msg: ChatMessage) {
        val state = _uiState.value
        if (state.sending) return
        val idx = state.messages.indexOfFirst { it.id == msg.id }
        if (idx < 0) return
        val userMsg = ChatLogic.findPrecedingUserMessage(state.messages, idx) ?: return
        sendMessagePayload(
            text = userMsg.text,
            attachments = userMsg.attachments.orEmpty(),
            clearComposer = false,
            replyTo = null,
            restoreEditOnError = null,
        )
    }

    private fun sendMessagePayload(
        text: String,
        attachments: List<ChatAttachment>,
        clearComposer: Boolean,
        replyTo: ChatReplyRef?,
        restoreEditOnError: EditingMessageState?,
    ) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && attachments.isEmpty()) || _uiState.value.sending) return

        val clientId = ChatLogic.makeClientId()
        val nowSec = System.currentTimeMillis() / 1000.0
        val currentState = _uiState.value
        val wasRunning = currentState.running
        val optUser = ChatMessage(
            id = "opt-u-$clientId",
            role = ChatRoles.USER,
            engine = currentState.engine,
            text = trimmed,
            createdAt = nowSec,
            status = ChatStatuses.DONE,
            clientId = clientId,
            replyTo = replyTo?.toJsonObject(),
            attachments = attachments.takeIf { it.isNotEmpty() },
            optimistic = true,
            queued = wasRunning,
        )

        isNearBottom = true
        _uiState.update { ChatReducer.onOptimisticSend(it, optUser, clearComposer) }
        _events.tryEmit(ChatUiEvent.ScrollToBottom(animated = true))

        viewModelScope.launch {
            try {
                val res = repository.sendMessage(
                    text = trimmed,
                    clientId = clientId,
                    attachments = attachments,
                    replyToId = replyTo?.id,
                )
                val next = ChatReducer.onSendSuccess(
                    state = _uiState.value,
                    optUserId = optUser.id,
                    response = res,
                    optimisticSteeredIds = snapshotSteeredIds(),
                )
                _uiState.value = next
                repository.saveCachedChatState(next.messages, next.engine, next.hasMore)
                updatePollingLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { cur ->
                    ChatReducer.onSendFailure(
                        state = cur,
                        optUserId = optUser.id,
                        wasRunning = wasRunning,
                        restoreComposer = clearComposer,
                        restoreDraft = trimmed,
                        restoreAttachments = attachments,
                        restoreEdit = restoreEditOnError,
                        restoreReply = replyTo,
                        error = e.message ?: "Failed to send message",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- Queued message steering ("Send now")

    fun steerQueuedMessage(msg: ChatMessage) {
        if (msg.id.isBlank() || msg.optimistic) return
        if (!optimisticSteeredIds.add(msg.id)) return

        val afterTap = ChatReducer.onOptimisticSteer(_uiState.value, msg.id)
        _uiState.value = afterTap
        repository.saveCachedChatState(afterTap.messages, afterTap.engine, afterTap.hasMore)

        viewModelScope.launch {
            try {
                val res = repository.steerMessage(msg.id)
                optimisticSteeredIds.remove(msg.id)
                val next = ChatReducer.onSteerSuccess(
                    state = _uiState.value,
                    response = res,
                    optimisticSteeredIds = snapshotSteeredIds(),
                )
                _uiState.value = next
                repository.saveCachedChatState(next.messages, next.engine, next.hasMore)
                updatePollingLoop()
            } catch (e: CancellationException) {
                optimisticSteeredIds.remove(msg.id)
                throw e
            } catch (e: Exception) {
                optimisticSteeredIds.remove(msg.id)
                val errBody = (e as? ChatApiException)?.let { repository.parseSteerErrorBody(it.rawBody) }
                val next = ChatReducer.onSteerFailure(
                    state = _uiState.value,
                    messageId = msg.id,
                    fallbackUserMessage = errBody?.userMessage,
                    serverRunning = errBody?.running,
                    error = e.message ?: "Failed to send message now",
                    optimisticSteeredIds = snapshotSteeredIds(),
                )
                _uiState.value = next
                repository.saveCachedChatState(next.messages, next.engine, next.hasMore)
                refreshMessages(silent = true)
            }
        }
    }

    // ---------------------------------------------------------------- Reply, Edit & Quote Jump

    fun startReply(msg: ChatMessage) {
        _uiState.update { state ->
            val edit = state.editingState
            state.copy(
                draft = edit?.previousDraft ?: state.draft,
                pendingAttachments = edit?.previousAttachments ?: state.pendingAttachments,
                editingState = null,
                replyTo = ChatLogic.buildReplyRef(msg),
            )
        }
        _events.tryEmit(ChatUiEvent.FocusComposer)
    }

    fun cancelReply() {
        _uiState.update { it.copy(replyTo = null) }
    }

    fun startEditMessage(msg: ChatMessage) {
        _uiState.update { state ->
            val prevEdit = state.editingState
            state.copy(
                replyTo = null,
                editingState = EditingMessageState(
                    messageId = msg.id,
                    previewText = ChatLogic.formatMessagePreview(msg),
                    previousDraft = prevEdit?.previousDraft ?: state.draft,
                    previousAttachments = prevEdit?.previousAttachments ?: state.pendingAttachments,
                ),
                draft = msg.text,
                pendingAttachments = msg.attachments.orEmpty(),
            )
        }
        _events.tryEmit(ChatUiEvent.FocusComposer)
    }

    fun cancelEdit() {
        _uiState.update { state ->
            val edit = state.editingState ?: return@update state
            state.copy(
                draft = edit.previousDraft,
                pendingAttachments = edit.previousAttachments,
                editingState = null,
            )
        }
    }

    fun jumpToQuotedMessage(replyId: String) {
        val idx = _uiState.value.listItems.indexOfFirst { it.id == replyId }
        if (idx >= 0) {
            _events.tryEmit(ChatUiEvent.ScrollToMessageIndex(idx))
        }
    }

    // ---------------------------------------------------------------- New session & Stop/Interrupt

    fun startNewChat() {
        if (_uiState.value.resetting) return
        viewModelScope.launch {
            _uiState.update { it.copy(resetting = true, error = null) }
            try {
                val res = repository.startNewChat()
                val next = ChatReducer.onNewChatSuccess(_uiState.value, res)
                _uiState.value = next
                repository.saveCachedChatState(next.messages, next.engine, next.hasMore)
                isNearBottom = true
                _events.tryEmit(ChatUiEvent.ScrollToBottom(animated = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        resetting = false,
                        error = e.message ?: "Failed to start new conversation",
                    )
                }
            }
        }
    }

    fun stopGeneration() {
        if (!_uiState.value.running) return
        viewModelScope.launch {
            try {
                val stopped = repository.interruptActiveTurn()
                if (stopped) {
                    _events.tryEmit(ChatUiEvent.ShowToast("Stopped generation"))
                    refreshMessages(silent = true)
                } else {
                    _events.tryEmit(ChatUiEvent.ShowToast("Send a follow-up to queue or steer"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Could not stop generation") }
            }
        }
    }

    // ---------------------------------------------------------------- Model & Effort picker

    fun refreshAgentSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(settingsLoading = true, settingsError = null) }
            try {
                val settings = repository.getAgentSettings()
                _uiState.update { it.copy(agentSettings = settings, settingsLoading = false, settingsError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        settingsLoading = false,
                        settingsError = e.message ?: "Failed to load agent settings",
                    )
                }
            }
        }
    }

    fun clearApplyModelError() {
        _uiState.update { it.copy(applyModelError = null) }
    }

    fun applyModelSelection(selection: AgentSelectionResult) {
        if (_uiState.value.applyingModel) return
        viewModelScope.launch {
            _uiState.update { it.copy(applyingModel = true, applyModelError = null) }
            try {
                if (!selection.patch.isEmpty) {
                    val updated = repository.updateAgentSettings(selection.patch)
                    _uiState.update { it.copy(agentSettings = updated) }
                }
                if (selection.engine != _uiState.value.engine) {
                    repository.switchAgent(selection.engine)
                    _uiState.update { state ->
                        val next = state.copy(engine = selection.engine)
                        repository.saveCachedChatState(next.messages, next.engine, next.hasMore)
                        next
                    }
                }
                _uiState.update { it.copy(applyingModel = false, applyModelError = null) }
                _events.tryEmit(ChatUiEvent.CloseModelPicker)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        applyingModel = false,
                        applyModelError = e.message ?: "Failed to update model settings",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- OG Link Previews

    fun requestLinkPreview(url: String) {
        val key = url.trim()
        if (key.isEmpty()) return
        if (_uiState.value.linkPreviews.containsKey(key)) return

        val (hit, cached) = repository.getCachedLinkPreview(key)
        if (hit) {
            _uiState.update { it.copy(linkPreviews = it.linkPreviews + (key to cached)) }
            return
        }

        viewModelScope.launch {
            val preview = repository.fetchLinkPreview(key)
            _uiState.update { it.copy(linkPreviews = it.linkPreviews + (key to preview)) }
        }
    }

    fun notifyCopied(label: String = "Copied") {
        _events.tryEmit(ChatUiEvent.ShowToast(label))
    }

    override fun onCleared() {
        sseJob?.cancel()
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val ACTIVE_POLL_MS = 1_000L
        private const val SSE_FALLBACK_POLL_MS = 3_000L

        fun factory(repository: ChatRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return ChatViewModel(repository) as T
                }
            }
    }
}
