package com.neekhar.pccontrol.feature.system

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

data class SparklineSeries(
    val values: List<Double?>,
    val color: Color,
    val strokeWidthDp: Float = 2f,
)

/**
 * Smooth anti-aliased Jetpack Compose Canvas sparkline with no gradients or glow,
 * adhering strictly to docs/DESIGN.md.
 */
@Composable
fun VitalsSparkline(
    series: List<SparklineSeries>,
    baselineColor: Color,
    dotColor: Color,
    modifier: Modifier = Modifier,
    maxValue: Double? = null,
    height: Dp = 44.dp,
    maxPoints: Int = 60,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val w = size.width
        val h = size.height
        if (w <= 2f || h <= 2f) return@Canvas

        val padY = 4.dp.toPx()
        val padX = 3.dp.toPx()
        val plotW = (w - padX * 2f).coerceAtLeast(1f)
        val plotH = (h - padY * 2f).coerceAtLeast(1f)

        // Subtle midline & baseline hairlines
        drawLine(
            color = baselineColor.copy(alpha = 0.55f),
            start = Offset(0f, h - padY),
            end = Offset(w, h - padY),
            strokeWidth = 1f,
        )
        drawLine(
            color = baselineColor.copy(alpha = 0.3f),
            start = Offset(0f, padY + plotH * 0.5f),
            end = Offset(w, padY + plotH * 0.5f),
            strokeWidth = 1f,
        )

        val trimmed = series.map { s -> s.copy(values = s.values.takeLast(maxPoints)) }
        val computedMax = maxValue ?: max(
            1.0,
            trimmed.flatMap { it.values }.filterNotNull().filter { it.isFinite() }.maxOrNull() ?: 1.0,
        )

        trimmed.forEachIndexed { seriesIdx, s ->
            val vals = s.values
            if (vals.isEmpty()) return@forEachIndexed
            val count = vals.size
            val stepX = if (count > 1) plotW / (count - 1).toFloat() else 0f

            val path = Path()
            var prevPoint: Offset? = null
            var lastValidPoint: Offset? = null

            for (i in vals.indices) {
                val raw = vals[i]
                if (raw == null || !raw.isFinite()) {
                    prevPoint = null
                    continue
                }
                val norm = (min(max(0.0, raw), computedMax) / computedMax).toFloat()
                val x = if (count == 1) padX + plotW else padX + i * stepX
                val y = padY + plotH * (1f - norm)
                val pt = Offset(x, y)

                if (prevPoint == null) {
                    path.moveTo(pt.x, pt.y)
                } else {
                    val midX = (prevPoint.x + pt.x) * 0.5f
                    val midY = (prevPoint.y + pt.y) * 0.5f
                    path.quadraticTo(prevPoint.x, prevPoint.y, midX, midY)
                    if (i == vals.lastIndex || vals.getOrNull(i + 1) == null) {
                        path.lineTo(pt.x, pt.y)
                    }
                }
                prevPoint = pt
                lastValidPoint = pt
            }

            drawPath(
                path = path,
                color = s.color,
                style = Stroke(
                    width = s.strokeWidthDp.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // Emphasize live latest sample on primary series
            if (seriesIdx == 0 && lastValidPoint != null) {
                drawCircle(
                    color = dotColor,
                    radius = 2.75.dp.toPx(),
                    center = lastValidPoint,
                )
            }
        }
    }
}

@Composable
fun VitalsProgressBar(
    percent: Double,
    trackColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val target = (percent / 100.0).toFloat().coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 280),
        label = "vitals_bar",
    )
    val shape = RoundedCornerShape(height)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(trackColor),
    ) {
        if (animated > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(fillColor),
            )
        }
    }
}

@Composable
fun PerCoreCpuStrip(
    perCpu: List<Double>,
    trackColor: Color,
    normalColor: Color,
    warnColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
) {
    if (perCpu.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        perCpu.forEach { load ->
            val fraction = (load / 100.0).toFloat().coerceIn(0.08f, 1f)
            val barColor = if (load > 65.0) warnColor else normalColor
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(trackColor),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor),
                )
            }
        }
    }
}

/**
 * Native Jetpack Compose Canvas status ring showing healthy ratio vs problems
 * with a clean centered glyph. No gradients, no glow.
 */
@Composable
fun ServicesStatusRing(
    runningCount: Int,
    totalCount: Int,
    problemCount: Int,
    ringColor: Color,
    trackColor: Color,
    okColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    val healthyRatio = if (totalCount > 0) {
        (runningCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
    } else if (problemCount == 0) {
        1f
    } else {
        0f
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokePx = 2.75.dp.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            val topLeft = Offset(inset, inset)

            // Base track
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )

            if (problemCount == 0) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            } else {
                val okSweep = (healthyRatio * 360f).coerceIn(0f, 330f)
                if (okSweep > 4f) {
                    drawArc(
                        color = okColor.copy(alpha = 0.45f),
                        startAngle = -90f,
                        sweepAngle = okSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
                val problemSweep = (360f - okSweep).coerceAtLeast(36f)
                drawArc(
                    color = ringColor,
                    startAngle = -90f + okSweep,
                    sweepAngle = problemSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }

        Icon(
            imageVector = if (problemCount == 0) Icons.Rounded.Check else Icons.Rounded.PriorityHigh,
            contentDescription = if (problemCount == 0) "All services healthy" else "$problemCount problems",
            tint = ringColor,
            modifier = Modifier.size(19.dp),
        )
    }
}
