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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcSheetDragHandle
import com.neekhar.pccontrol.core.ui.PcTone

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
        "collection" -> Icons.Filled.CollectionsBookmark
        "tv" -> Icons.Filled.Tv
        else -> Icons.Filled.Movie
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
            imageVector = Icons.Filled.Person,
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
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = colors.warn,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = formatted,
            color = colors.textDim,
            style = TabularNumStyle.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val collection = state.collection
    val parts = collection?.parts.orEmpty()
    val missingParts = SeerrHelpers.collectionMissingParts(collection)
    val title = collection?.name?.takeIf { it.isNotBlank() }
        ?: state.seedTitle.takeIf { it.isNotBlank() }
        ?: "Collection"
    val poster = collection?.poster ?: state.seedPoster
    val summary = SeerrHelpers.collectionSummary(collection)

    ModalBottomSheet(
        onDismissRequest = { if (!state.batchSubmitting) onDismiss() },
        sheetState = sheetState,
        containerColor = colors.bg,
        contentColor = colors.text,
        dragHandle = { PcSheetDragHandle() },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 16.dp),
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
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
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
                                style = TabularNumStyle.copy(
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        }
                    }
                }
            }

            if (!collection?.overview.isNullOrBlank()) {
                Text(
                    text = collection!!.overview,
                    color = colors.textDim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
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
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.SemiBold,
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
                                            style = TabularNumStyle.copy(fontSize = 12.sp),
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
                                        imageVector = Icons.Filled.ChevronRight,
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
                        icon = Icons.Filled.Add,
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
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                color = if (selected) colors.accent else colors.textFaint,
                style = TabularNumStyle.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }
}
