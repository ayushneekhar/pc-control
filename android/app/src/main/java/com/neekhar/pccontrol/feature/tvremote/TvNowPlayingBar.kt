package com.neekhar.pccontrol.feature.tvremote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcIconButton
import com.neekhar.pccontrol.core.ui.PcStatusDot
import com.neekhar.pccontrol.core.ui.PcTone

@Composable
fun TvNowPlayingBar(
    state: TvRemoteUiState,
    resolveImageUrl: (String?) -> String?,
    onTogglePlayPause: () -> Unit,
    onOpenRemote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = state.session
    val nowPlaying = session?.nowPlaying
    if (session == null || !session.connected || !session.isPlaying || nowPlaying == null) {
        return
    }

    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val posterUrl = resolveImageUrl(nowPlaying.posterUrl)
    val statusLabel = if (session.isPaused) {
        "Paused on ${state.deviceName}"
    } else {
        "Playing on ${state.deviceName}"
    }

    PcCard(
        modifier = modifier,
        contentPadding = PaddingValues(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        onClick = onOpenRemote,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tv,
                    contentDescription = null,
                    tint = colors.textFaint,
                    modifier = Modifier.size(16.dp),
                )
                if (!posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = nowPlaying.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PcStatusDot(
                        tone = if (session.isPaused) PcTone.Warn else PcTone.Ok,
                        size = 6.dp,
                    )
                    Text(
                        text = statusLabel,
                        color = if (session.isPaused) colors.textDim else colors.accent,
                        style = type.micro.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = TvRemoteFormatters.miniBarTitle(nowPlaying),
                    color = colors.text,
                    style = type.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            PcIconButton(
                icon = if (session.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = if (session.isPaused) "Resume playback" else "Pause playback",
                onClick = onTogglePlayPause,
                tint = colors.accent,
                containerColor = colors.accentSoft,
                iconSize = 22.dp,
                visualSize = 40.dp,
            )
        }
    }
}
