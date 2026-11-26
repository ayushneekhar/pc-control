package com.neekhar.pccontrol.feature.seerr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcScreenHeader
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcSegmentOption
import com.neekhar.pccontrol.core.ui.PcSegmentedControl
import com.neekhar.pccontrol.core.ui.PcTone

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeerrPersonScreen(
    viewModel: SeerrPersonViewModel,
    onBack: () -> Unit,
    onOpenDetail: (mediaType: String, id: Int, seed: SeerrItem?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalPcColors.current
    val person = state.person

    val age = remember(person?.birthday, person?.deathday) {
        SeerrHelpers.computeAge(person?.birthday, person?.deathday)
    }
    val birthStr = remember(person?.birthday) { SeerrHelpers.formatDate(person?.birthday) }
    val deathStr = remember(person?.deathday) { SeerrHelpers.formatDate(person?.deathday) }
    val bio = person?.biography?.trim().orEmpty()

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding(),
    ) {
        PcScreenHeader(
            title = state.displayName,
            onBack = onBack,
        )

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    top = 4.dp,
                    bottom = 36.dp + navBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.error.isNullOrBlank()) {
                    item(key = "error") {
                        PcErrorBanner(title = "Seerr", message = state.error)
                    }
                }

                // Profile Summary Card
                item(key = "profile") {
                    PcCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 96.dp, height = 132.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.surfaceRaised),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = colors.textFaint,
                                    modifier = Modifier.size(38.dp),
                                )
                                if (!state.displayProfilePath.isNullOrBlank()) {
                                    AsyncImage(
                                        model = state.displayProfilePath,
                                        contentDescription = state.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    state.displayDepartment?.let { dept ->
                                        PcPill(label = dept, tone = PcTone.Accent)
                                    }
                                    if (state.credits.isNotEmpty()) {
                                        Text(
                                            text = "${state.credits.size} ${if (state.credits.size == 1) "credit" else "credits"}",
                                            color = colors.textFaint,
                                            style = TabularNumStyle.copy(fontSize = 12.5.sp),
                                        )
                                    }
                                }

                                if (birthStr != null) {
                                    PersonMetaRow(
                                        icon = Icons.Filled.Cake,
                                        text = buildString {
                                            append(birthStr)
                                            if (age != null && deathStr == null) append(" ($age)")
                                        },
                                    )
                                }
                                if (deathStr != null) {
                                    PersonMetaRow(
                                        icon = Icons.Filled.CalendarToday,
                                        text = buildString {
                                            append("Died $deathStr")
                                            if (age != null) append(" (aged $age)")
                                        },
                                    )
                                }
                                if (!person?.placeOfBirth.isNullOrBlank()) {
                                    PersonMetaRow(
                                        icon = Icons.Filled.LocationOn,
                                        text = person!!.placeOfBirth!!,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.loading && person == null) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = colors.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }

                // Biography
                if (bio.isNotEmpty()) {
                    item(key = "biography") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PcSectionLabel(label = "Biography")
                            PcCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = bio,
                                        color = colors.textDim,
                                        fontSize = 14.sp,
                                        lineHeight = 21.sp,
                                        maxLines = if (state.bioExpanded) Int.MAX_VALUE else 5,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (bio.length > 260) {
                                        Text(
                                            text = if (state.bioExpanded) "Show less" else "Read more",
                                            color = colors.accent,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable(onClick = viewModel::toggleBioExpanded)
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Known For horizontal strip
                if (state.knownFor.isNotEmpty()) {
                    item(key = "known_for_header") {
                        PcSectionLabel(label = "Known for")
                    }
                    item(key = "known_for_row") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(
                                items = state.knownFor,
                                key = { "kf:${it.mediaType}:${it.id}" },
                            ) { credit ->
                                PersonKnownForCard(
                                    credit = credit,
                                    onClick = {
                                        onOpenDetail(credit.mediaType, credit.id, credit.toSeedItem())
                                    },
                                )
                            }
                        }
                    }
                }

                // Filmography section
                if (person != null) {
                    item(key = "filmography_header") {
                        PcSectionLabel(
                            label = "Filmography (${state.filteredCredits.size})",
                            trailing = if (state.credits.size > 1) {
                                {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(onClick = viewModel::toggleSortMode)
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Sort,
                                            contentDescription = "Sort",
                                            tint = colors.accent,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Text(
                                            text = state.sortMode.label,
                                            color = colors.accent,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }

                    item(key = "filmography_segmented") {
                        PcSegmentedControl(
                            options = listOf(
                                PcSegmentOption(SeerrPersonMediaFilter.ALL, "All (${state.credits.size})"),
                                PcSegmentOption(SeerrPersonMediaFilter.MOVIE, "Movies (${state.movieCount})"),
                                PcSegmentOption(SeerrPersonMediaFilter.TV, "TV (${state.tvCount})"),
                            ),
                            selected = state.mediaFilter,
                            onSelect = viewModel::selectMediaFilter,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (state.filteredCredits.isEmpty()) {
                        item(key = "empty_credits") {
                            PcCard(modifier = Modifier.fillMaxWidth()) {
                                PcEmptyState(
                                    icon = Icons.Filled.Movie,
                                    title = "No credits in this category",
                                )
                            }
                        }
                    } else {
                        items(
                            items = state.filteredCredits,
                            key = { "credit:${it.mediaType}:${it.id}" },
                        ) { credit ->
                            PersonCreditRow(
                                credit = credit,
                                onClick = {
                                    onOpenDetail(credit.mediaType, credit.id, credit.toSeedItem())
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonMetaRow(
    icon: ImageVector,
    text: String,
) {
    val colors = LocalPcColors.current
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textFaint,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(14.dp),
        )
        Text(
            text = text,
            color = colors.textDim,
            style = TabularNumStyle.copy(fontSize = 13.sp),
        )
    }
}

@Composable
private fun PersonKnownForCard(
    credit: SeerrPersonCredit,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val badge = SeerrHelpers.statusBadge(credit.status)
    Column(
        modifier = Modifier
            .width(106.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(width = 106.dp, height = 156.dp)
                .background(colors.surfaceRaised),
        ) {
            Icon(
                imageVector = if (credit.mediaType == "tv") Icons.Filled.Tv else Icons.Filled.Movie,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.Center),
            )
            if (!credit.poster.isNullOrBlank()) {
                AsyncImage(
                    model = credit.poster,
                    contentDescription = credit.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.mediaChip)
                        .border(0.5.dp, colors.mediaChipBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badge.label,
                        color = colors.onMedia,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = credit.title,
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            credit.year?.let { year ->
                Text(
                    text = year.toString(),
                    color = colors.textFaint,
                    style = TabularNumStyle.copy(fontSize = 11.sp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonCreditRow(
    credit: SeerrPersonCredit,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    PcCard(
        onClick = onClick,
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SeerrPoster(
                url = credit.poster,
                mediaType = credit.mediaType,
                size = SeerrPosterSize.SM,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = credit.title,
                    color = colors.text,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (credit.role.isNotBlank()) {
                    Text(
                        text = credit.role,
                        color = colors.textDim,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PcPill(
                        label = if (credit.mediaType == "tv") "TV" else "Movie",
                        tone = PcTone.Faint,
                    )
                    credit.year?.let { year ->
                        Text(
                            text = year.toString(),
                            color = colors.textFaint,
                            style = TabularNumStyle.copy(fontSize = 12.sp),
                        )
                    }
                    SeerrRatingInline(voteAverage = credit.voteAverage)
                    SeerrStatusPill(status = credit.status)
                }
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun SeerrPersonCredit.toSeedItem(): SeerrItem = SeerrItem(
    id = id,
    mediaType = mediaType,
    title = title,
    year = year,
    poster = poster,
    overview = "",
    status = status,
    voteAverage = voteAverage,
)
