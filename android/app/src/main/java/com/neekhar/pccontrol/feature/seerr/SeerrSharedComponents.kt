package com.neekhar.pccontrol.feature.seerr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcBottomSheet
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcTone
import com.neekhar.pccontrol.feature.watch.player.WatchPlayerHelpers
import com.neekhar.pccontrol.feature.watch.player.WatchRemoteSubtitle

enum class SeerrPosterSize(
    val width: Dp,
    val height: Dp,
    val radius: Dp,
    val iconSize: Dp,
) {
    SM(width = 44.dp, height = 64.dp, radius = 8.dp, iconSize = 20.dp),
    MD(width = 56.dp, height = 82.dp, radius = 10.dp, iconSize = 24.dp),
    LG(width = 96.dp, height = 144.dp, radius = 12.dp, iconSize = 30.dp),
}

fun SeerrBadgeTone.toPcTone(): PcTone = when (this) {
    SeerrBadgeTone.OK -> PcTone.Ok
    SeerrBadgeTone.ACCENT -> PcTone.Accent
    SeerrBadgeTone.WARN -> PcTone.Warn
    SeerrBadgeTone.BAD -> PcTone.Bad
    SeerrBadgeTone.DIM -> PcTone.Dim
    SeerrBadgeTone.FAINT -> PcTone.Faint
}

@Composable
fun seerrToneColors(tone: SeerrBadgeTone): Pair<Color, Color> {
    val colors = LocalPcColors.current
    return when (tone) {
        SeerrBadgeTone.OK -> colors.ok to colors.okSoft
        SeerrBadgeTone.ACCENT -> colors.accent to colors.accentSoft
        SeerrBadgeTone.WARN -> colors.warn to colors.warnSoft
        SeerrBadgeTone.BAD -> colors.bad to colors.badSoft
        SeerrBadgeTone.DIM -> colors.textDim to colors.surfaceRaised
        SeerrBadgeTone.FAINT -> colors.textFaint to colors.surfaceRaised
    }
}

@Composable
fun SeerrPoster(
    url: String?,
    mediaType: String,
    size: SeerrPosterSize = SeerrPosterSize.SM,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val shape = RoundedCornerShape(size.radius)
    val fallbackIcon = when (mediaType) {
        "collection" -> Icons.Rounded.CollectionsBookmark
        "tv" -> Icons.Rounded.Tv
        else -> Icons.Rounded.Movie
    }

    Box(
        modifier = modifier
            .size(width = size.width, height = size.height)
            .clip(shape)
            .background(colors.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = colors.textFaint,
            modifier = Modifier.size(size.iconSize),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
fun SeerrPersonAvatar(
    url: String?,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            tint = colors.textFaint,
            modifier = Modifier.size(size * 0.52f),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
fun SeerrStatusPill(
    status: SeerrStatus,
    showDot: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val badge = SeerrHelpers.statusBadge(status) ?: return
    PcPill(
        label = badge.label,
        tone = badge.tone.toPcTone(),
        showDot = showDot,
        modifier = modifier,
    )
}

@Composable
fun SeerrRatingInline(
    voteAverage: Double?,
    modifier: Modifier = Modifier,
) {
    val formatted = SeerrHelpers.formatVoteAverage(voteAverage) ?: return
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = colors.warn,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = formatted,
            color = colors.textDim,
            style = type.caption.tabular().copy(fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
fun SeerrRowSkeleton(modifier: Modifier = Modifier) {
    val colors = LocalPcColors.current
    PcCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceRaised),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceRaised),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.42f)
                        .height(11.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceRaised),
                )
            }
            Box(
                modifier = Modifier
                    .size(width = 68.dp, height = 24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.surfaceRaised),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeerrCollectionSheet(
    state: SeerrCollectionSheetState,
    onRequestMovie: (SeerrItem) -> Unit,
    onRequestAllMissing: () -> Unit,
    onSelectMovie: (SeerrItem) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val collection = state.collection
    val parts = collection?.parts.orEmpty()
    val missingParts = SeerrHelpers.collectionMissingParts(collection)
    val title = collection?.name?.takeIf { it.isNotBlank() }
        ?: state.seedTitle.takeIf { it.isNotBlank() }
        ?: "Collection"
    val poster = collection?.poster ?: state.seedPoster
    val summary = SeerrHelpers.collectionSummary(collection)

    PcBottomSheet(
        onDismissRequest = { if (!state.batchSubmitting) onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SeerrPoster(
                    url = poster,
                    mediaType = "collection",
                    size = SeerrPosterSize.MD,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        color = colors.text,
                        style = type.collapsedTitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PcPill(label = "Collection", tone = PcTone.Dim)
                        if (summary.isNotEmpty()) {
                            Text(
                                text = summary,
                                color = colors.textDim,
                                style = type.caption.tabular().copy(fontWeight = FontWeight.Medium),
                            )
                        }
                    }
                }
            }

            if (!collection?.overview.isNullOrBlank()) {
                Text(
                    text = collection!!.overview,
                    color = colors.textDim,
                    style = type.caption,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!state.error.isNullOrBlank()) {
                PcErrorBanner(title = "Collection", message = state.error)
            }

            if (state.loading && collection == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(parts, key = { it.id }) { movie ->
                        val canReq = SeerrHelpers.isRequestableMovie(movie.status)
                        val isBusy = state.busyMovieId == movie.id
                        PcCard(
                            onClick = { onSelectMovie(movie) },
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SeerrPoster(
                                    url = movie.poster,
                                    mediaType = "movie",
                                    size = SeerrPosterSize.SM,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = movie.title,
                                        color = colors.text,
                                        style = type.cardTitle.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = movie.year?.toString() ?: "TBA",
                                            color = colors.textFaint,
                                            style = type.caption.tabular(),
                                        )
                                        SeerrStatusPill(status = movie.status)
                                    }
                                }
                                if (canReq) {
                                    PcButton(
                                        label = "Request",
                                        onClick = { onRequestMovie(movie) },
                                        variant = PcButtonVariant.Primary,
                                        compact = true,
                                        busy = isBusy,
                                        enabled = !state.batchSubmitting && state.busyMovieId == null,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.ChevronRight,
                                        contentDescription = null,
                                        tint = colors.textFaint,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PcButton(
                    label = "Close",
                    onClick = onDismiss,
                    variant = PcButtonVariant.Subtle,
                    enabled = !state.batchSubmitting,
                    modifier = Modifier.weight(if (missingParts.isNotEmpty()) 0.45f else 1f),
                )
                if (missingParts.isNotEmpty()) {
                    PcButton(
                        label = "Request all missing (${missingParts.size})",
                        onClick = onRequestAllMissing,
                        variant = PcButtonVariant.Primary,
                        icon = Icons.Rounded.Add,
                        busy = state.batchSubmitting,
                        enabled = !state.loading && !state.batchSubmitting,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
fun SeerrFilterChip(
    label: String,
    count: Int? = null,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(
                width = if (selected) 0.dp else 0.5.dp,
                color = if (selected) Color.Transparent else colors.hairline,
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) colors.accent else colors.textDim,
            style = type.caption.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            maxLines = 1,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                color = if (selected) colors.accent else colors.textFaint,
                style = type.micro.tabular(),
                maxLines = 1,
            )
        }
    }
}

/**
 * Renders official Fanart ClearLogo PNG title art when [logoUrl] is present and loads cleanly,
 * with zero-flash graceful fallback to the styled text [title] when missing or failed.
 */
@Composable
fun WatchClearLogoTitle(
    title: String,
    logoUrl: String?,
    fallbackStyle: TextStyle,
    fallbackColor: Color,
    maxHeight: Dp = 60.dp,
    maxWidth: Dp = 240.dp,
    fallbackMaxLines: Int = 2,
    modifier: Modifier = Modifier,
) {
    val cleanLogo = logoUrl?.trim()?.takeIf { it.isNotEmpty() }
    var logoLoaded by remember(cleanLogo) { mutableStateOf(false) }
    var logoFailed by remember(cleanLogo) { mutableStateOf(false) }

    if (cleanLogo != null && !logoFailed) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.CenterStart,
        ) {
            if (!logoLoaded) {
                Text(
                    text = title,
                    color = fallbackColor,
                    style = fallbackStyle,
                    maxLines = fallbackMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AsyncImage(
                model = cleanLogo,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onSuccess = { logoLoaded = true },
                onError = { logoFailed = true },
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .heightIn(max = maxHeight)
                    .alpha(if (logoLoaded) 1f else 0f),
            )
        }
    } else {
        Text(
            text = title,
            color = fallbackColor,
            style = fallbackStyle,
            maxLines = fallbackMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

data class WatchBoxSetSheetState(
    val visible: Boolean = false,
    val boxSet: WatchBoxSet? = null,
    val items: List<WatchItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchBoxSetSheet(
    state: WatchBoxSetSheetState,
    resolveImageUrl: (String?) -> String?,
    onSelectItem: (WatchItem) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val boxSet = state.boxSet
    val title = boxSet?.name?.takeIf { it.isNotBlank() } ?: "Collection"
    val posterUrl = resolveImageUrl(boxSet?.poster)
    val count = state.items.size.takeIf { it > 0 } ?: (boxSet?.itemCount ?: 0)

    PcBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SeerrPoster(
                    url = posterUrl,
                    mediaType = "collection",
                    size = SeerrPosterSize.MD,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        color = colors.text,
                        style = type.collapsedTitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PcPill(label = "In Library", tone = PcTone.Ok, showDot = true)
                        if (count > 0) {
                            Text(
                                text = SeerrHelpers.boxSetCountLabel(count),
                                color = colors.textDim,
                                style = type.caption.tabular().copy(fontWeight = FontWeight.Medium),
                            )
                        }
                    }
                }
            }

            if (!boxSet?.overview.isNullOrBlank()) {
                Text(
                    text = boxSet!!.overview,
                    color = colors.textDim,
                    style = type.caption,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!state.error.isNullOrBlank()) {
                PcErrorBanner(title = "Collection", message = state.error)
            }

            if (state.loading && state.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(
                        items = state.items,
                        key = { "${it.mediaType}:${it.id}:${it.jellyfinItemId.orEmpty()}" },
                    ) { movie ->
                        PcCard(
                            onClick = { onSelectItem(movie) },
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SeerrPoster(
                                    url = resolveImageUrl(movie.poster),
                                    mediaType = movie.mediaType,
                                    size = SeerrPosterSize.SM,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = movie.title,
                                        color = colors.text,
                                        style = type.cardTitle.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        movie.year?.let { yr ->
                                            Text(
                                                text = yr.toString(),
                                                color = colors.textFaint,
                                                style = type.caption.tabular(),
                                            )
                                        }
                                        SeerrRatingInline(voteAverage = movie.voteAverage)
                                        if (movie.played) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.CheckCircle,
                                                    contentDescription = "Watched",
                                                    tint = colors.ok,
                                                    modifier = Modifier.size(13.dp),
                                                )
                                                Text(
                                                    text = "Watched",
                                                    color = colors.ok,
                                                    style = type.micro,
                                                )
                                            }
                                        }
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = colors.textFaint,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            PcButton(
                label = "Close",
                onClick = onDismiss,
                variant = PcButtonVariant.Subtle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

data class WatchSubtitleSheetState(
    val visible: Boolean = false,
    val jellyfinItemId: String = "",
    val language: String = "eng",
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val results: List<WatchRemoteSubtitle> = emptyList(),
    val downloadingSubtitleId: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchSubtitleSearchSheet(
    state: WatchSubtitleSheetState,
    onSelectLanguage: (String) -> Unit,
    onDownloadSubtitle: (WatchRemoteSubtitle) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return
    val colors = LocalPcColors.current
    val type = LocalPcType.current

    PcBottomSheet(
        onDismissRequest = { if (state.downloadingSubtitleId == null) onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Online subtitles",
                color = colors.text,
                style = type.collapsedTitle,
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(WatchPlayerHelpers.SUBTITLE_LANGUAGES, key = { it.first }) { (code, label) ->
                    SeerrFilterChip(
                        label = label,
                        selected = state.language.equals(code, ignoreCase = true),
                        onClick = { if (!state.loading) onSelectLanguage(code) },
                    )
                }
            }

            if (!state.notice.isNullOrBlank()) {
                PcPill(label = state.notice, tone = PcTone.Ok, showDot = true)
            }

            if (!state.error.isNullOrBlank()) {
                PcErrorBanner(title = "Subtitles", message = state.error)
            }

            when {
                state.loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                state.results.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No online subtitles found for this language",
                            color = colors.textDim,
                            style = type.caption,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.results, key = { it.id }) { sub ->
                            val downloading = state.downloadingSubtitleId == sub.id
                            val meta = WatchPlayerHelpers.formatSubtitleMeta(sub)
                            PcCard(
                                onClick = {
                                    if (state.downloadingSubtitleId == null) {
                                        onDownloadSubtitle(sub)
                                    }
                                },
                                contentPadding = PaddingValues(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (sub.isHashMatch) {
                                            PcPill(label = "Exact match", tone = PcTone.Accent)
                                        }
                                        Text(
                                            text = sub.name.ifBlank { "Subtitle" },
                                            color = colors.text,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (meta.isNotEmpty()) {
                                            Text(
                                                text = meta,
                                                color = colors.textDim,
                                                style = type.caption.tabular(),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (downloading) {
                                        CircularProgressIndicator(
                                            color = colors.accent,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = "Download subtitle",
                                            tint = if (sub.isHashMatch) colors.accent else colors.textDim,
                                            modifier = Modifier.size(19.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            PcButton(
                label = "Close",
                onClick = onDismiss,
                variant = PcButtonVariant.Subtle,
                enabled = state.downloadingSubtitleId == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}
