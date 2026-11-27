package com.neekhar.pccontrol.feature.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

@Composable
fun ImageViewerDialog(
    image: PreviewImageItem,
    source: ChatAttachmentSource,
    onDismiss: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onMessage: (String) -> Unit = {},
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val scale = remember(image.url) { Animatable(MIN_ZOOM) }
    val offsetX = remember(image.url) { Animatable(0f) }
    val offsetY = remember(image.url) { Animatable(0f) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    var loading by remember(image.url) { mutableStateOf(true) }
    var loadError by remember(image.url) { mutableStateOf(false) }
    var saving by remember(image.url) { mutableStateOf(false) }
    var saved by remember(image.url) { mutableStateOf(false) }

    val actionUrl = remember(image, source) {
        val preferred = image.linkUrl?.takeIf { ChatLogic.isHttpUrl(it) }
        preferred ?: source.uri.takeIf { ChatLogic.isHttpUrl(it) }
    }

    val imageRequest = remember(source) {
        ImageRequest.Builder(context)
            .data(source.uri)
            .apply {
                if (source.headers.isNotEmpty()) {
                    val netHeaders = NetworkHeaders.Builder().apply {
                        source.headers.forEach { (k, v) -> set(k, v) }
                    }.build()
                    httpHeaders(netHeaders)
                }
            }
            .crossfade(true)
            .build()
    }

    LaunchedEffect(image.url) {
        scale.snapTo(MIN_ZOOM)
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
    }

    fun clampOffset(targetScale: Float, x: Float, y: Float): Pair<Float, Float> {
        val limX = ((targetScale - 1f) * viewportSize.width / 2f).coerceAtLeast(0f)
        val limY = ((targetScale - 1f) * viewportSize.height / 2f).coerceAtLeast(0f)
        return x.coerceIn(-limX, limX) to y.coerceIn(-limY, limY)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.media),
        ) {
            // Interactive pinch-to-zoom + pan + double-tap canvas
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    .pointerInput(image.url, viewportSize) {
                        detectTapGestures(
                            onDoubleTap = { tapOffset ->
                                scope.launch {
                                    if (scale.value > 1.05f) {
                                        launch {
                                            scale.animateTo(
                                                MIN_ZOOM,
                                                spring(stiffness = Spring.StiffnessMediumLow),
                                            )
                                        }
                                        launch {
                                            offsetX.animateTo(
                                                0f,
                                                spring(stiffness = Spring.StiffnessMediumLow),
                                            )
                                        }
                                        launch {
                                            offsetY.animateTo(
                                                0f,
                                                spring(stiffness = Spring.StiffnessMediumLow),
                                            )
                                        }
                                    } else {
                                        val fx = tapOffset.x - viewportSize.width / 2f
                                        val fy = tapOffset.y - viewportSize.height / 2f
                                        val (cx, cy) = clampOffset(
                                            DOUBLE_TAP_ZOOM,
                                            fx * (1f - DOUBLE_TAP_ZOOM),
                                            fy * (1f - DOUBLE_TAP_ZOOM),
                                        )
                                        launch {
                                            scale.animateTo(
                                                DOUBLE_TAP_ZOOM,
                                                spring(stiffness = Spring.StiffnessMediumLow),
                                            )
                                        }
                                        launch {
                                            offsetX.animateTo(
                                                cx,
                                                spring(stiffness = Spring.StiffnessMediumLow),
                                            )
                                        }
                                        launch {
                                            offsetY.animateTo(
                                                cy,
                                                spring(stiffness = Spring.StiffnessMediumLow),
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                    .pointerInput(image.url, viewportSize) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val prevScale = scale.value
                            val nextScale = (prevScale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val scaleRatio = if (prevScale > 0f) nextScale / prevScale else 1f
                            val cx = centroid.x - viewportSize.width / 2f
                            val cy = centroid.y - viewportSize.height / 2f
                            val rawX = (offsetX.value - cx) * scaleRatio + cx + pan.x
                            val rawY = (offsetY.value - cy) * scaleRatio + cy + pan.y
                            val (clampedX, clampedY) = clampOffset(nextScale, rawX, rawY)
                            scope.launch {
                                scale.snapTo(nextScale)
                                offsetX.snapTo(if (nextScale <= 1.01f) 0f else clampedX)
                                offsetY.snapTo(if (nextScale <= 1.01f) 0f else clampedY)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = image.name,
                    contentScale = ContentScale.Fit,
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Loading -> {
                                loading = true
                                loadError = false
                            }

                            is AsyncImagePainter.State.Success -> {
                                loading = false
                                loadError = false
                            }

                            is AsyncImagePainter.State.Error -> {
                                loading = false
                                loadError = true
                            }

                            is AsyncImagePainter.State.Empty -> Unit
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            translationX = offsetX.value
                            translationY = offsetY.value
                        },
                )

                if (loading) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(32.dp),
                    )
                }

                if (loadError) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.mediaChip)
                            .border(0.5.dp, colors.mediaChipBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BrokenImage,
                            contentDescription = null,
                            tint = colors.onMediaDim,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Could not load image",
                            color = colors.onMedia,
                            style = type.body.copy(fontWeight = FontWeight.Medium),
                        )
                    }
                }
            }

            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(colors.overlay)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = image.name.ifBlank { "Image" },
                    color = colors.onMedia,
                    style = type.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colors.mediaChip)
                        .border(0.5.dp, colors.mediaChipBorder, CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close image viewer",
                        tint = colors.onMedia,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Bottom action bar (Save, Share, Open in browser)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(colors.overlay)
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                ViewerActionPill(
                    icon = if (saved) Icons.Rounded.Check else Icons.Rounded.Download,
                    label = when {
                        saving -> "Saving…"
                        saved -> "Saved"
                        else -> "Save"
                    },
                    tint = if (saved) colors.accent else colors.onMedia,
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            val ok = saveImageToGallery(
                                context = context,
                                source = source,
                                displayName = image.name.ifBlank { "pc_control_image.jpg" },
                                mimeHint = image.mimeType,
                            )
                            saving = false
                            if (ok) {
                                saved = true
                                onMessage("Saved to Pictures/PC Control")
                            } else {
                                onMessage("Could not save image")
                            }
                        }
                    },
                )

                if (actionUrl != null) {
                    ViewerActionPill(
                        icon = Icons.Rounded.Share,
                        label = "Share",
                        tint = colors.onMedia,
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                this.type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, image.name)
                                putExtra(Intent.EXTRA_TEXT, actionUrl)
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(shareIntent, "Share image"))
                            }
                        },
                    )
                    ViewerActionPill(
                        icon = Icons.Rounded.OpenInNew,
                        label = "Open",
                        tint = colors.onMedia,
                        onClick = { onOpenExternalUrl(actionUrl) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.mediaChip)
            .border(0.5.dp, colors.mediaChipBorder, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = tint,
            style = type.caption.copy(fontWeight = FontWeight.Medium),
        )
    }
}

private suspend fun saveImageToGallery(
    context: Context,
    source: ChatAttachmentSource,
    displayName: String,
    mimeHint: String?,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val bytes: ByteArray
        val contentType: String

        if (source.uri.startsWith("data:", ignoreCase = true)) {
            val comma = source.uri.indexOf(',')
            if (comma <= 0) return@runCatching false
            val header = source.uri.substring(5, comma)
            contentType = header.substringBefore(';').ifBlank { mimeHint ?: "image/jpeg" }
            bytes = android.util.Base64.decode(source.uri.substring(comma + 1), android.util.Base64.DEFAULT)
        } else {
            val conn = (URL(source.uri).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                source.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching false
                contentType = conn.contentType?.substringBefore(';')?.trim()
                    ?.takeIf { it.startsWith("image/") }
                    ?: mimeHint
                    ?: "image/jpeg"
                bytes = conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }

        if (bytes.isEmpty()) return@runCatching false

        val cleanName = displayName.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "image.jpg" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, cleanName)
            put(MediaStore.Images.Media.MIME_TYPE, contentType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PC Control")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        resolver.openOutputStream(uri)?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: return@runCatching false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
}
