package com.neekhar.pccontrol.feature.cams

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * Native Compose pinch-to-zoom and double-tap zoom container (1× to 5×, double-tap toggles 2.5×).
 * Ported from `src/components/PinchZoom.tsx`.
 */
@Composable
fun PinchZoomBox(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    resetKey: Any? = null,
    onTap: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(enabled, resetKey) {
        scale = 1f
        offset = Offset.Zero
    }

    fun clampOffset(targetScale: Float, candidate: Offset): Offset {
        if (targetScale <= 1f || boxSize.width <= 0 || boxSize.height <= 0) return Offset.Zero
        val maxX = (targetScale - 1f) * boxSize.width / 2f
        val maxY = (targetScale - 1f) * boxSize.height / 2f
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY),
        )
    }

    val gestureModifier = if (enabled) {
        Modifier
            .pointerInput(enabled, resetKey) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(1f, 5f)
                    if (newScale <= 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                        val focalFromCenter = centroid - center
                        val ratio = newScale / oldScale
                        val nextOffset = Offset(
                            x = (offset.x - focalFromCenter.x) * ratio + focalFromCenter.x + pan.x,
                            y = (offset.y - focalFromCenter.y) * ratio + focalFromCenter.y + pan.y,
                        )
                        scale = newScale
                        offset = clampOffset(newScale, nextOffset)
                    }
                }
            }
            .pointerInput(enabled, resetKey, onTap) {
                detectTapGestures(
                    onTap = { onTap?.invoke() },
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val target = 2.5f
                            val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                            val fx = tapOffset.x - center.x
                            val fy = tapOffset.y - center.y
                            scale = target
                            offset = clampOffset(target, Offset(fx * (1f - target), fy * (1f - target)))
                        }
                    },
                )
            }
    } else if (onTap != null) {
        Modifier.pointerInput(onTap) {
            detectTapGestures(onTap = { onTap() })
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { newSize ->
                boxSize = newSize
                offset = clampOffset(scale, offset)
            }
            .then(gestureModifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        content = content,
    )
}
