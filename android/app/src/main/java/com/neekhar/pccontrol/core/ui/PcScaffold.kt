package com.neekhar.pccontrol.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.lerp as lerpTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.PcTheme
import com.neekhar.pccontrol.core.theme.tabular

private val HEADER_FADE_TAIL_DP = 24.dp

/**
 * Shared screen scaffold for all PC Control screens.
 *
 * Features:
 * - Seamless fading header overlaid on top of scrolling content (`bg` 0%..65% -> `bg` 50% at 85%
 *   -> transparent at 100%, extending ~24.dp below the title row) with zero hard divider seam.
 * - Optional collapsing large title (`30.sp` SemiBold -> `18.sp` SemiBold on scroll) driven by
 *   either [listState] or an automatic [NestedScrollConnection].
 * - Optional back button cleanly vertically centered with the title (guaranteed `48.dp` touch
 *   target) and trailing [actions].
 * - Passes the header's height as top [PaddingValues] to [content] so initial items start cleanly
 *   below the header and scroll smoothly underneath the fade.
 */
@Composable
fun PcScaffold(
    modifier: Modifier = Modifier,
    title: String? = null,
    statusText: String? = null,
    statusTone: PcTone = PcTone.Faint,
    statusColor: Color? = null,
    showStatusDot: Boolean = false,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    headerBottom: (@Composable () -> Unit)? = null,
    collapsing: Boolean = true,
    compactHeader: Boolean = false,
    listState: LazyListState? = null,
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    containerColor: Color = PcTheme.colors.bg,
    applyStatusBarPadding: Boolean = true,
    applyNavigationBarPadding: Boolean = true,
    showStatusBarScrim: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val collapseRangePx = remember(density) { with(density) { 56.dp.toPx() } }

    var nestedScrolledPx by remember { mutableFloatStateOf(0f) }
    var headerHeightPx by remember { mutableIntStateOf(0) }

    val nestedScrollConnection = remember(listState, collapseRangePx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (listState == null) {
                    if (consumed.y != 0f) {
                        nestedScrolledPx = (nestedScrolledPx - consumed.y).coerceAtLeast(0f)
                    } else if (available.y > 0f) {
                        // Reached top scroll boundary: snap back to 0 to avoid drift
                        nestedScrolledPx = 0f
                    }
                }
                return Offset.Zero
            }
        }
    }

    val rawScrollFraction by remember(listState, collapseRangePx) {
        derivedStateOf {
            if (listState != null) {
                if (listState.firstVisibleItemIndex > 0) {
                    1f
                } else {
                    (listState.firstVisibleItemScrollOffset.toFloat() / collapseRangePx).coerceIn(0f, 1f)
                }
            } else {
                (nestedScrolledPx / collapseRangePx).coerceIn(0f, 1f)
            }
        }
    }

    val targetCollapseFraction = when {
        compactHeader -> 1f
        collapsing -> rawScrollFraction
        else -> 0f
    }

    val collapseFraction by animateFloatAsState(
        targetValue = targetCollapseFraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "pcScaffoldCollapseFraction",
    )

    val statusBarTopDp = if (applyStatusBarPadding) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    } else {
        0.dp
    }
    val fallbackHeaderHeightDp = if (title != null) {
        val estimatedRowDp = when {
            compactHeader -> 56.dp
            !statusText.isNullOrEmpty() -> 72.dp
            else -> 64.dp
        }
        val estimatedBottomDp = if (headerBottom != null) 52.dp else 0.dp
        statusBarTopDp + estimatedRowDp + estimatedBottomDp + HEADER_FADE_TAIL_DP
    } else {
        0.dp
    }
    val contentTopPadding = if (title != null) {
        if (headerHeightPx > 0) {
            with(density) { headerHeightPx.toDp() }
        } else {
            fallbackHeaderHeightDp
        }
    } else {
        0.dp
    }
    val contentPadding = PaddingValues(top = contentTopPadding)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(containerColor)
            .nestedScroll(nestedScrollConnection),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (applyNavigationBarPadding) Modifier.navigationBarsPadding() else Modifier),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (title == null && applyStatusBarPadding) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                    ) {
                        content(PaddingValues(0.dp))
                    }
                } else {
                    content(contentPadding)
                }

                if (title != null) {
                    PcScaffoldTopBar(
                        title = title,
                        statusText = statusText,
                        statusTone = statusTone,
                        statusColor = statusColor,
                        showStatusDot = showStatusDot,
                        onBack = onBack,
                        actions = actions,
                        headerBottom = headerBottom,
                        collapseFraction = collapseFraction,
                        containerColor = containerColor,
                        applyStatusBarPadding = applyStatusBarPadding,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .onSizeChanged { size ->
                                val atRest = collapseFraction < 0.01f && rawScrollFraction == 0f
                                if (size.height > 0 && (atRest || compactHeader || !collapsing || headerHeightPx == 0)) {
                                    headerHeightPx = size.height
                                }
                            },
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(PcDimens.Space4),
                ) {
                    floatingActionButton()
                }
            }

            bottomBar()
        }

        // For headerless screens, overlay the solid status-bar band + soft gradient scrim
        // at the very top so scrolling content never clashes with the status bar clock.
        if (title == null && showStatusBarScrim) {
            PcStatusBarScrim(
                color = containerColor,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * Top status-bar gradient scrim: solid [color] across the status-bar inset fading smoothly to
 * transparent over [fadeHeight] (default `24.dp`).
 */
@Composable
fun PcStatusBarScrim(
    modifier: Modifier = Modifier,
    color: Color = PcTheme.colors.bg,
    fadeHeight: Dp = 24.dp,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(color),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(fadeHeight)
                .background(
                    Brush.verticalGradient(
                        0.00f to color,
                        0.50f to color.copy(alpha = 0.5f),
                        1.00f to Color.Transparent,
                    ),
                ),
        )
    }
}

@Composable
private fun PcScaffoldTopBar(
    title: String,
    statusText: String?,
    statusTone: PcTone,
    statusColor: Color?,
    showStatusDot: Boolean,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    headerBottom: (@Composable () -> Unit)?,
    collapseFraction: Float,
    containerColor: Color,
    applyStatusBarPadding: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = PcTheme.colors
    val type = PcTheme.type

    val clampedFraction = collapseFraction.coerceIn(0f, 1f)
    val titleStyle = lerpTextStyle(type.screenTitle, type.collapsedTitle, clampedFraction)
    val topPad = lerpDp(12.dp, 4.dp, clampedFraction)

    val statusProgress = (clampedFraction * 1.5f).coerceIn(0f, 1f)
    val statusHeight = lerpDp(18.dp, 0.dp, statusProgress)
    val statusAlpha = (1f - statusProgress).coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(containerColor)
                .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .padding(
                        start = if (onBack != null) 4.dp else PcDimens.ScreenPad,
                        end = PcDimens.ScreenPad,
                        top = topPad,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.text,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (!statusText.isNullOrEmpty() && statusHeight > 0.5.dp) {
                        Row(
                            modifier = Modifier
                                .height(statusHeight)
                                .graphicsLayer { alpha = statusAlpha },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (statusColor != null) {
                                PcStatusDot(color = statusColor, size = 7.dp)
                            } else if (showStatusDot) {
                                PcStatusDot(tone = statusTone, size = 7.dp)
                            }
                            Text(
                                text = statusText,
                                style = type.caption.tabular(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = title,
                        color = colors.text,
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    content = actions,
                )
            }

            if (headerBottom != null) {
                headerBottom()
            }
        }

        // Soft bottom fade tail so scrolling cards dissolve smoothly behind the title
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HEADER_FADE_TAIL_DP)
                .background(
                    Brush.verticalGradient(
                        0.00f to containerColor,
                        0.50f to containerColor.copy(alpha = 0.5f),
                        1.00f to Color.Transparent,
                    ),
                ),
        )
    }
}
