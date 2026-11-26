package com.neekhar.pccontrol.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neekhar.pccontrol.core.platform.ChatForegroundState
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenExternalUrl: ((String) -> Unit)? = null,
    onOpenAgentSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val onAccent = if (colors.accent.luminance() > 0.4f) Color.Black else Color.White
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listItems = state.listItems
    val listState = rememberLazyListState()

    var showModelPicker by remember { mutableStateOf(false) }
    var showNewChatDialog by remember { mutableStateOf(false) }
    var actionSheetMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var previewImage by remember { mutableStateOf<PreviewImageItem?>(null) }
    var toastText by remember { mutableStateOf<String?>(null) }
    var toastNonce by remember { mutableIntStateOf(0) }
    var focusTrigger by remember { mutableIntStateOf(0) }
    var hasInitialScrolled by remember { mutableStateOf(false) }

    val openUrl: (String) -> Unit = remember(onOpenExternalUrl, context) {
        { url ->
            val resolved = ChatLogic.resolveChatLinkUrl(url)
            if (resolved != null) {
                if (onOpenExternalUrl != null) {
                    onOpenExternalUrl(resolved)
                } else {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved))
                        context.startActivity(intent)
                    }
                }
            }
        }
    }

    fun copyToClipboard(text: String, toastLabel: String = "Copied") {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("PC Control", clean))
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        viewModel.notifyCopied(toastLabel)
    }

    // Foreground push suppression + live SSE lifecycle management
    DisposableEffect(lifecycleOwner, viewModel) {
        val pushCallback: (String, String, String) -> Unit = { id, _, _ ->
            viewModel.onPushReceived(id)
        }

        fun setVisible(visible: Boolean) {
            ChatForegroundState.visible = visible
            ChatForegroundState.onPush = if (visible) pushCallback else null
            viewModel.setForeground(visible)
        }

        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            setVisible(true)
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> setVisible(true)
                Lifecycle.Event.ON_PAUSE -> setVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (ChatForegroundState.onPush === pushCallback) {
                ChatForegroundState.onPush = null
            }
            ChatForegroundState.visible = false
            viewModel.setForeground(false)
        }
    }

    // Track whether list is near bottom for sticky auto-scroll and unread pill visibility
    val isNearBottom by remember(listItems.size) {
        derivedStateOf {
            if (listItems.isEmpty()) return@derivedStateOf true
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            lastVisible.index >= listItems.lastIndex - 1
        }
    }

    LaunchedEffect(isNearBottom) {
        viewModel.onNearBottomChanged(isNearBottom)
    }

    // Initial jump to bottom as soon as cached or loaded messages appear
    LaunchedEffect(listItems.size) {
        if (listItems.isNotEmpty() && !hasInitialScrolled) {
            hasInitialScrolled = true
            listState.scrollToItem(listItems.lastIndex)
        } else if (listItems.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(listItems.lastIndex)
        }
    }

    // Keep live shimmer/activity steps anchored at bottom when near bottom
    LaunchedEffect(state.activeProgress?.steps?.size) {
        if (listItems.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(listItems.lastIndex)
        }
    }

    // One-shot ViewModel UI events
    LaunchedEffect(viewModel) {
        viewModel.events.collect { ev ->
            when (ev) {
                is ChatUiEvent.ScrollToBottom -> {
                    val lastIdx = viewModel.uiState.value.listItems.lastIndex
                    if (lastIdx >= 0) {
                        if (ev.animated) listState.animateScrollToItem(lastIdx)
                        else listState.scrollToItem(lastIdx)
                    }
                }

                is ChatUiEvent.ScrollToMessageIndex -> {
                    val size = viewModel.uiState.value.listItems.size
                    if (ev.index in 0 until size) {
                        listState.animateScrollToItem(ev.index)
                    }
                }

                is ChatUiEvent.ShowToast -> {
                    toastText = ev.message
                    toastNonce += 1
                }

                is ChatUiEvent.FocusComposer -> {
                    focusTrigger += 1
                }

                is ChatUiEvent.CloseModelPicker -> {
                    showModelPicker = false
                }
            }
        }
    }

    // Auto-dismiss feedback toast pill
    LaunchedEffect(toastNonce) {
        if (toastNonce > 0 && toastText != null) {
            delay(1500L)
            toastText = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // Header: Status line + Title | Model/effort quick pill + New chat button
        ChatHeader(
            engine = state.engine,
            modelChipLabel = state.modelChipLabel,
            running = state.running,
            sseConnected = state.sseConnected,
            resetting = state.resetting,
            onOpenModelPicker = {
                keyboard?.hide()
                viewModel.clearApplyModelError()
                showModelPicker = true
                if (state.agentSettings == null) {
                    viewModel.refreshAgentSettings()
                }
            },
            onNewChat = { showNewChatDialog = true },
        )

        // Error banner
        AnimatedVisibility(visible = !state.error.isNullOrBlank()) {
            state.error?.let { errMsg ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.badSoft)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = colors.bad,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = errMsg,
                        color = colors.bad,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { viewModel.dismissError() },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss error",
                            tint = colors.bad,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }

        // Main messages viewport + floating pills
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.loadingInitial && listItems.isEmpty() -> {
                    ChatSkeletonPlaceholder()
                }

                listItems.isEmpty() -> {
                    ChatEmptyState(engine = state.engine)
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 18.dp,
                            end = 18.dp,
                            top = 8.dp,
                            bottom = 14.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (state.hasMore) {
                            item(key = "__load_earlier__") {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(colors.surfaceRaised)
                                            .clickable(enabled = !state.loadingOlder) {
                                                viewModel.loadOlderMessages()
                                            }
                                            .padding(horizontal = 14.dp, vertical = 7.dp),
                                    ) {
                                        if (state.loadingOlder) {
                                            CircularProgressIndicator(
                                                color = colors.accent,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                        Text(
                                            text = if (state.loadingOlder) "Loading…" else "Load earlier",
                                            color = colors.text,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }
                        }

                        itemsIndexed(
                            items = listItems,
                            key = { _, item -> ChatLogic.getChatMessageKey(item) },
                        ) { index, item ->
                            val canRetry = item.isAssistant &&
                                item.isError &&
                                ChatLogic.findPrecedingUserMessage(listItems, index) != null
                            val isQueued = state.running && item.isUser && item.queued
                            val canSteer = ChatLogic.canSteerQueuedMessage(item, state.running)

                            ChatMessageItem(
                                msg = item,
                                canRetry = canRetry,
                                isQueued = isQueued,
                                canSteer = canSteer,
                                linkPreviews = state.linkPreviews,
                                resolveAttachmentSource = viewModel::resolveAttachmentSource,
                                onRequestLinkPreview = viewModel::requestLinkPreview,
                                onOpenUrl = openUrl,
                                onLongPress = { pressed ->
                                    keyboard?.hide()
                                    actionSheetMsg = pressed
                                },
                                onReply = { target ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.startReply(target)
                                },
                                onCopy = { text -> copyToClipboard(text) },
                                onCopyCode = { code -> copyToClipboard(code, "Copied code") },
                                onRetry = { errMsg -> viewModel.retryAssistantError(errMsg) },
                                onSteer = { queuedMsg ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.steerQueuedMessage(queuedMsg)
                                },
                                onPressReplyQuote = { replyId -> viewModel.jumpToQuotedMessage(replyId) },
                                onPressImage = { img ->
                                    keyboard?.hide()
                                    previewImage = img
                                },
                            )
                        }
                    }
                }
            }

            // Floating jump-to-bottom pill with unread badge
            val showJumpPill = !isNearBottom && listItems.isNotEmpty()
            AnimatedVisibility(
                visible = showJumpPill,
                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                exit = fadeOut() + scaleOut(targetScale = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (state.unreadCount > 0) colors.accent else colors.surfaceRaised)
                        .border(
                            width = 0.5.dp,
                            color = if (state.unreadCount > 0) colors.accent else colors.hairline,
                            shape = RoundedCornerShape(999.dp),
                        )
                        .clickable {
                            scope.launch {
                                val lastIdx = listItems.lastIndex
                                if (lastIdx >= 0) {
                                    listState.animateScrollToItem(lastIdx)
                                }
                            }
                            viewModel.onNearBottomChanged(true)
                        }
                        .padding(
                            horizontal = if (state.unreadCount > 0) 12.dp else 9.dp,
                            vertical = 9.dp,
                        ),
                ) {
                    if (state.unreadCount > 0) {
                        Text(
                            text = "${state.unreadCount}",
                            style = TabularNumStyle,
                            color = onAccent,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Jump to latest message",
                        tint = if (state.unreadCount > 0) onAccent else colors.text,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Transient Copy / Save toast pill
            AnimatedVisibility(
                visible = toastText != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.surfaceRaised)
                        .border(0.5.dp, colors.hairline, RoundedCornerShape(999.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = toastText.orEmpty(),
                        color = colors.text,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)

        // Native Composer
        ChatComposer(
            draft = state.draft,
            engine = state.engine,
            running = state.running,
            sending = state.sending,
            canSend = state.canSend,
            pendingAttachments = state.pendingAttachments,
            uploadingCount = state.uploadingCount,
            replyTo = state.replyTo,
            editingState = state.editingState,
            focusTrigger = focusTrigger,
            resolveAttachmentSource = viewModel::resolveAttachmentSource,
            onDraftChange = viewModel::onDraftChanged,
            onSend = viewModel::sendCurrentDraft,
            onStopGeneration = viewModel::stopGeneration,
            onPickFiles = viewModel::uploadFiles,
            onRemoveAttachment = viewModel::removeAttachment,
            onCancelReply = viewModel::cancelReply,
            onCancelEdit = viewModel::cancelEdit,
            onPressImage = { img ->
                keyboard?.hide()
                previewImage = img
            },
            onError = { msg -> viewModel.notifyCopied(msg) },
        )
    }

    // Unified Model & Effort Picker Sheet
    if (showModelPicker) {
        AgentModelPickerSheet(
            engine = state.engine,
            settings = state.agentSettings,
            loading = state.settingsLoading,
            loadError = state.settingsError,
            applying = state.applyingModel,
            applyError = state.applyModelError,
            onRetryLoad = viewModel::refreshAgentSettings,
            onApply = viewModel::applyModelSelection,
            onOpenAgentSettings = onOpenAgentSettings,
            onDismiss = { showModelPicker = false },
        )
    }

    // New conversation confirmation dialog
    if (showNewChatDialog) {
        AlertDialog(
            onDismissRequest = { showNewChatDialog = false },
            containerColor = colors.surface,
            titleContentColor = colors.text,
            textContentColor = colors.textDim,
            title = {
                Text(
                    text = "New conversation",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = "Start a fresh ${ChatLogic.formatEngineLabel(state.engine)} session?",
                    fontSize = 14.5.sp,
                )
            },
            dismissButton = {
                TextButton(onClick = { showNewChatDialog = false }) {
                    Text("Cancel", color = colors.textDim)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewChatDialog = false
                        viewModel.startNewChat()
                    },
                ) {
                    Text("New", color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }

    // Message Long-Press Actions Sheet
    actionSheetMsg?.let { target ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val preview = remember(target) { ChatLogic.formatMessagePreview(target) }
        val timeStr = remember(target.createdAt) { ChatLogic.formatChatTime(target.createdAt) }
        val metaLabel = remember(target) {
            when {
                target.isUser -> "You"
                target.isError -> "${ChatLogic.formatEngineLabel(target.engine)} · Error"
                else -> ChatLogic.formatEngineLabel(target.engine)
            }
        }
        val copyText = remember(target) { (target.text.ifBlank { target.error.orEmpty() }).trim() }
        val canRetryTarget = remember(target, state.messages) {
            if (!target.isAssistant || !target.isError) false
            else {
                val idx = state.messages.indexOfFirst { it.id == target.id }
                idx >= 0 && ChatLogic.findPrecedingUserMessage(state.messages, idx) != null
            }
        }

        ModalBottomSheet(
            onDismissRequest = { actionSheetMsg = null },
            sheetState = sheetState,
            containerColor = colors.surface,
            contentColor = colors.text,
            dragHandle = { BottomSheetDefaults.DragHandle(color = colors.hairline) },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 6.dp)
                    .padding(bottom = 14.dp),
            ) {
                // Message preview header
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceRaised)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = if (timeStr.isNotEmpty()) "$metaLabel · $timeStr" else metaLabel,
                        style = TabularNumStyle,
                        color = if (target.isError) colors.bad else colors.textDim,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (preview.isNotBlank()) {
                        Text(
                            text = preview,
                            color = colors.text,
                            fontSize = 13.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                MessageActionRow(
                    icon = Icons.Rounded.Reply,
                    iconTint = colors.accent,
                    title = "Reply",
                    subtitle = "Quote this message in your reply",
                    onClick = {
                        actionSheetMsg = null
                        viewModel.startReply(target)
                    },
                )

                if (target.isUser) {
                    MessageActionRow(
                        icon = Icons.Rounded.Edit,
                        iconTint = colors.accent,
                        title = "Edit & resend",
                        subtitle = "Modify in composer before sending",
                        onClick = {
                            actionSheetMsg = null
                            viewModel.startEditMessage(target)
                        },
                    )
                    MessageActionRow(
                        icon = Icons.Rounded.Refresh,
                        iconTint = colors.textDim,
                        title = "Resend",
                        subtitle = "Send again immediately",
                        enabled = !state.sending,
                        onClick = {
                            actionSheetMsg = null
                            viewModel.resendMessage(target)
                        },
                    )
                }

                if (canRetryTarget) {
                    MessageActionRow(
                        icon = Icons.Rounded.Refresh,
                        iconTint = colors.accent,
                        title = "Retry",
                        subtitle = "Resend your previous message",
                        enabled = !state.sending,
                        onClick = {
                            actionSheetMsg = null
                            viewModel.retryAssistantError(target)
                        },
                    )
                }

                if (copyText.isNotEmpty()) {
                    MessageActionRow(
                        icon = Icons.Rounded.ContentCopy,
                        iconTint = colors.textDim,
                        title = "Copy",
                        subtitle = if (target.isError) "Copy error details" else "Copy message text",
                        onClick = {
                            actionSheetMsg = null
                            copyToClipboard(copyText)
                        },
                    )
                }
            }
        }
    }

    // Full-screen pinch-to-zoom ImageViewerDialog
    previewImage?.let { img ->
        val src = remember(img.url) { viewModel.resolveAttachmentSource(img.url) }
        ImageViewerDialog(
            image = img,
            source = src,
            onDismiss = { previewImage = null },
            onOpenExternalUrl = openUrl,
            onMessage = { msg -> viewModel.notifyCopied(msg) },
        )
    }
}

@Composable
private fun ChatHeader(
    engine: String,
    modelChipLabel: String,
    running: Boolean,
    sseConnected: Boolean,
    resetting: Boolean,
    onOpenModelPicker: () -> Unit,
    onNewChat: () -> Unit,
) {
    val colors = LocalPcColors.current
    val statusDotColor = when {
        running -> colors.accent
        sseConnected -> colors.ok
        else -> colors.textFaint
    }
    val statusLabel = when {
        running -> "${ChatLogic.formatEngineLabel(engine)} · working"
        sseConnected -> "Live"
        else -> "Syncing…"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusDotColor),
                )
                Text(
                    text = statusLabel,
                    color = colors.textDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "Chat",
                color = colors.text,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            )
        }

        // Quick model & effort pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(colors.surfaceRaised)
                .clickable(onClick = onOpenModelPicker)
                .padding(horizontal = 11.dp, vertical = 7.dp),
        ) {
            Icon(
                imageVector = if (engine == ChatEngines.GEMINI) Icons.Rounded.AutoAwesome else Icons.Rounded.Psychology,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = modelChipLabel,
                color = colors.text,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Switch model and effort",
                tint = colors.textFaint,
                modifier = Modifier.size(15.dp),
            )
        }

        // New conversation button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .alpha(if (resetting) 0.5f else 1f)
                .clip(CircleShape)
                .background(colors.surfaceRaised)
                .clickable(enabled = !resetting, onClick = onNewChat),
        ) {
            if (resetting) {
                CircularProgressIndicator(
                    color = colors.text,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(15.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.EditNote,
                    contentDescription = "New conversation",
                    tint = colors.text,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatEmptyState(engine: String) {
    val colors = LocalPcColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(colors.surface),
        ) {
            Icon(
                imageVector = Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Start a conversation",
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Ask ${ChatLogic.formatEngineLabel(engine)} anything or paste a screenshot",
            color = colors.textFaint,
            fontSize = 13.5.sp,
        )
    }
}

@Composable
private fun ChatSkeletonPlaceholder() {
    val colors = LocalPcColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        // Right user bubble placeholder
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth(0.68f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceRaised)
                    .padding(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.hairline),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.hairline),
                )
            }
        }

        // Left bubbleless assistant placeholder
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(colors.surface),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(colors.surface),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(colors.surface),
            )
        }
    }
}

@Composable
private fun MessageActionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceRaised),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                color = colors.textFaint,
                fontSize = 12.5.sp,
            )
        }
    }
}
