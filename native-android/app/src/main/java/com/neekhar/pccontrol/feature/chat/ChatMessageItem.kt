package com.neekhar.pccontrol.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val UserBubbleShape = RoundedCornerShape(
    topStart = 18.dp,
    topEnd = 18.dp,
    bottomStart = 18.dp,
    bottomEnd = 5.dp,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(
    msg: ChatMessage,
    canRetry: Boolean,
    isQueued: Boolean,
    canSteer: Boolean,
    linkPreviews: Map<String, LinkPreview?>,
    resolveAttachmentSource: (String) -> ChatAttachmentSource,
    onRequestLinkPreview: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onLongPress: (ChatMessage) -> Unit,
    onReply: (ChatMessage) -> Unit,
    onCopyCode: (String) -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onSteer: (ChatMessage) -> Unit,
    onPressReplyQuote: (String) -> Unit,
    onPressImage: (PreviewImageItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val isDark = colors.bg.luminance() < 0.5f

    if (msg.isSystem) {
        SystemBoundaryRow(text = msg.text, modifier = modifier)
        return
    }

    if (!msg.isUser && msg.isPending) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        ) {
            LiveStreamingActivityCard(progress = msg.progress)
        }
        return
    }

    val previewUrl = remember(msg.role, msg.status, msg.text) {
        if (!msg.isSystem && !msg.isPending && !msg.isError) {
            ChatLogic.extractPreviewUrl(msg.text)
        } else {
            null
        }
    }
    val t3Links = remember(msg.role, msg.status, msg.text) {
        if (!msg.isSystem && !msg.isPending && !msg.isError) {
            ChatLogic.extractT3ThreadLinks(msg.text)
        } else {
            emptyList()
        }
    }

    LaunchedEffect(previewUrl) {
        if (previewUrl != null && !ChatLogic.isT3ThreadWebUrl(previewUrl)) {
            onRequestLinkPreview(previewUrl)
        }
    }

    val replyRef = remember(msg.replyTo) { msg.replyRef() }
    val attachments = msg.attachments.orEmpty()
    val hasText = msg.text.isNotBlank()
    val canOpenActions = !msg.isPending && (msg.isUser || msg.isError || hasText || attachments.isNotEmpty())
    val canSwipeReply = remember(msg) { ChatLogic.isReplyableMessage(msg) }

    val triggerLongPress: (() -> Unit)? = if (canOpenActions) {
        { onLongPress(msg) }
    } else {
        null
    }

    if (msg.isUser) {
        val queuedBg = if (isDark) Color(0xFF0E0E0E) else Color(0xFFE2E2E2)
        val queuedBorder = if (isDark) Color(0xFF3D3D3D) else Color(0xFFAEAEAE)

        SwipeableMessageRow(
            enabled = canSwipeReply,
            onSwipeReply = { onReply(msg) },
            modifier = modifier,
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                BoxWithConstraints(
                    contentAlignment = Alignment.CenterEnd,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val bubbleMaxWidth = maxWidth * 0.84f
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .widthIn(max = bubbleMaxWidth)
                            .clip(UserBubbleShape)
                            .background(if (isQueued) queuedBg else colors.surfaceRaised)
                            .then(
                                if (isQueued) {
                                    Modifier.drawBehind {
                                        val strokeWidth = 1.dp.toPx()
                                        val dash = 4.dp.toPx()
                                        drawRoundRect(
                                            color = queuedBorder,
                                            cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
                                            style = Stroke(
                                                width = strokeWidth,
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), 0f),
                                            ),
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .combinedClickable(
                                onClick = {},
                                onLongClick = triggerLongPress,
                            )
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                    ) {
                        if (replyRef != null) {
                            ReplyQuoteBlock(
                                replyRef = replyRef,
                                isUser = true,
                                onClick = { onPressReplyQuote(replyRef.id) },
                            )
                        }
                        if (attachments.isNotEmpty()) {
                            MessageAttachmentGrid(
                                attachments = attachments,
                                isUser = true,
                                resolveAttachmentSource = resolveAttachmentSource,
                                onPressImage = onPressImage,
                                onLongPress = triggerLongPress,
                            )
                        }
                        if (hasText) {
                            MarkdownMessageBody(
                                text = msg.text,
                                isUser = true,
                                muted = isQueued,
                                resolveAttachmentSource = resolveAttachmentSource,
                                onOpenUrl = onOpenUrl,
                                onCopyCode = onCopyCode,
                                onPressImage = onPressImage,
                                onLongPress = triggerLongPress,
                            )
                            if (previewUrl != null && !ChatLogic.isT3ThreadWebUrl(previewUrl)) {
                                linkPreviews[previewUrl]?.let { preview ->
                                    LinkPreviewCard(
                                        preview = preview,
                                        fallbackUrl = previewUrl,
                                        isUser = true,
                                        onOpenUrl = onOpenUrl,
                                    )
                                }
                            }
                        }
                    }
                }

                if (isQueued) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                    ) {
                        Text(
                            text = "Queued",
                            color = colors.textFaint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                        )
                        if (canSteer) {
                            Text(
                                text = "·",
                                color = colors.textFaint,
                                fontSize = 12.sp,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable { onSteer(msg) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Bolt,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = "Send now",
                                    color = colors.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // Bubbleless Assistant Reply
    val hasCompletedProgress = remember(msg.progress) {
        ChatLogic.formatProgressSummary(msg.progress) != null
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        if (hasCompletedProgress && msg.progress != null) {
            CompletedProgressBlock(progress = msg.progress)
        }

        SwipeableMessageRow(
            enabled = canSwipeReply,
            onSwipeReply = { onReply(msg) },
        ) {
            if (msg.isError) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.badSoft)
                        .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = triggerLongPress,
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(colors.bad),
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = msg.error?.takeIf { it.isNotBlank() } ?: "Request failed",
                            color = colors.bad,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                        if (canRetry) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(colors.surface)
                                    .border(0.5.dp, colors.hairline, RoundedCornerShape(999.dp))
                                    .clickable { onRetry(msg) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Retry",
                                    tint = colors.bad,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "Retry",
                                    color = colors.bad,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = triggerLongPress,
                        ),
                ) {
                    if (replyRef != null) {
                        ReplyQuoteBlock(
                            replyRef = replyRef,
                            isUser = false,
                            onClick = { onPressReplyQuote(replyRef.id) },
                        )
                    }
                    if (attachments.isNotEmpty()) {
                        MessageAttachmentGrid(
                            attachments = attachments,
                            isUser = false,
                            resolveAttachmentSource = resolveAttachmentSource,
                            onPressImage = onPressImage,
                            onLongPress = triggerLongPress,
                        )
                    }
                    if (hasText) {
                        MarkdownMessageBody(
                            text = msg.text,
                            isUser = false,
                            muted = false,
                            resolveAttachmentSource = resolveAttachmentSource,
                            onOpenUrl = onOpenUrl,
                            onCopyCode = onCopyCode,
                            onPressImage = onPressImage,
                            onLongPress = triggerLongPress,
                        )

                        if (t3Links.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((label, url) in t3Links) {
                                    T3ThreadLinkChip(
                                        label = label,
                                        onClick = { onOpenUrl(url) },
                                    )
                                }
                            }
                        }

                        if (previewUrl != null && !ChatLogic.isT3ThreadWebUrl(previewUrl)) {
                            linkPreviews[previewUrl]?.let { preview ->
                                LinkPreviewCard(
                                    preview = preview,
                                    fallbackUrl = previewUrl,
                                    isUser = false,
                                    onOpenUrl = onOpenUrl,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Swipe-to-reply row

@Composable
private fun SwipeableMessageRow(
    enabled: Boolean,
    onSwipeReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalPcColors.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val dragDp = remember { Animatable(0f) }
    var thresholdReached by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.pointerInput(onSwipeReply) {
                        var rawDxDp = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                rawDxDp = 0f
                                thresholdReached = false
                            },
                            onHorizontalDrag = { _, dragAmountPx ->
                                val deltaDp = with(density) { dragAmountPx.toDp().value }
                                rawDxDp = (rawDxDp + deltaDp).coerceAtLeast(0f)
                                val damped = ChatLogic.computeSwipeReplyOffset(rawDxDp)
                                scope.launch { dragDp.snapTo(damped) }
                                if (!thresholdReached && damped >= SWIPE_REPLY_THRESHOLD) {
                                    thresholdReached = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                } else if (thresholdReached && damped < SWIPE_REPLY_RESET_THRESHOLD) {
                                    thresholdReached = false
                                }
                            },
                            onDragEnd = {
                                val triggered = thresholdReached
                                thresholdReached = false
                                scope.launch {
                                    dragDp.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium,
                                        ),
                                    )
                                }
                                if (triggered) onSwipeReply()
                            },
                            onDragCancel = {
                                thresholdReached = false
                                scope.launch {
                                    dragDp.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        if (enabled && dragDp.value > 4f) {
            val progress = ((dragDp.value - 12f) / (SWIPE_REPLY_THRESHOLD - 12f)).coerceIn(0f, 1f)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset { IntOffset(x = (progress * 8.dp.toPx()).roundToInt(), y = 0) }
                    .size(32.dp)
                    .alpha(progress)
                    .clip(CircleShape)
                    .background(if (thresholdReached) colors.accentSoft else colors.surfaceRaised),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Reply,
                    contentDescription = "Reply",
                    tint = if (thresholdReached) colors.accent else colors.textDim,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(x = dragDp.value.dp.roundToPx(), y = 0) },
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------- Live Activity Shimmer & Completed Progress

@Composable
fun LiveStreamingActivityCard(
    progress: ChatProgress?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    var expanded by remember { mutableStateOf(false) }
    val steps = progress?.steps.orEmpty()
    val currentStep = ChatLogic.currentProgressStepText(progress)

    val transition = rememberInfiniteTransition(label = "activityShimmer")
    val shimmerPhase by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerPhase",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            colors.textDim,
            colors.text,
            colors.textDim,
        ),
        start = Offset(shimmerPhase * 420f - 140f, 0f),
        end = Offset(shimmerPhase * 420f + 140f, 0f),
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .then(if (steps.isNotEmpty()) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(vertical = 3.dp),
        ) {
            // Subtle pulsing status dot
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.65f + 0.35f * (1f - kotlin.math.abs(shimmerPhase - 0.5f).coerceIn(0f, 1f)))),
            )
            Text(
                text = currentStep,
                style = TextStyle(
                    brush = shimmerBrush,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (steps.isNotEmpty()) {
                Text(
                    text = "${steps.size}",
                    style = TabularNumStyle,
                    color = colors.textFaint,
                    fontSize = 11.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.surfaceRaised)
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Hide steps" else "Show steps",
                    tint = colors.textFaint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded && steps.isNotEmpty()) {
            ProgressStepTimeline(steps = steps)
        }
    }
}

@Composable
private fun CompletedProgressBlock(progress: ChatProgress) {
    val colors = LocalPcColors.current
    var expanded by remember { mutableStateOf(false) }
    val summary = ChatLogic.formatProgressSummary(progress) ?: return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        ) {
            Text(
                text = summary,
                style = TabularNumStyle,
                color = colors.textFaint,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.KeyboardArrowRight,
                contentDescription = if (expanded) "Hide steps" else "Show steps",
                tint = colors.textFaint,
                modifier = Modifier.size(15.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            ProgressStepTimeline(steps = progress.steps)
        }
    }
}

@Composable
private fun ProgressStepTimeline(steps: List<ChatProgressStep>) {
    val colors = LocalPcColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = 3.dp),
    ) {
        Box(
            modifier = Modifier
                .width(1.5.dp)
                .fillMaxHeight()
                .background(colors.hairline),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
        ) {
            for (step in steps) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(colors.textFaint),
                    )
                    Text(
                        text = step.text,
                        color = colors.textDim,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Rich Native Markdown Renderer

@Composable
private fun MarkdownMessageBody(
    text: String,
    isUser: Boolean,
    muted: Boolean,
    resolveAttachmentSource: (String) -> ChatAttachmentSource,
    onOpenUrl: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    onPressImage: (PreviewImageItem) -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val segments = remember(text) { ChatLogic.parseMessageSegments(text) }
    if (segments.isEmpty()) return

    Column(
        verticalArrangement = Arrangement.spacedBy(if (segments.size > 1) 10.dp else 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        segments.forEach { seg ->
            when (seg) {
                is MessageSegment.CodeBlock -> CodeBlockCard(
                    lang = seg.lang,
                    code = seg.code,
                    isUser = isUser,
                    onCopyCode = onCopyCode,
                    onLongPress = onLongPress,
                )

                is MessageSegment.Table -> MarkdownTableCard(
                    headers = seg.headers,
                    alignments = seg.alignments,
                    rows = seg.rows,
                    isUser = isUser,
                    onOpenUrl = onOpenUrl,
                    onLongPress = onLongPress,
                )

                is MessageSegment.Image -> MarkdownImageCard(
                    url = seg.url,
                    alt = seg.alt,
                    linkUrl = seg.linkUrl,
                    isUser = isUser,
                    resolveAttachmentSource = resolveAttachmentSource,
                    onPressImage = onPressImage,
                    onLongPress = onLongPress,
                )

                is MessageSegment.Text -> MarkdownTextBlock(
                    text = seg.text,
                    isUser = isUser,
                    muted = muted,
                    onOpenUrl = onOpenUrl,
                )
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Blockquote(val lines: List<String>) : MdBlock
    data class BulletItem(val indent: Int, val text: String) : MdBlock
    data class OrderedItem(val indent: Int, val marker: String, val text: String) : MdBlock
    data object HorizontalRule : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

private val mdHeadingRe = Regex("""^(#{1,6})\s+(.+)$""")
private val mdHrRe = Regex("""^([-*_])\1{2,}\s*$""")
private val mdBulletRe = Regex("""^([ \t]*)[-*+]\s+(.+)$""")
private val mdOrderedRe = Regex("""^([ \t]*)(\d+[.)])\s+(.+)$""")
private val mdQuoteRe = Regex("""^>\s?(.*)$""")

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val normalized = ChatLogic.normalizeMarkdownForDisplay(raw).replace("\r\n", "\n")
    val lines = normalized.split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paraBuf = mutableListOf<String>()

    fun flushPara() {
        if (paraBuf.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paraBuf.joinToString("\n")))
            paraBuf.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            flushPara()
            i += 1
            continue
        }
        if (mdHrRe.matches(trimmed)) {
            flushPara()
            blocks.add(MdBlock.HorizontalRule)
            i += 1
            continue
        }
        val heading = mdHeadingRe.matchEntire(trimmed)
        if (heading != null) {
            flushPara()
            blocks.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
            i += 1
            continue
        }
        if (mdQuoteRe.matches(line)) {
            flushPara()
            val quoteLines = mutableListOf<String>()
            while (i < lines.size) {
                val qm = mdQuoteRe.matchEntire(lines[i]) ?: break
                quoteLines.add(qm.groupValues[1])
                i += 1
            }
            blocks.add(MdBlock.Blockquote(quoteLines))
            continue
        }
        val bullet = mdBulletRe.matchEntire(line)
        if (bullet != null) {
            flushPara()
            val indentLevel = (bullet.groupValues[1].replace("\t", "  ").length / 2).coerceAtMost(3)
            blocks.add(MdBlock.BulletItem(indentLevel, bullet.groupValues[2]))
            i += 1
            continue
        }
        val ordered = mdOrderedRe.matchEntire(line)
        if (ordered != null) {
            flushPara()
            val indentLevel = (ordered.groupValues[1].replace("\t", "  ").length / 2).coerceAtMost(3)
            blocks.add(MdBlock.OrderedItem(indentLevel, ordered.groupValues[2], ordered.groupValues[3]))
            i += 1
            continue
        }
        paraBuf.add(line)
        i += 1
    }
    flushPara()
    return blocks
}

@Composable
private fun MarkdownTextBlock(
    text: String,
    isUser: Boolean,
    muted: Boolean,
    onOpenUrl: (String) -> Unit,
) {
    val colors = LocalPcColors.current
    val textColor = if (muted) colors.textDim else colors.text
    val bulletColor = if (muted) colors.textFaint else colors.textDim
    val inlineCodeBg = if (isUser) colors.bg else colors.surfaceRaised
    val lineHeight = if (isUser) 21.sp else 22.5.sp
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(
        verticalArrangement = Arrangement.spacedBy(if (isUser) 5.dp else 7.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> {
                    val fontSize = when (block.level) {
                        1 -> 18.sp
                        2 -> 17.sp
                        3 -> 16.sp
                        4 -> 15.sp
                        else -> 14.5.sp
                    }
                    val weight = if (block.level <= 4) FontWeight.SemiBold else FontWeight.Medium
                    Text(
                        text = buildInlineAnnotatedString(
                            raw = block.text,
                            textColor = if (block.level == 6) colors.textDim else textColor,
                            accentColor = colors.accent,
                            codeBg = inlineCodeBg,
                            onOpenUrl = onOpenUrl,
                        ),
                        fontSize = fontSize,
                        lineHeight = (fontSize.value + 6f).sp,
                        fontWeight = weight,
                    )
                }

                is MdBlock.HorizontalRule -> {
                    HorizontalDivider(
                        color = colors.hairline,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                is MdBlock.Blockquote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isUser) colors.bg else colors.surface),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.5.dp)
                                .fillMaxHeight()
                                .background(bulletColor),
                        )
                        Text(
                            text = buildInlineAnnotatedString(
                                raw = block.lines.joinToString("\n"),
                                textColor = colors.textDim,
                                accentColor = colors.accent,
                                codeBg = inlineCodeBg,
                                onOpenUrl = onOpenUrl,
                            ),
                            fontSize = 14.5.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }

                is MdBlock.BulletItem -> {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.indent * 14).dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(bulletColor),
                        )
                        Text(
                            text = buildInlineAnnotatedString(
                                raw = block.text,
                                textColor = textColor,
                                accentColor = colors.accent,
                                codeBg = inlineCodeBg,
                                onOpenUrl = onOpenUrl,
                            ),
                            fontSize = 15.sp,
                            lineHeight = lineHeight,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.OrderedItem -> {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.indent * 14).dp),
                    ) {
                        Text(
                            text = block.marker,
                            style = TabularNumStyle,
                            color = bulletColor,
                            fontSize = 14.sp,
                            lineHeight = lineHeight,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.widthIn(min = 20.dp),
                        )
                        Text(
                            text = buildInlineAnnotatedString(
                                raw = block.text,
                                textColor = textColor,
                                accentColor = colors.accent,
                                codeBg = inlineCodeBg,
                                onOpenUrl = onOpenUrl,
                            ),
                            fontSize = 15.sp,
                            lineHeight = lineHeight,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = buildInlineAnnotatedString(
                            raw = block.text,
                            textColor = textColor,
                            accentColor = colors.accent,
                            codeBg = inlineCodeBg,
                            onOpenUrl = onOpenUrl,
                        ),
                        fontSize = 15.sp,
                        lineHeight = lineHeight,
                    )
                }
            }
        }
    }
}

private fun buildInlineAnnotatedString(
    raw: String,
    textColor: Color,
    accentColor: Color,
    codeBg: Color,
    onOpenUrl: (String) -> Unit,
): AnnotatedString {
    val tokens = ChatLogic.parseInlineTokens(raw)
    return buildAnnotatedString {
        for (tok in tokens) {
            when (tok) {
                is InlineToken.Text -> {
                    withStyle(SpanStyle(color = textColor)) {
                        append(tok.text)
                    }
                }

                is InlineToken.Bold -> {
                    withStyle(SpanStyle(color = textColor, fontWeight = FontWeight.SemiBold)) {
                        append(tok.text)
                    }
                }

                is InlineToken.Italic -> {
                    withStyle(SpanStyle(color = textColor, fontStyle = FontStyle.Italic)) {
                        append(tok.text)
                    }
                }

                is InlineToken.Strike -> {
                    withStyle(SpanStyle(color = textColor, textDecoration = TextDecoration.LineThrough)) {
                        append(tok.text)
                    }
                }

                is InlineToken.Code -> {
                    withStyle(
                        SpanStyle(
                            color = textColor,
                            background = codeBg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    ) {
                        append(" ${tok.code} ")
                    }
                }

                is InlineToken.Link -> {
                    val resolved = ChatLogic.resolveChatLinkUrl(tok.url)
                    if (resolved != null) {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = resolved,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = accentColor,
                                        textDecoration = TextDecoration.Underline,
                                    )
                                ),
                                linkInteractionListener = { onOpenUrl(resolved) },
                            )
                        ) {
                            append(tok.label)
                        }
                    } else {
                        withStyle(SpanStyle(color = accentColor)) {
                            append(tok.label)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Code Block Card

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeBlockCard(
    lang: String,
    code: String,
    isUser: Boolean,
    onCopyCode: (String) -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val colors = LocalPcColors.current
    var justCopied by remember(code) { mutableStateOf(false) }

    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1500L)
            justCopied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isUser) colors.bg else colors.surface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                text = lang.ifBlank { "code" },
                color = colors.textFaint,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.surfaceRaised)
                    .clickable {
                        onCopyCode(code)
                        justCopied = true
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Icon(
                    imageVector = if (justCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = if (justCopied) "Copied" else "Copy code",
                    tint = if (justCopied) colors.accent else colors.textDim,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = if (justCopied) "Copied" else "Copy",
                    color = if (justCopied) colors.accent else colors.textDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .combinedClickable(
                    onClick = {
                        onCopyCode(code)
                        justCopied = true
                    },
                    onLongClick = onLongPress,
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            Text(
                text = code,
                color = colors.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- GFM Table Card

private fun cellDisplayLength(raw: String): Int {
    var total = 0
    for (tok in ChatLogic.parseInlineTokens(raw)) {
        total += when (tok) {
            is InlineToken.Code -> tok.code.length
            is InlineToken.Link -> tok.label.length
            is InlineToken.Bold -> tok.text.length
            is InlineToken.Italic -> tok.text.length
            is InlineToken.Strike -> tok.text.length
            is InlineToken.Text -> tok.text.length
        }
    }
    return total
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkdownTableCard(
    headers: List<String>,
    alignments: List<TableAlignment>,
    rows: List<List<String>>,
    isUser: Boolean,
    onOpenUrl: (String) -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val colors = LocalPcColors.current
    val surfaceBg = if (isUser) colors.bg else colors.surface
    val headerBg = if (isUser) colors.surface else colors.surfaceRaised
    val inlineCodeBg = if (isUser) colors.surface else colors.surfaceRaised

    val colWidths = remember(headers, rows) {
        headers.mapIndexed { colIdx, header ->
            var maxLen = cellDisplayLength(header)
            for (row in rows) {
                val len = cellDisplayLength(row.getOrElse(colIdx) { "" })
                if (len > maxLen) maxLen = len
            }
            min(220, max(72, ceil(maxLen * 7.8).toInt() + 24)).dp
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(surfaceBg)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            // Header row
            Row(
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .background(headerBg),
            ) {
                headers.forEachIndexed { colIdx, cell ->
                    val align = alignments.getOrElse(colIdx) { TableAlignment.LEFT }
                    if (colIdx > 0) {
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .fillMaxHeight()
                                .background(colors.hairline),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(colWidths[colIdx])
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = buildInlineAnnotatedString(
                                raw = cell,
                                textColor = colors.text,
                                accentColor = colors.accent,
                                codeBg = inlineCodeBg,
                                onOpenUrl = onOpenUrl,
                            ),
                            style = TabularNumStyle.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = align.toTextAlign(),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Data rows
            rows.forEach { row ->
                HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    headers.indices.forEach { colIdx ->
                        val align = alignments.getOrElse(colIdx) { TableAlignment.LEFT }
                        if (colIdx > 0) {
                            Box(
                                modifier = Modifier
                                    .width(0.5.dp)
                                    .fillMaxHeight()
                                    .background(colors.hairline),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(colWidths[colIdx])
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = buildInlineAnnotatedString(
                                    raw = row.getOrElse(colIdx) { "" },
                                    textColor = colors.text,
                                    accentColor = colors.accent,
                                    codeBg = inlineCodeBg,
                                    onOpenUrl = onOpenUrl,
                                ),
                                style = TabularNumStyle.copy(
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.sp,
                                    fontWeight = FontWeight.Normal,
                                    textAlign = align.toTextAlign(),
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun TableAlignment.toTextAlign(): TextAlign = when (this) {
    TableAlignment.LEFT -> TextAlign.Start
    TableAlignment.CENTER -> TextAlign.Center
    TableAlignment.RIGHT -> TextAlign.End
}

// ---------------------------------------------------------------- Inline Markdown Image Card

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkdownImageCard(
    url: String,
    alt: String,
    linkUrl: String?,
    isUser: Boolean,
    resolveAttachmentSource: (String) -> ChatAttachmentSource,
    onPressImage: (PreviewImageItem) -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val colors = LocalPcColors.current
    val context = LocalContext.current
    val source = remember(url) { resolveAttachmentSource(url) }
    val displayName = remember(alt, url) { ChatLogic.deriveImageDisplayName(alt, url) }

    var retryNonce by remember(url) { mutableIntStateOf(0) }
    var naturalW by remember(url) { mutableIntStateOf(0) }
    var naturalH by remember(url) { mutableIntStateOf(0) }
    var isLoading by remember(url, retryNonce) { mutableStateOf(true) }
    var isFailed by remember(url, retryNonce) { mutableStateOf(false) }

    val imageRequest = remember(source, retryNonce) {
        ImageRequest.Builder(context)
            .data(source.uri)
            .apply {
                if (source.headers.isNotEmpty()) {
                    val h = NetworkHeaders.Builder().apply {
                        source.headers.forEach { (k, v) -> set(k, v) }
                    }.build()
                    httpHeaders(h)
                }
            }
            .crossfade(true)
            .build()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val containerDp = maxWidth.value.roundToInt().coerceAtLeast(160)
        val boxSize = remember(naturalW, naturalH, containerDp) {
            ChatLogic.computeInlineImageSize(naturalW, naturalH, containerDp, INLINE_IMAGE_MAX_HEIGHT_DP)
        }
        val surfaceBg = if (isUser) colors.bg else colors.surface

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(boxSize.width.dp)
                .height(if (isFailed) min(boxSize.height, 140).dp else boxSize.height.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(surfaceBg)
                .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = {
                        if (isFailed) {
                            retryNonce += 1
                        } else {
                            onPressImage(
                                PreviewImageItem(
                                    url = url,
                                    name = displayName,
                                    linkUrl = linkUrl,
                                )
                            )
                        }
                    },
                    onLongClick = onLongPress,
                ),
        ) {
            if (isFailed) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BrokenImage,
                        contentDescription = null,
                        tint = colors.textFaint,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = "Tap to retry",
                        color = colors.textFaint,
                        fontSize = 12.sp,
                    )
                }
            } else {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = displayName,
                    contentScale = ContentScale.Fit,
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Loading -> {
                                isLoading = true
                                isFailed = false
                            }

                            is AsyncImagePainter.State.Success -> {
                                isLoading = false
                                isFailed = false
                                val img = state.result.image
                                if (img.width > 0 && img.height > 0) {
                                    naturalW = img.width
                                    naturalH = img.height
                                }
                            }

                            is AsyncImagePainter.State.Error -> {
                                isLoading = false
                                isFailed = true
                            }

                            is AsyncImagePainter.State.Empty -> Unit
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        color = colors.textFaint,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Message Attachments Grid

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageAttachmentGrid(
    attachments: List<ChatAttachment>,
    isUser: Boolean,
    resolveAttachmentSource: (String) -> ChatAttachmentSource,
    onPressImage: (PreviewImageItem) -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val colors = LocalPcColors.current
    val context = LocalContext.current
    val images = remember(attachments) { attachments.filter { it.isImage } }
    val files = remember(attachments) { attachments.filterNot { it.isImage } }
    val surfaceBg = if (isUser) colors.bg else colors.surface

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (images.isNotEmpty()) {
            val chunked = images.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (rowImgs in chunked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (img in rowImgs) {
                            val src = remember(img.url) { resolveAttachmentSource(img.url) }
                            val req = remember(src) {
                                ImageRequest.Builder(context)
                                    .data(src.uri)
                                    .apply {
                                        if (src.headers.isNotEmpty()) {
                                            val h = NetworkHeaders.Builder().apply {
                                                src.headers.forEach { (k, v) -> set(k, v) }
                                            }.build()
                                            httpHeaders(h)
                                        }
                                    }
                                    .crossfade(true)
                                    .build()
                            }
                            val single = images.size == 1
                            AsyncImage(
                                model = req,
                                contentDescription = img.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .then(
                                        if (single) Modifier.size(width = 220.dp, height = 165.dp)
                                        else Modifier.size(112.dp)
                                    )
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceBg)
                                    .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = {
                                            onPressImage(
                                                PreviewImageItem(
                                                    url = img.url,
                                                    name = img.name,
                                                    mimeType = img.mimeType,
                                                )
                                            )
                                        },
                                        onLongClick = onLongPress,
                                    ),
                            )
                        }
                    }
                }
            }
        }

        for (file in files) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(surfaceBg)
                    .border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(17.dp),
                )
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = file.name,
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = ChatLogic.formatAttachmentSize(file.sizeBytes),
                        style = TabularNumStyle,
                        color = colors.textFaint,
                        fontSize = 11.5.sp,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Reply Quote, T3 Thread Chip, OG Link Preview & Footer

@Composable
private fun ReplyQuoteBlock(
    replyRef: ChatReplyRef,
    isUser: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isUser) colors.bg else colors.surface)
            .then(
                if (!isUser) Modifier.border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp)) else Modifier
            )
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .fillMaxHeight()
                .background(colors.accent),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            Text(
                text = ChatLogic.replySenderLabel(replyRef),
                color = colors.accent,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (replyRef.text.isNotBlank()) {
                Text(
                    text = replyRef.text,
                    color = colors.textDim,
                    fontSize = 12.5.sp,
                    lineHeight = 16.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun T3ThreadLinkChip(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Link,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = colors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = "T3",
            color = colors.accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(colors.accentSoft)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
        Icon(
            imageVector = Icons.Rounded.OpenInNew,
            contentDescription = "Open in T3 Code",
            tint = colors.textFaint,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun LinkPreviewCard(
    preview: LinkPreview,
    fallbackUrl: String,
    isUser: Boolean,
    onOpenUrl: (String) -> Unit,
) {
    val colors = LocalPcColors.current
    var imgFailed by remember(preview.image) { mutableStateOf(false) }
    val showImage = !preview.image.isNullOrBlank() && !imgFailed
    if (preview.title.isNullOrBlank() && !showImage) return

    val targetUrl = if (ChatLogic.isHttpUrl(preview.url)) preview.url else fallbackUrl
    val hostLabel = preview.siteName?.takeIf { it.isNotBlank() } ?: preview.domain

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isUser) colors.bg else colors.surface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp))
            .clickable { onOpenUrl(targetUrl) },
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(colors.accent),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (hostLabel.isNotBlank()) {
                    Text(
                        text = hostLabel,
                        color = colors.textFaint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!preview.title.isNullOrBlank()) {
                    Text(
                        text = preview.title,
                        color = colors.text,
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!preview.description.isNullOrBlank()) {
                    Text(
                        text = preview.description,
                        color = colors.textDim,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (showImage && preview.image != null) {
                AsyncImage(
                    model = preview.image,
                    contentDescription = preview.title ?: hostLabel,
                    contentScale = ContentScale.Crop,
                    onError = { imgFailed = true },
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceRaised)
                        .border(0.5.dp, colors.hairline, RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}

@Composable
private fun SystemBoundaryRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        HorizontalDivider(color = colors.hairline, thickness = 0.5.dp, modifier = Modifier.weight(1f))
        Text(
            text = text,
            color = colors.textFaint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        HorizontalDivider(color = colors.hairline, thickness = 0.5.dp, modifier = Modifier.weight(1f))
    }
}
