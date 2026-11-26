package com.neekhar.pccontrol.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neekhar.pccontrol.core.theme.PcColors
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.PcTheme
import com.neekhar.pccontrol.core.theme.TabularNumStyle

enum class PcTone {
    Ok,
    Warn,
    Bad,
    Accent,
    Dim,
    Faint,
}

enum class PcButtonVariant {
    Primary,
    Subtle,
    Danger,
    Ghost,
}

fun PcColors.toneForeground(tone: PcTone): Color = when (tone) {
    PcTone.Ok -> ok
    PcTone.Warn -> warn
    PcTone.Bad -> bad
    PcTone.Accent -> accent
    PcTone.Dim -> textDim
    PcTone.Faint -> textFaint
}

fun PcColors.toneSoftBackground(tone: PcTone): Color = when (tone) {
    PcTone.Ok -> okSoft
    PcTone.Warn -> warnSoft
    PcTone.Bad -> badSoft
    PcTone.Accent -> accentSoft
    PcTone.Dim -> surfaceRaised
    PcTone.Faint -> surfaceRaised
}

/**
 * Standard PC Control card: surface fill, no border, 16.dp radius.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PcCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(PcDimens.Space4),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PcTheme.colors
    val shape = RoundedCornerShape(PcDimens.RadiusMd)
    val baseModifier = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(colors.surface)

    val interactiveModifier = if (onClick != null || onLongClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        baseModifier.combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(color = colors.ripple),
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    } else {
        baseModifier
    }

    Column(
        modifier = interactiveModifier.padding(contentPadding),
        content = content,
    )
}

/**
 * Grouped list container: surface fill, 16.dp radius, clipped so rows sit edge-to-edge inside.
 */
@Composable
fun PcListCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PcTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PcDimens.RadiusMd))
            .background(colors.surface),
        content = content,
    )
}

/**
 * Single row inside a [PcListCard]. Min height 56.dp, hairline separator when [showDivider] is true.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PcListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    showChevron: Boolean? = null,
    danger: Boolean = false,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = PcTheme.colors
    val chevronVisible = showChevron ?: (onClick != null && trailing == null && trailingContent == null)

    val clickableModifier = if (onClick != null || onLongClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(color = colors.ripple),
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    } else {
        Modifier
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(clickableModifier)
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = PcDimens.Space4, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (leading != null) {
                leading()
            }
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (danger) colors.bad else colors.textDim,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    color = if (danger) colors.bad else colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    subtitleContent != null -> subtitleContent()
                    subtitle != null -> Text(
                        text = subtitle,
                        color = colors.textFaint,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailingContent != null) {
                trailingContent()
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    color = colors.textDim,
                    style = TabularNumStyle.copy(
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (chevronVisible) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textFaint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = colors.hairline,
            )
        }
    }
}

@Immutable
data class PcStatItem(
    val label: String,
    val value: String,
    val unit: String? = null,
    val valueColor: Color? = null,
)

/**
 * Multi-column stat strip in one surface card separated by vertical hairlines.
 */
@Composable
fun PcStatStrip(
    items: List<PcStatItem>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = PcTheme.colors
    val shape = RoundedCornerShape(PcDimens.RadiusMd)
    val baseModifier = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(colors.surface)
        .let { m ->
            if (onClick != null) {
                val interactionSource = remember { MutableInteractionSource() }
                m.clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = colors.ripple),
                    onClick = onClick,
                )
            } else {
                m
            }
        }

    Row(
        modifier = baseModifier
            .height(IntrinsicSize.Min)
            .padding(vertical = 14.dp),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    thickness = 0.5.dp,
                    color = colors.hairline,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = PcDimens.Space4),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.label,
                    color = colors.textFaint,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.value,
                        color = item.valueColor ?: colors.text,
                        style = TabularNumStyle.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 24.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!item.unit.isNullOrEmpty()) {
                        Text(
                            text = item.unit,
                            color = colors.textDim,
                            style = TabularNumStyle.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                lineHeight = 18.sp,
                            ),
                            maxLines = 1,
                            modifier = Modifier.padding(bottom = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Small status / metadata pill with soft background.
 */
@Composable
fun PcPill(
    label: String,
    modifier: Modifier = Modifier,
    tone: PcTone = PcTone.Dim,
    icon: ImageVector? = null,
    showDot: Boolean = false,
) {
    val colors = PcTheme.colors
    val fg = colors.toneForeground(tone)
    val bg = colors.toneSoftBackground(tone)

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when {
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(13.dp),
            )
            showDot -> PcStatusDot(tone = tone, size = 6.dp)
        }
        Text(
            text = label,
            color = fg,
            style = TabularNumStyle.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Action button matching DESIGN.md:
 * - Primary: accent fill + black text
 * - Subtle: surfaceRaised fill + primary text
 * - Danger: badSoft fill + bad text
 * - Ghost: transparent + accent text
 */
@Composable
fun PcButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PcButtonVariant = PcButtonVariant.Primary,
    icon: ImageVector? = null,
    busy: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val colors = PcTheme.colors
    val (bg, fg) = when (variant) {
        PcButtonVariant.Primary -> colors.accent to colors.onAccent
        PcButtonVariant.Subtle -> colors.surfaceRaised to colors.text
        PcButtonVariant.Danger -> colors.badSoft to colors.bad
        PcButtonVariant.Ghost -> Color.Transparent to colors.accent
    }
    val isEnabled = enabled && !busy
    val height = if (compact) 36.dp else 44.dp
    val radius = if (compact) 10.dp else PcDimens.RadiusSm
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(bg)
            .alpha(if (isEnabled) 1f else 0.45f)
            .clickable(
                enabled = isEnabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = ripple(color = colors.ripple),
                onClick = onClick,
            )
            .padding(horizontal = if (compact) PcDimens.Space3 else PcDimens.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(if (compact) 15.dp else 18.dp),
                strokeWidth = 2.dp,
                color = fg,
            )
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(if (compact) 16.dp else 19.dp),
            )
        }
        Text(
            text = label,
            color = fg,
            fontSize = if (compact) 13.5.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Immutable
data class PcSegmentOption<T>(
    val id: T,
    val label: String,
    val icon: ImageVector? = null,
)

/**
 * Surface track with raised selected segment.
 */
@Composable
fun <T> PcSegmentedControl(
    options: List<PcSegmentOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PcTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PcDimens.RadiusSm))
            .background(colors.surface)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option.id == selected
            val interactionSource = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) colors.surfaceRaised else Color.Transparent)
                    .clickable(
                        role = Role.Tab,
                        interactionSource = interactionSource,
                        indication = ripple(color = colors.ripple),
                        onClick = { onSelect(option.id) },
                    )
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            ) {
                if (option.icon != null) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        tint = if (isSelected) colors.text else colors.textDim,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    text = option.label,
                    color = if (isSelected) colors.text else colors.textDim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 7.dp status dot indicator (ok / warn / bad / accent / dim / faint).
 */
@Composable
fun PcStatusDot(
    modifier: Modifier = Modifier,
    tone: PcTone = PcTone.Faint,
    size: Dp = 7.dp,
) {
    val colors = PcTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.toneForeground(tone)),
    )
}

/**
 * Circular outlined status ring (used on Services summary, etc.).
 */
@Composable
fun PcStatusRing(
    modifier: Modifier = Modifier,
    tone: PcTone = PcTone.Ok,
    icon: ImageVector? = null,
) {
    val colors = PcTheme.colors
    val fg = colors.toneForeground(tone)
    val resolvedIcon = icon ?: when (tone) {
        PcTone.Bad, PcTone.Warn -> Icons.Filled.Warning
        else -> Icons.Filled.Check
    }
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(CircleShape)
            .border(width = 2.dp, color = fg, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = resolvedIcon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Screen header per DESIGN.md:
 * 30sp SemiBold (-0.5sp tracking), left-aligned under an optional 12.5sp status line with 7.dp dot.
 */
@Composable
fun PcScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    statusTone: PcTone = PcTone.Faint,
    showStatusDot: Boolean = false,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = PcTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (onBack != null) 6.dp else PcDimens.ScreenPad,
                end = PcDimens.ScreenPad,
                top = PcDimens.Space4,
                bottom = PcDimens.Space4,
            ),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.text,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!statusText.isNullOrEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (showStatusDot) {
                        PcStatusDot(tone = statusTone, size = 7.dp)
                    }
                    Text(
                        text = statusText,
                        color = colors.textDim,
                        style = TabularNumStyle.copy(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = title,
                color = colors.text,
                fontSize = 30.sp,
                lineHeight = 35.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = actions,
        )
    }
}

/**
 * Sentence-case section label sitting outside cards (12.5sp Medium, textDim).
 */
@Composable
fun PcSectionLabel(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = PcTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textDim,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = label,
            color = colors.textDim,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * Empty state placeholder centered in screen or section.
 */
@Composable
fun PcEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = PcTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PcDimens.Space6, vertical = PcDimens.Space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PcDimens.Space2),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (!body.isNullOrEmpty()) {
            Text(
                text = body,
                color = colors.textDim,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(modifier = Modifier.height(PcDimens.Space2))
            action()
        }
    }
}

/**
 * Inline error / warning / info banner with optional action button.
 */
@Composable
fun PcErrorBanner(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    tone: PcTone = PcTone.Bad,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = PcTheme.colors
    val fg = colors.toneForeground(tone)
    val bg = colors.toneSoftBackground(tone)
    val resolvedIcon = icon ?: when (tone) {
        PcTone.Bad -> Icons.Filled.Error
        PcTone.Warn -> Icons.Filled.Warning
        PcTone.Ok, PcTone.Accent -> Icons.Filled.CheckCircle
        else -> Icons.Filled.Info
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PcDimens.RadiusMd))
            .background(bg)
            .padding(PcDimens.Space4),
        horizontalArrangement = Arrangement.spacedBy(PcDimens.Space3),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = resolvedIcon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(20.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                color = fg,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (!message.isNullOrEmpty()) {
                Text(
                    text = message,
                    color = colors.textDim,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )
            }
            if (!actionLabel.isNullOrEmpty() && onAction != null) {
                Spacer(modifier = Modifier.height(4.dp))
                PcButton(
                    label = actionLabel,
                    onClick = onAction,
                    variant = if (tone == PcTone.Bad) PcButtonVariant.Danger else PcButtonVariant.Subtle,
                    compact = true,
                )
            }
        }
    }
}

/**
 * Clean confirmation dialog using PC Control tokens (no Material blue/purple defaults).
 */
@Composable
fun PcConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Confirm",
    dismissLabel: String = "Cancel",
    confirmVariant: PcButtonVariant = PcButtonVariant.Primary,
    confirmBusy: Boolean = false,
) {
    val colors = PcTheme.colors
    Dialog(
        onDismissRequest = { if (!confirmBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PcDimens.RadiusMd))
                .background(colors.surface)
                .border(0.5.dp, colors.hairline, RoundedCornerShape(PcDimens.RadiusMd))
                .padding(PcDimens.Space4),
            verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
        ) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                color = colors.textDim,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
            ) {
                PcButton(
                    label = dismissLabel,
                    onClick = onDismiss,
                    variant = PcButtonVariant.Subtle,
                    enabled = !confirmBusy,
                    modifier = Modifier.weight(1f),
                )
                PcButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    variant = confirmVariant,
                    busy = confirmBusy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Thin progress bar (4.dp default). Fill uses primary text color, switching to warn above 85%.
 */
@Composable
fun PcProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    height: Dp = 4.dp,
) {
    val colors = PcTheme.colors
    val clamped = progress.coerceIn(0f, 1f)
    val fill = color ?: if (clamped > 0.85f) colors.warn else colors.text
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(colors.surfaceRaised),
    ) {
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(fill),
            )
        }
    }
}

/**
 * Key-value metadata row used in cards and detail sheets.
 */
@Composable
fun PcKeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    val colors = PcTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = key,
            color = colors.textDim,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Normal,
        )
        Spacer(modifier = Modifier.width(PcDimens.Space4))
        Text(
            text = value,
            color = colors.text,
            style = if (mono) {
                TabularNumStyle.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            } else {
                TabularNumStyle.copy(
                    fontSize = 13.5.sp,
                )
            },
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
