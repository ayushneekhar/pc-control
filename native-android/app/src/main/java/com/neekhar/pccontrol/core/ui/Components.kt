package com.neekhar.pccontrol.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neekhar.pccontrol.core.theme.PcColors
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.PcTheme
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.theme.tabular

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
    val shape = RoundedCornerShape(PcDimens.RadiusLg)
    val baseModifier = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(colors.surface)

    val interactiveModifier = if (onClick != null || onLongClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        baseModifier.combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(),
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
            .clip(RoundedCornerShape(PcDimens.RadiusLg))
            .background(colors.surface),
        content = content,
    )
}

/**
 * Single row inside a [PcListCard]. Min height 56.dp, hairline separator when [showDivider] is true.
 * [dividerStartIndent] defaults to `52.dp` when a leading icon or leading slot is present, and
 * `16.dp` ([PcDimens.Space4]) otherwise so hairlines always align cleanly with the title text start.
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
    dividerStartIndent: Dp = if (icon != null || leading != null) 52.dp else PcDimens.Space4,
) {
    val colors = PcTheme.colors
    val type = PcTheme.type
    val chevronVisible = showChevron ?: (onClick != null && trailing == null && trailingContent == null)

    val clickableModifier = if (onClick != null || onLongClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(),
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
                    style = type.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    subtitleContent != null -> subtitleContent()
                    subtitle != null -> Text(
                        text = subtitle,
                        color = colors.textFaint,
                        style = type.caption,
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
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textFaint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = dividerStartIndent),
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
    val type = PcTheme.type
    val shape = RoundedCornerShape(PcDimens.RadiusLg)
    val baseModifier = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(colors.surface)
        .let { m ->
            if (onClick != null) {
                val interactionSource = remember { MutableInteractionSource() }
                m.clickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
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
                    style = type.label.copy(fontWeight = FontWeight.Normal),
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
                        style = type.metricValue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!item.unit.isNullOrEmpty()) {
                        Text(
                            text = item.unit,
                            color = colors.textDim,
                            style = type.metricUnit,
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
    onClick: (() -> Unit)? = null,
) {
    val colors = PcTheme.colors
    val fg = colors.toneForeground(tone)
    val bg = colors.toneSoftBackground(tone)

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .let { m ->
                if (onClick != null) {
                    val interactionSource = remember { MutableInteractionSource() }
                    m.clickable(
                        interactionSource = interactionSource,
                        indication = ripple(),
                        onClick = onClick,
                    )
                } else {
                    m
                }
            }
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
 * Small status / metadata pill with explicit foreground and background colors.
 */
@Composable
fun PcPill(
    text: String,
    color: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = color,
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
 * Shared filter chip matching PC Control design tokens:
 * `36.dp` visible pill height inside a guaranteed `48.dp` minimum touch target.
 * Uses `accentSoft` background + `accent` foreground when [selected], and
 * `surface` background + `textDim` foreground when unselected.
 */
@Composable
fun PcFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    count: Int? = null,
    enabled: Boolean = true,
) {
    val colors = PcTheme.colors
    val type = PcTheme.type
    val interactionSource = remember { MutableInteractionSource() }

    val targetBg = when {
        !enabled -> colors.surfaceRaised.copy(alpha = 0.6f)
        selected -> colors.accentSoft
        else -> colors.surface
    }
    val targetFg = when {
        !enabled -> colors.textFaint
        selected -> colors.accent
        else -> colors.textDim
    }
    val bg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 160),
        label = "pcFilterChipBg",
    )
    val fg by animateColorAsState(
        targetValue = targetFg,
        animationSpec = tween(durationMillis = 160),
        label = "pcFilterChipFg",
    )

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { this.selected = selected }
            .clickable(
                enabled = enabled,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(PcDimens.RadiusPill))
                .background(bg)
                .indication(
                    interactionSource = interactionSource,
                    indication = ripple(),
                )
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = label,
                color = fg,
                style = type.chip.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                Text(
                    text = count.toString(),
                    color = fg.copy(alpha = 0.78f),
                    style = type.micro.tabular(),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Action button matching DESIGN.md:
 * - Primary: accent fill + black text (when disabled: surfaceRaised fill + textFaint text)
 * - Subtle: surfaceRaised fill + primary text (when disabled: surfaceRaised + textFaint)
 * - Danger: badSoft fill + bad text
 * - Ghost: transparent + accent text (when disabled: transparent + textFaint)
 *
 * Guarantees a minimum 48.dp interactive touch target even for compact variants.
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
    val isClickable = enabled && !busy
    val (bg, fg) = when {
        !enabled && variant == PcButtonVariant.Primary ->
            colors.surfaceRaised to colors.textFaint
        !enabled && variant == PcButtonVariant.Subtle ->
            colors.surfaceRaised to colors.textFaint
        !enabled && variant == PcButtonVariant.Danger ->
            colors.badSoft.copy(alpha = 0.5f) to colors.bad.copy(alpha = 0.45f)
        !enabled && variant == PcButtonVariant.Ghost ->
            Color.Transparent to colors.textFaint
        variant == PcButtonVariant.Primary ->
            colors.accent to colors.onAccent
        variant == PcButtonVariant.Subtle ->
            colors.surfaceRaised to colors.text
        variant == PcButtonVariant.Danger ->
            colors.badSoft to colors.bad
        else ->
            Color.Transparent to colors.accent
    }
    val visualAlpha = if (enabled && busy) 0.85f else 1f
    val height = if (compact) 36.dp else 44.dp
    val radius = if (compact) PcDimens.RadiusSm else PcDimens.RadiusMd
    val shape = RoundedCornerShape(radius)
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .clickable(
                enabled = isClickable,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .minimumInteractiveComponentSize()
            .height(height)
            .clip(shape)
            .background(bg)
            .alpha(visualAlpha)
            .indication(
                interactionSource = interactionSource,
                indication = ripple(),
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

/**
 * Shared restrained switch control:
 * subtle mint track + crisp mint thumb when checked, dark `surfaceRaised` when unchecked.
 * Replaces duplicated inline `SwitchDefaults.colors(...)` definitions across feature screens.
 */
@Composable
fun PcSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = PcTheme.colors
    val checkedTrack = colors.accent.copy(alpha = if (colors.isDark) 0.28f else 0.22f)
    val checkedBorder = colors.accent.copy(alpha = if (colors.isDark) 0.48f else 0.40f)

    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.accent,
            checkedTrackColor = checkedTrack,
            checkedBorderColor = checkedBorder,
            uncheckedThumbColor = colors.textDim,
            uncheckedTrackColor = colors.surfaceRaised,
            uncheckedBorderColor = colors.hairline,
            disabledCheckedThumbColor = colors.accent.copy(alpha = 0.42f),
            disabledCheckedTrackColor = colors.surfaceRaised,
            disabledCheckedBorderColor = colors.hairline,
            disabledUncheckedThumbColor = colors.textFaint.copy(alpha = 0.55f),
            disabledUncheckedTrackColor = colors.surfaceRaised.copy(alpha = 0.55f),
            disabledUncheckedBorderColor = colors.hairline.copy(alpha = 0.55f),
        ),
    )
}

/**
 * Standard interactive icon button with a guaranteed 48.dp minimum touch target.
 */
@Composable
fun PcIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = PcTheme.colors.text,
    containerColor: Color = Color.Transparent,
    iconSize: Dp = 22.dp,
    visualSize: Dp = 40.dp,
) {
    val colors = PcTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val resolvedTint = if (enabled) tint else colors.textFaint

    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(visualSize)
                .clip(CircleShape)
                .background(containerColor)
                .indication(
                    interactionSource = interactionSource,
                    indication = ripple(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = resolvedTint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Immutable
data class PcSegmentOption<T>(
    val id: T,
    val label: String,
    val icon: ImageVector? = null,
)

/**
 * Surface track with smoothly sliding `surfaceRaised` pill indicator (never solid mint).
 */
@Composable
fun <T> PcSegmentedControl(
    options: List<PcSegmentOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PcTheme.colors
    val count = options.size
    if (count == 0) return

    val selectedIndex = options.indexOfFirst { it.id == selected }
    val hasSelection = selectedIndex >= 0
    val trackPadding = 4.dp
    val segmentGap = 2.dp
    val segmentHeight = 40.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PcDimens.RadiusMd))
            .background(colors.surface)
            .padding(trackPadding),
    ) {
        val totalGap = segmentGap * (count - 1)
        val segmentWidth = ((maxWidth - totalGap) / count).coerceAtLeast(0.dp)
        val targetOffset = if (hasSelection) {
            (segmentWidth + segmentGap) * selectedIndex
        } else {
            0.dp
        }
        val animatedOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "pcSegmentPillOffset",
        )

        if (hasSelection) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(animatedOffset.roundToPx(), 0) }
                    .width(segmentWidth)
                    .height(segmentHeight)
                    .clip(RoundedCornerShape(PcDimens.RadiusSm))
                    .background(colors.surfaceRaised),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(segmentGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                val isSelected = option.id == selected
                val interactionSource = remember { MutableInteractionSource() }
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) colors.text else colors.textDim,
                    animationSpec = tween(durationMillis = 160),
                    label = "pcSegmentContentColor",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(segmentHeight)
                        .clip(RoundedCornerShape(PcDimens.RadiusSm))
                        .clickable(
                            role = Role.Tab,
                            interactionSource = interactionSource,
                            indication = ripple(),
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
                            tint = contentColor,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Text(
                        text = option.label,
                        color = contentColor,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Convenience string-list overload of [PcSegmentedControl] indexed by [selectedIndex].
 */
@Composable
fun PcSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segmentOptions = remember(options) {
        options.mapIndexed { idx, label -> PcSegmentOption(id = idx, label = label) }
    }
    PcSegmentedControl(
        options = segmentOptions,
        selected = selectedIndex,
        onSelect = onSelect,
        modifier = modifier,
    )
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
    PcStatusDot(
        color = PcTheme.colors.toneForeground(tone),
        modifier = modifier,
        size = size,
    )
}

/**
 * 7.dp status dot indicator with an explicit [Color].
 */
@Composable
fun PcStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 7.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
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
        PcTone.Bad, PcTone.Warn -> Icons.Rounded.Warning
        else -> Icons.Rounded.Check
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
    val type = PcTheme.type
    Row(
        modifier = modifier
            .then(if (trailing != null) Modifier.fillMaxWidth() else Modifier)
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
            style = type.sectionLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (trailing != null) Modifier.weight(1f) else Modifier,
        )
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * Compact drag handle for Material 3 ModalBottomSheets (replaces default 44dp vertical padding).
 */
@Composable
fun PcSheetDragHandle(modifier: Modifier = Modifier) {
    val colors = PcTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(colors.hairline),
        )
    }
}

/**
 * Shared Material 3 [ModalBottomSheet] wrapper using `PcTheme.colors.surface` (never `#000000`
 * black-on-black), compact [PcSheetDragHandle], and clean bottom `navigationBarsPadding()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    title: String? = null,
    contentPadding: PaddingValues = PaddingValues(
        start = PcDimens.ScreenPad,
        end = PcDimens.ScreenPad,
        top = PcDimens.Space2,
        bottom = PcDimens.Space4,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PcTheme.colors
    val type = PcTheme.type
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.text,
        scrimColor = colors.overlay,
        dragHandle = { PcSheetDragHandle() },
        shape = RoundedCornerShape(topStart = PcDimens.RadiusXl, topEnd = PcDimens.RadiusXl),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(contentPadding),
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    color = colors.text,
                    style = type.collapsedTitle,
                    modifier = Modifier.padding(bottom = PcDimens.Space3),
                )
            }
            content()
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
    title: String = "",
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
        PcTone.Bad -> Icons.Rounded.Error
        PcTone.Warn -> Icons.Rounded.Warning
        PcTone.Ok, PcTone.Accent -> Icons.Rounded.CheckCircle
        else -> Icons.Rounded.Info
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PcDimens.RadiusLg))
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
            if (title.isNotEmpty()) {
                Text(
                    text = title,
                    color = fg,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!message.isNullOrEmpty()) {
                Text(
                    text = message,
                    color = if (title.isEmpty()) fg else colors.textDim,
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
    dismissLabel: String? = "Cancel",
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
                .clip(RoundedCornerShape(PcDimens.RadiusLg))
                .background(colors.surface)
                .border(0.5.dp, colors.hairline, RoundedCornerShape(PcDimens.RadiusLg))
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
        ) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                color = colors.textDim,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
            ) {
                if (!dismissLabel.isNullOrBlank()) {
                    PcButton(
                        label = dismissLabel,
                        onClick = onDismiss,
                        variant = PcButtonVariant.Subtle,
                        enabled = !confirmBusy,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                PcButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    variant = confirmVariant,
                    busy = confirmBusy,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Unified progress meter supporting `4.dp` (compact) and `8.dp` (prominent) thicknesses.
 *
 * - Track uses [PcColors.meterTrack] (`#2C2C2C` dark / `#E0E0E0` light).
 * - Automatically switches fill color to `colors.warn` at `>= 0.80f` and `colors.bad` at `>= 0.95f`
 *   (unless an explicit [color] override is supplied).
 * - Guarantees a minimum visible fill width ([minVisibleFillWidth], default `4.dp`) for very small
 *   non-zero values so tiny fractions remain readable.
 */
@Composable
fun PcMeter(
    progress: Float,
    modifier: Modifier = Modifier,
    thickness: Dp = 4.dp,
    color: Color? = null,
    trackColor: Color = PcTheme.colors.meterTrack,
    minVisibleFillWidth: Dp = 4.dp,
    warnThreshold: Float = 0.80f,
    badThreshold: Float = 0.95f,
) {
    val colors = PcTheme.colors
    val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val targetFill = color ?: when {
        safeProgress >= badThreshold -> colors.bad
        safeProgress >= warnThreshold -> colors.warn
        else -> colors.text
    }
    val fill by animateColorAsState(
        targetValue = targetFill,
        animationSpec = tween(durationMillis = 180),
        label = "pcMeterFillColor",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .clip(CircleShape)
            .background(trackColor),
    ) {
        if (safeProgress > 0f && maxWidth > 0.dp) {
            val minWidth = minVisibleFillWidth.coerceAtMost(maxWidth)
            val fillWidth = (maxWidth * safeProgress).coerceIn(minWidth, maxWidth)
            Box(
                modifier = Modifier
                    .width(fillWidth)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(fill),
            )
        }
    }
}

/**
 * Thin progress bar (`4.dp` default), delegating to [PcMeter] for unified track contrast,
 * minimum visible fill width, and automatic `warn`/`bad` thresholds.
 */
@Composable
fun PcProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    height: Dp = 4.dp,
) {
    PcMeter(
        progress = progress,
        modifier = modifier,
        thickness = height,
        color = color,
    )
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

/**
 * Compact 48.dp single-line text field replacing oversized Material 3 OutlinedTextField.
 */
@Composable
fun PcCompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    mono: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = PcTheme.colors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(PcDimens.RadiusMd)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = if (enabled) colors.text else colors.textFaint,
            fontSize = 14.5.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
        ),
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { innerTextField ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(shape)
                    .background(colors.surfaceRaised)
                    .border(
                        width = if (focused) 1.dp else 0.5.dp,
                        color = if (focused) colors.accent else colors.hairline,
                        shape = shape,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty() && !placeholder.isNullOrEmpty()) {
                    Text(
                        text = placeholder,
                        color = colors.textFaint,
                        fontSize = 14.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}
