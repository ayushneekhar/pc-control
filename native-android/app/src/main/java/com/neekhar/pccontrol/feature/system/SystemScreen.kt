package com.neekhar.pccontrol.feature.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.LocalCache
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.ui.PcScreenHeader
import com.neekhar.pccontrol.core.ui.PcSegmentOption
import com.neekhar.pccontrol.core.ui.PcSegmentedControl
import kotlinx.coroutines.launch

fun resolveSystemTab(raw: String?): SystemTab = when (raw?.lowercase()) {
    "services", "health" -> SystemTab.SERVICES
    else -> SystemTab.VITALS
}

@Composable
fun SystemScreen(
    api: PcApiClient,
    cache: LocalCache,
    onOpenServiceDetail: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    initialTab: String? = null,
    modifier: Modifier = Modifier,
    vitalsViewModel: VitalsViewModel = viewModel(factory = VitalsViewModel.factory(api, cache)),
    servicesViewModel: ServicesViewModel = viewModel(factory = ServicesViewModel.factory(api, cache)),
) {
    val colors = LocalPcColors.current
    val vitalsState by vitalsViewModel.uiState.collectAsStateWithLifecycle()
    val servicesState by servicesViewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val resolvedInitial = remember(initialTab) { resolveSystemTab(initialTab) }
    val pagerState = rememberPagerState(
        initialPage = resolvedInitial.ordinal,
        pageCount = { SystemTab.entries.size },
    )

    LaunchedEffect(initialTab) {
        if (!initialTab.isNullOrBlank()) {
            val targetPage = resolveSystemTab(initialTab).ordinal
            if (pagerState.currentPage != targetPage) {
                pagerState.animateScrollToPage(targetPage)
            }
        }
    }

    val currentTab = SystemTab.entries[pagerState.currentPage]

    // Lifecycle-aware polling: only poll the visible tab while the screen is RESUMED.
    LifecycleResumeEffect(vitalsViewModel, servicesViewModel, currentTab) {
        if (currentTab == SystemTab.VITALS) {
            servicesViewModel.stopPolling()
            vitalsViewModel.startPolling()
        } else {
            vitalsViewModel.stopPolling()
            servicesViewModel.startPolling()
        }
        onPauseOrDispose {
            vitalsViewModel.stopPolling()
            servicesViewModel.stopPolling()
        }
    }

    val headerStatusText = when (currentTab) {
        SystemTab.VITALS -> vitalsState.statusText
        SystemTab.SERVICES -> servicesState.statusText
    }
    val headerStatusTone = when (currentTab) {
        SystemTab.VITALS -> vitalsState.statusTone
        SystemTab.SERVICES -> servicesState.summaryTone
    }

    val segmentOptions = remember {
        listOf(
            PcSegmentOption(SystemTab.VITALS, SystemTab.VITALS.label),
            PcSegmentOption(SystemTab.SERVICES, SystemTab.SERVICES.label),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding(),
    ) {
        PcScreenHeader(
            title = "System",
            statusText = headerStatusText,
            statusTone = headerStatusTone,
            showStatusDot = headerStatusText != null,
        )

        PcSegmentedControl(
            options = segmentOptions,
            selected = currentTab,
            onSelect = { tab ->
                scope.launch {
                    pagerState.animateScrollToPage(tab.ordinal)
                }
            },
            modifier = Modifier.padding(
                start = PcDimens.ScreenPad,
                end = PcDimens.ScreenPad,
                bottom = 12.dp,
            ),
        )

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
        ) { page ->
            when (SystemTab.entries[page]) {
                SystemTab.VITALS -> VitalsTab(
                    state = vitalsState,
                    onToggleProcesses = vitalsViewModel::toggleProcesses,
                    onProcessQueryChange = vitalsViewModel::setProcessQuery,
                    onSelectSort = vitalsViewModel::selectSort,
                )
                SystemTab.SERVICES -> ServicesTab(
                    state = servicesState,
                    onRefresh = servicesViewModel::refresh,
                    onSelectFilter = servicesViewModel::selectFilter,
                    onOpenServiceDetail = onOpenServiceDetail,
                    onOpenDownloads = onOpenDownloads,
                )
            }
        }
    }
}

/**
 * Standalone Vitals screen wrapper for direct deep-link / stack navigation (`PcRoute.Vitals`).
 */
@Composable
fun VitalsScreen(
    api: PcApiClient,
    cache: LocalCache,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VitalsViewModel = viewModel(factory = VitalsViewModel.factory(api, cache)),
) {
    val colors = LocalPcColors.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.startPolling()
        onPauseOrDispose { viewModel.stopPolling() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        PcScreenHeader(
            title = "Vitals",
            statusText = state.statusText,
            statusTone = state.statusTone,
            showStatusDot = state.statusText != null,
            onBack = onBack,
        )
        VitalsTab(
            state = state,
            onToggleProcesses = viewModel::toggleProcesses,
            onProcessQueryChange = viewModel::setProcessQuery,
            onSelectSort = viewModel::selectSort,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Standalone Services screen wrapper for direct deep-link / stack navigation (`PcRoute.Services`).
 */
@Composable
fun ServicesScreen(
    api: PcApiClient,
    cache: LocalCache,
    onBack: () -> Unit,
    onOpenServiceDetail: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServicesViewModel = viewModel(factory = ServicesViewModel.factory(api, cache)),
) {
    val colors = LocalPcColors.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.startPolling()
        onPauseOrDispose { viewModel.stopPolling() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        PcScreenHeader(
            title = "Services",
            statusText = state.statusText,
            statusTone = state.summaryTone,
            showStatusDot = state.statusText != null,
            onBack = onBack,
        )
        ServicesTab(
            state = state,
            onRefresh = viewModel::refresh,
            onSelectFilter = viewModel::selectFilter,
            onOpenServiceDetail = onOpenServiceDetail,
            onOpenDownloads = onOpenDownloads,
            modifier = Modifier.weight(1f),
        )
    }
}
