package com.neekhar.pccontrol.feature.cams

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcStatusDot
import com.neekhar.pccontrol.core.ui.PcTone
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Music tab (`cam-music`, `/music/api/*`): YouTube URL or search play on the laptop speakers,
 * now-playing transport (pause/resume/stop/loop), debounced speaker volume & mute control,
 * up-next queue management, and recent tracks history.
 * Ported from `src/screens/cams/MusicTab.tsx`.
 */
@Composable
fun MusicTab(
    visible: Boolean,
    repository: CamsRepository,
    sharedUrl: String?,
    onSharedConsumed: () -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<MusicStatus?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf<List<MusicRecentItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    var playBusy by remember { mutableStateOf(false) }
    var draggingVol by remember { mutableStateOf<Int?>(null) }
    val queue = remember { mutableStateListOf<MusicQueueEntry>() }

    var reqSeq by remember { mutableIntStateOf(0) }
    var shownSeq by remember { mutableIntStateOf(0) }
    var lastRecentUrl by remember { mutableStateOf<String?>(null) }
    var prevState by remember { mutableStateOf<String?>(null) }
    var volJob by remember { mutableStateOf<Job?>(null) }

    fun loadRecent() {
        scope.launch {
            val res = repository.getMusicRecent()
            if (res is ApiResult.Success) {
                recent = res.data.recent
            }
        }
    }

    fun applyStatus(seq: Int, next: MusicStatus?) {
        if (seq < shownSeq) return
        shownSeq = seq
        val oldState = prevState
        status = next
        prevState = next?.state
        loaded = true

        val playingUrl = if (next?.state == "playing") next.url else lastRecentUrl
        if (playingUrl != lastRecentUrl) {
            lastRecentUrl = playingUrl
            loadRecent()
        }

        // Auto-advance queue when a non-looping track finishes and returns to idle
        if (oldState == "playing" && next?.state == "idle" && next.loop.not() && queue.isNotEmpty() && !playBusy) {
            val upcoming = queue.removeAt(0)
            playBusy = true
            scope.launch {
                val res = repository.playMusic(upcoming.url)
                if (res is ApiResult.Success) {
                    applyStatus(++reqSeq, res.data)
                } else if (res is ApiResult.Error) {
                    onToast(res.message)
                }
                playBusy = false
            }
        }
    }

    suspend fun pollOnce() {
        val seq = ++reqSeq
        when (val res = repository.getMusicStatus()) {
            is ApiResult.Success -> applyStatus(seq, res.data)
            is ApiResult.Error -> applyStatus(seq, null)
        }
    }

    fun runCommand(call: suspend () -> ApiResult<MusicStatus>) {
        scope.launch {
            when (val res = call()) {
                is ApiResult.Success -> applyStatus(++reqSeq, res.data)
                is ApiResult.Error -> {
                    onToast(res.message)
                    pollOnce()
                }
            }
        }
    }

    fun normalizeInput(raw: String): String {
        val trimmed = raw.trim()
        // If user pasted text containing a YouTube URL, extract it; otherwise pass raw query (ytsearch1:...)
        val extracted = CamsConfig.extractYoutubeLink(trimmed)
        if (extracted != null) return extracted
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return if (trimmed.startsWith("ytsearch", ignoreCase = true)) trimmed else "ytsearch1:$trimmed"
    }

    fun playLink(rawInput: String, clearField: Boolean = false) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        formError = null
        playBusy = true
        val payload = normalizeInput(trimmed)
        scope.launch {
            when (val res = repository.playMusic(payload)) {
                is ApiResult.Success -> {
                    applyStatus(++reqSeq, res.data)
                    if (clearField) query = ""
                }

                is ApiResult.Error -> {
                    formError = res.message
                }
            }
            playBusy = false
        }
    }

    fun enqueueLink(rawInput: String, label: String? = null) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        val normalized = normalizeInput(trimmed)
        queue.add(
            MusicQueueEntry(
                id = UUID.randomUUID().toString(),
                url = normalized,
                title = label?.takeIf { it.isNotBlank() } ?: trimmed,
            ),
        )
        onToast("Added to queue")
    }

    fun onVolumeChanged(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        draggingVol = clamped
        volJob?.cancel()
        volJob = scope.launch {
            delay(200L)
            when (val res = repository.setMusicVolume(clamped)) {
                is ApiResult.Success -> applyStatus(++reqSeq, res.data)
                is ApiResult.Error -> {
                    onToast(res.message)
                    pollOnce()
                }
            }
            draggingVol = null
        }
    }

    // Status poll loop while visible (1 s while loading, 2 s otherwise)
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        lastRecentUrl = null
        loadRecent()
        while (isActive) {
            pollOnce()
            val delayMs = if (status?.state == "loading") 1000L else 2000L
            delay(delayMs)
        }
    }

    // Play YouTube link shared into PC Control
    LaunchedEffect(visible, sharedUrl) {
        if (visible && !sharedUrl.isNullOrBlank()) {
            onSharedConsumed()
            playLink(sharedUrl, clearField = false)
        }
    }

    val up = status != null
    val stateStr = status?.state ?: "idle"
    val active = stateStr in setOf("loading", "playing", "paused")
    val posText = when {
        !active || status == null -> ""
        status!!.live -> if (stateStr == "loading") "" else "LIVE"
        status!!.position != null -> buildString {
            append(fmtDuration(status!!.position!!))
            val dur = status!!.duration
            if (dur != null && dur > 0.0) {
                append(" / ")
                append(fmtDuration(dur))
            }
        }
        else -> ""
    }
    val trackTitle = if (up) {
        status?.title?.takeIf { it.isNotBlank() } ?: if (active) "Finding the video…" else null
    } else {
        null
    }
    val loadingNote = if (stateStr == "loading") status?.lastError else null
    val displayError = formError ?: if (stateStr == "error") status?.lastError ?: "Playback failed" else null
    val stateTone = when {
        !up -> PcTone.Bad
        stateStr == "playing" -> PcTone.Ok
        stateStr == "loading" -> PcTone.Warn
        stateStr == "paused" -> PcTone.Accent
        stateStr == "error" -> PcTone.Bad
        else -> PcTone.Dim
    }
    val stateLabel = if (up) {
        when (stateStr) {
            "idle" -> "Idle"
            "loading" -> "Loading…"
            "playing" -> "Playing"
            "paused" -> "Paused"
            "error" -> "Error"
            else -> stateStr
        }
    } else if (loaded) {
        "Music service offline"
    } else {
        "Connecting…"
    }

    val hasVol = up && status?.volume != null
    val shownVol = draggingVol ?: status?.volume ?: 0
    val progress = if (active && status != null && !status!!.live && status!!.position != null && (status!!.duration ?: 0.0) > 0.0) {
        (status!!.position!! / status!!.duration!!).toFloat().coerceIn(0f, 1f)
    } else {
        null
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
    ) {
        // YouTube URL / search bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (formError != null) formError = null
                },
                placeholder = {
                    Text(
                        text = "YouTube link or search…",
                        color = colors.textFaint,
                        fontSize = 14.5.sp,
                        maxLines = 1,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = { if (query.isNotBlank()) playLink(query, clearField = true) },
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.hairline,
                    cursorColor = colors.accent,
                ),
                shape = RoundedCornerShape(PcDimens.RadiusSm),
                modifier = Modifier.weight(1f),
            )
            PcButton(
                label = "Play",
                icon = Icons.Filled.PlayArrow,
                busy = playBusy,
                enabled = query.isNotBlank(),
                onClick = { playLink(query, clearField = true) },
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(PcDimens.RadiusSm))
                    .background(colors.surfaceRaised)
                    .alpha(if (query.isNotBlank()) 1f else 0.45f)
                    .clickable(enabled = query.isNotBlank()) {
                        enqueueLink(query)
                        query = ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add to queue",
                    tint = colors.text,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (!displayError.isNullOrBlank()) {
            PcErrorBanner(
                title = displayError,
                tone = PcTone.Bad,
            )
        }

        // Now Playing card
        PcCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PcStatusDot(tone = stateTone, size = 8.dp)
                Text(
                    text = stateLabel,
                    color = when (stateTone) {
                        PcTone.Ok -> colors.ok
                        PcTone.Warn -> colors.warn
                        PcTone.Bad -> colors.bad
                        PcTone.Accent -> colors.accent
                        else -> colors.textDim
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (stateStr == "loading") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = colors.warn,
                    )
                }
                Box(modifier = Modifier.weight(1f))
                if (posText == "LIVE") {
                    PcPill(label = "LIVE", tone = PcTone.Ok, showDot = true)
                } else if (posText.isNotEmpty()) {
                    Text(
                        text = posText,
                        color = colors.textDim,
                        style = TabularNumStyle.copy(fontSize = 13.sp),
                    )
                }
            }

            Text(
                text = trackTitle ?: "Nothing playing",
                color = if (trackTitle != null) colors.text else colors.textDim,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = if (trackTitle != null) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )

            if (progress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceRaised),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                    )
                }
            }

            if (!loadingNote.isNullOrBlank()) {
                Text(
                    text = loadingNote,
                    color = colors.warn,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
            ) {
                PcButton(
                    label = if (stateStr == "paused") "Resume" else "Pause",
                    icon = if (stateStr == "paused") Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    variant = PcButtonVariant.Subtle,
                    enabled = active,
                    onClick = {
                        runCommand {
                            if (stateStr == "paused") repository.resumeMusic() else repository.pauseMusic()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                PcButton(
                    label = "Stop",
                    icon = Icons.Filled.Stop,
                    variant = PcButtonVariant.Subtle,
                    enabled = active || stateStr == "error",
                    onClick = { runCommand { repository.stopMusic() } },
                    modifier = Modifier.weight(1f),
                )
                PcButton(
                    label = "Loop",
                    icon = Icons.Filled.Repeat,
                    variant = if (status?.loop == true) PcButtonVariant.Primary else PcButtonVariant.Subtle,
                    enabled = up,
                    onClick = { runCommand { repository.setMusicLoop(status?.loop != true) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Speaker volume card
        PcCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
            ) {
                val muted = status?.muted == true
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(PcDimens.RadiusSm))
                        .background(if (muted) colors.accentSoft else colors.surfaceRaised)
                        .alpha(if (hasVol) 1f else 0.5f)
                        .clickable(enabled = hasVol) {
                            runCommand { repository.setMusicMuted(!muted) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (muted) "Unmute laptop speakers" else "Mute laptop speakers",
                        tint = if (muted) colors.accent else colors.text,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Slider(
                    value = shownVol.toFloat(),
                    onValueChange = { onVolumeChanged(it.roundToInt()) },
                    valueRange = 0f..100f,
                    enabled = hasVol,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.hairline,
                        disabledThumbColor = colors.textFaint,
                        disabledActiveTrackColor = colors.textFaint,
                    ),
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = if (hasVol || draggingVol != null) "$shownVol%" else "--",
                    color = colors.text,
                    style = TabularNumStyle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Medium),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
            }
        }

        // Up Next queue section
        if (queue.isNotEmpty()) {
            PcSectionLabel(
                label = "Up next (${queue.size})",
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Play next",
                            color = colors.accent,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                if (queue.isNotEmpty()) {
                                    val next = queue.removeAt(0)
                                    playLink(next.url)
                                }
                            },
                        )
                        Text(
                            text = "Clear",
                            color = colors.textDim,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { queue.clear() },
                        )
                    }
                },
            )
            PcListCard {
                queue.forEachIndexed { idx, item ->
                    PcListRow(
                        title = item.title,
                        icon = Icons.Filled.SkipNext,
                        showDivider = idx < queue.lastIndex,
                        onClick = {
                            queue.removeAt(idx)
                            playLink(item.url)
                        },
                        trailingContent = {
                            IconButton(
                                onClick = { queue.removeAt(idx) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove from queue",
                                    tint = colors.textFaint,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                    )
                }
            }
        }

        // Recent tracks section
        if (recent.isNotEmpty()) {
            PcSectionLabel(label = "Recent")
            PcListCard {
                recent.forEachIndexed { idx, item ->
                    val isCurrent = active && status?.url == item.url
                    PcListRow(
                        title = item.title?.takeIf { it.isNotBlank() } ?: item.url,
                        leading = {
                            Icon(
                                imageVector = if (isCurrent) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = if (isCurrent) colors.accent else colors.textDim,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (item.live) {
                                    PcPill(label = "Live", tone = PcTone.Accent)
                                }
                                IconButton(
                                    onClick = { enqueueLink(item.url, item.title) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = "Add to queue",
                                        tint = colors.textDim,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        showDivider = idx < recent.lastIndex,
                        onClick = { playLink(item.url) },
                    )
                }
            }
        }
    }
}

private fun fmtDuration(seconds: Double): String {
    val total = floor(seconds).toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total / 60) % 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}
