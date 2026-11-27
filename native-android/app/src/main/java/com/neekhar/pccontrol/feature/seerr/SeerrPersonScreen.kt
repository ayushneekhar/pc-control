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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcScaffold
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
    val type = LocalPcType.current
    val person = state.person
    val listState = rememberLazyListState()

    val age = remember(person?.birthday, person?.deathday) {
        SeerrHelpers.computeAge(person?.birthday, person?.deathday)
    }
    val birthStr = remember(person?.birthday) { SeerrHelpers.formatDate(person?.birthday) }
    val deathStr = remember(person?.deathday) { SeerrHelpers.formatDate(person?.deathday) }
    val bio = person?.biography?.trim().orEmpty()

    PcScaffold(
        modifier = modifier,
        title = state.displayName,
        onBack = onBack,
        listState = listState,
    ) { innerPadding ->
        val topPad = innerPadding.calculateTopPadding()
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh() },
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    containerColor = colors.surfaceRaised,
                    color = colors.accent,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topPad),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = topPad,
                    bottom = 36.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.error.isNullOrBlank()) {
                    item(key = "error") {
                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                            PcErrorBanner(title = "Seerr", message = state.error)
                        }
                    }
                }

                // Profile Summary Card
                item(key = "profile") {
                    PcCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PcDimens.ScreenPad),
                    ) {
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
                                    imageVector = Icons.Rounded.Person,
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
                                            style = type.caption.tabular(),
                                        )
                                    }
                                }

                                if (birthStr != null) {
                                    PersonMetaRow(
                                        icon = Icons.Rounded.Cake,
                                        text = buildString {
                                            append(birthStr)
                                            if (age != null && deathStr == null) append(" ($age)")
                                        },
                                    )
                                }
                                if (deathStr != null) {
                                    PersonMetaRow(
                                        icon = Icons.Rounded.CalendarToday,
                                        text = buildString {
                                            append("Died $deathStr")
                                            if (age != null) append(" (aged $age)")
                                        },
                                    )
                                }
                                if (!person?.placeOfBirth.isNullOrBlank()) {
                                    PersonMetaRow(
                                        icon = Icons.Rounded.LocationOn,
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
                        Column(
                            modifier = Modifier.padding(horizontal = PcDimens.ScreenPad),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PcSectionLabel(label = "Biography")
                            PcCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = bio,
                                        color = colors.textDim,
                                        style = type.body,
                                        maxLines = if (state.bioExpanded) Int.MAX_VALUE else 5,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (bio.length > 260) {
                                        Text(
                                            text = if (state.bioExpanded) "Show less" else "Read more",
                                            color = colors.accent,
                                            style = type.caption.copy(fontWeight = FontWeight.Medium),
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

                // Edge-to-edge Known For horizontal poster carousel
                if (state.knownFor.isNotEmpty()) {
                    item(key = "known_for_header") {
                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                            PcSectionLabel(label = "Known for")
                        }
                    }
                    item(key = "known_for_row") {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
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
                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
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
                                                imageVector = Icons.AutoMirrored.Rounded.Sort,
                                                contentDescription = "Sort",
                                                tint = colors.accent,
                                                modifier = Modifier.size(15.dp),
                                            )
                                            Text(
                                                text = state.sortMode.label,
                                                color = colors.accent,
                                                style = type.caption.copy(fontWeight = FontWeight.Medium),
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PcDimens.ScreenPad),
                        )
                    }

                    if (state.filteredCredits.isEmpty()) {
                        item(key = "empty_credits") {
                            PcCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PcDimens.ScreenPad),
                            ) {
                                PcEmptyState(
                                    icon = Icons.Rounded.Movie,
                                    title = "No credits in this category",
                                )
                            }
                        }
                    } else {
                        items(
                            items = state.filteredCredits,
                            key = { "credit:${it.mediaType}:${it.id}" },
                        ) { credit ->
                            Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
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
}

@Composable
private fun PersonMetaRow(
    icon: ImageVector,
    text: String,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
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
            style = type.caption.tabular(),
        )
    }
}

@Composable
private fun PersonKnownForCard(
    credit: SeerrPersonCredit,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
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
                imageVector = if (credit.mediaType == "tv") Icons.Rounded.Tv else Icons.Rounded.Movie,
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
                        style = type.micro.copy(fontSize = 10.sp),
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
                style = type.caption.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            credit.year?.let { year ->
                Text(
                    text = year.toString(),
                    color = colors.textFaint,
                    style = type.micro.tabular(),
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
    val type = LocalPcType.current
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
                    style = type.cardTitle.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (credit.role.isNotBlank()) {
                    Text(
                        text = credit.role,
                        color = colors.textDim,
                        style = type.caption,
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
                            style = type.caption.tabular(),
                        )
                    }
                    SeerrRatingInline(voteAverage = credit.voteAverage)
                    SeerrStatusPill(status = credit.status)
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
