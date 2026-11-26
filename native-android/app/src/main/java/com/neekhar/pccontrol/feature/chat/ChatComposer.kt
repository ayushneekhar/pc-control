package com.neekhar.pccontrol.feature.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatComposer(
    draft: String,
    engine: String,
    running: Boolean,
    sending: Boolean,
    canSend: Boolean,
    pendingAttachments: List<ChatAttachment>,
    uploadingCount: Int,
    replyTo: ChatReplyRef?,
    editingState: EditingMessageState?,
    focusTrigger: Int,
    resolveAttachmentSource: (String) -> ChatAttachmentSource,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
    onPickFiles: (List<PickedLocalFile>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    onPressImage: (PreviewImageItem) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val onAccent = if (colors.accent.luminance() > 0.4f) Color.Black else Color.White
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    val textFieldState = rememberTextFieldState(initialText = draft)
    var lastObservedText by remember { mutableStateOf(draft) }
    var lastSelection by remember { mutableStateOf(TextRange(draft.length)) }
    var showAttachSheet by remember { mutableStateOf(false) }

    // Sync external draft mutations (clear after send, edit & resend, rollback on failure) into TextFieldState.
    LaunchedEffect(draft) {
        val current = textFieldState.text.toString()
        if (current != draft) {
            lastObservedText = draft
            lastSelection = TextRange(draft.length)
            textFieldState.setTextAndPlaceCursorAtEnd(draft)
        }
    }

    // Observe user edits, apply smart list continuation on single newline, and forward to ViewModel.
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() to textFieldState.selection }
            .collect { (nextText, nextSel) ->
                if (nextText != lastObservedText) {
                    val autoFormatted = ChatLogic.applyChatListAutoFormat(
                        prevText = lastObservedText,
                        nextText = nextText,
                        prevSelectionStart = lastSelection.start,
                        prevSelectionEnd = lastSelection.end,
                    )
                    if (autoFormatted.changed) {
                        lastObservedText = autoFormatted.text
                        lastSelection = TextRange(autoFormatted.selectionStart, autoFormatted.selectionEnd)
                        textFieldState.edit {
                            replace(0, length, autoFormatted.text)
                            selection = TextRange(autoFormatted.selectionStart, autoFormatted.selectionEnd)
                        }
                        onDraftChange(autoFormatted.text)
                    } else {
                        lastObservedText = nextText
                        lastSelection = nextSel
                        onDraftChange(nextText)
                    }
                } else {
                    lastSelection = nextSel
                }
            }
    }

    LaunchedEffect(focusTrigger) {
        if (focusTrigger > 0) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun ingestUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val loaded = mutableListOf<PickedLocalFile>()
            var firstError: String? = null
            for (uri in uris) {
                when (val read = readLocalFileFromUri(context, uri)) {
                    is LocalFileReadResult.Ok -> loaded.add(read.file)
                    is LocalFileReadResult.Error -> if (firstError == null) firstError = read.message
                }
            }
            if (loaded.isNotEmpty()) {
                onPickFiles(loaded)
            } else if (firstError != null) {
                onError(firstError)
            }
        }
    }

    // Native Android Photo Picker (PickVisualMedia)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS),
    ) { uris ->
        ingestUris(uris)
    }

    // Native Android File Picker (GetContent)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) ingestUris(listOf(uri))
    }

    // Gboard / Android Rich Content clipboard image paste via Compose contentReceiver
    val receiveContentListener = remember {
        ReceiveContentListener { transferableContent ->
            if (!transferableContent.hasMediaType(MediaType.Image)) {
                return@ReceiveContentListener transferableContent
            }
            val uris = mutableListOf<Uri>()
            val remaining = transferableContent.consume { item ->
                val uri = item.uri
                if (uri != null) {
                    uris.add(uri)
                    true
                } else {
                    false
                }
            }
            if (uris.isNotEmpty()) {
                ingestUris(uris)
            }
            remaining
        }
    }

    val canAddAttachment = !sending && (pendingAttachments.size + uploadingCount) < MAX_ATTACHMENTS
    val showStopAction = running && draft.isBlank() && pendingAttachments.isEmpty() && !sending

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // Pending attachment chips strip
        if (pendingAttachments.isNotEmpty() || uploadingCount > 0) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                for (att in pendingAttachments) {
                    if (att.isImage) {
                        val src = remember(att.url) { resolveAttachmentSource(att.url) }
                        PendingImageChip(
                            attachment = att,
                            source = src,
                            onPress = {
                                onPressImage(
                                    PreviewImageItem(
                                        url = att.url,
                                        name = att.name,
                                        mimeType = att.mimeType,
                                    )
                                )
                            },
                            onRemove = { onRemoveAttachment(att.id) },
                        )
                    } else {
                        PendingFileChip(
                            attachment = att,
                            onRemove = { onRemoveAttachment(att.id) },
                        )
                    }
                }

                if (uploadingCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface)
                            .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp),
                    ) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (uploadingCount > 1) "Uploading $uploadingCount…" else "Uploading…",
                            color = colors.textDim,
                            fontSize = 12.5.sp,
                        )
                    }
                }
            }
        }

        // Reply-to quote bar
        if (replyTo != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(44.dp)
                        .background(colors.accent),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "Replying to ${ChatLogic.replySenderLabel(replyTo)}",
                        color = colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (replyTo.text.isNotBlank()) {
                        Text(
                            text = replyTo.text,
                            color = colors.textDim,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCancelReply),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Cancel reply",
                        tint = colors.textDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Editing message bar
        if (editingState != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(0.5.dp, colors.accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "Editing",
                    color = colors.accent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (editingState.previewText.isNotBlank()) {
                    Text(
                        text = "· ${editingState.previewText}",
                        color = colors.textFaint,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCancelEdit),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Cancel edit",
                        tint = colors.textDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Main composer pill
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .border(
                    width = if (editingState != null) 1.dp else 0.5.dp,
                    color = if (editingState != null) colors.accent else colors.hairline,
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            // Attach button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .alpha(if (canAddAttachment) 1f else 0.4f)
                    .clip(CircleShape)
                    .background(colors.surfaceRaised)
                    .clickable(enabled = canAddAttachment) { showAttachSheet = true },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Add photo or file",
                    tint = colors.text,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Auto-expanding multiline input with Gboard image paste (contentReceiver)
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .padding(vertical = 6.dp),
            ) {
                BasicTextField(
                    state = textFieldState,
                    enabled = !sending,
                    textStyle = TextStyle(
                        color = colors.text,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
                    cursorBrush = SolidColor(colors.accent),
                    decorator = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (textFieldState.text.isEmpty()) {
                                val placeholder = if (running) {
                                    "Queue follow-up for ${ChatLogic.formatEngineLabel(engine)}…"
                                } else {
                                    "Message ${ChatLogic.formatEngineLabel(engine)}"
                                }
                                Text(
                                    text = placeholder,
                                    color = colors.textFaint,
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .contentReceiver(receiveContentListener),
                )
            }

            // Send / Queue / Stop button
            when {
                showStopAction -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.badSoft)
                            .clickable(onClick = onStopGeneration),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = "Stop generation",
                            tint = colors.bad,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                else -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (canSend) colors.accent else colors.surfaceRaised)
                            .clickable(enabled = canSend, onClick = onSend),
                    ) {
                        if (sending) {
                            CircularProgressIndicator(
                                color = colors.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = if (running) "Queue message" else "Send message",
                                tint = if (canSend) onAccent else colors.textFaint,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAttachSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
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
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .padding(bottom = 14.dp),
            ) {
                Text(
                    text = "Attach",
                    color = colors.textDim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                AttachSheetRow(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "Photo",
                    subtitle = "Choose images from your device",
                    onClick = {
                        showAttachSheet = false
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                )
                AttachSheetRow(
                    icon = Icons.Rounded.Description,
                    title = "File",
                    subtitle = "Documents, logs, or any file up to 15 MB",
                    onClick = {
                        showAttachSheet = false
                        filePickerLauncher.launch("*/*")
                    },
                )
            }
        }
    }
}

@Composable
private fun PendingImageChip(
    attachment: ChatAttachment,
    source: ChatAttachmentSource,
    onPress: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalPcColors.current
    val context = LocalContext.current
    val request = remember(source) {
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

    Box(modifier = Modifier.size(64.dp)) {
        AsyncImage(
            model = request,
            contentDescription = attachment.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp, end = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceRaised)
                .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp))
                .clickable(onClick = onPress),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(CircleShape)
                .background(colors.bg)
                .border(0.5.dp, colors.hairline, CircleShape)
                .clickable(onClick = onRemove),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove ${attachment.name}",
                tint = colors.text,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun PendingFileChip(
    attachment: ChatAttachment,
    onRemove: () -> Unit,
) {
    val colors = LocalPcColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .height(48.dp)
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp))
            .padding(start = 10.dp, end = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = attachment.name,
                color = colors.text,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = ChatLogic.formatAttachmentSize(attachment.sizeBytes),
                style = TabularNumStyle,
                color = colors.textFaint,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove ${attachment.name}",
                tint = colors.textDim,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun AttachSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceRaised),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
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

private sealed interface LocalFileReadResult {
    data class Ok(val file: PickedLocalFile) : LocalFileReadResult
    data class Error(val message: String) : LocalFileReadResult
}

private suspend fun readLocalFileFromUri(context: Context, uri: Uri): LocalFileReadResult =
    withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            var displayName: String? = null
            var reportedSize = -1L

            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) reportedSize = cursor.getLong(sizeIdx)
                    }
                }

            if (reportedSize > MAX_ATTACHMENT_BYTES) {
                return@withContext LocalFileReadResult.Error("Attachment exceeds 15 MB limit.")
            }

            val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val fallbackExt = when {
                mime.startsWith("image/png") -> ".png"
                mime.startsWith("image/webp") -> ".webp"
                mime.startsWith("image/gif") -> ".gif"
                mime.startsWith("image/") -> ".jpg"
                else -> ""
            }
            val safeName = displayName?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "attachment$fallbackExt"

            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_ATTACHMENT_BYTES) {
                        return@withContext LocalFileReadResult.Error("Attachment exceeds 15 MB limit.")
                    }
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            } ?: return@withContext LocalFileReadResult.Error("Could not read selected file.")

            if (bytes.isEmpty()) {
                return@withContext LocalFileReadResult.Error("Selected file is empty.")
            }

            LocalFileReadResult.Ok(PickedLocalFile(name = safeName, mimeType = mime, bytes = bytes))
        } catch (e: Exception) {
            LocalFileReadResult.Error(e.message ?: "Could not read attachment")
        }
    }
