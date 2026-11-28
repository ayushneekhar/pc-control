package com.neekhar.pccontrol.navigation

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.neekhar.pccontrol.AppContainer
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.platform.ShareDestination
import com.neekhar.pccontrol.core.platform.SharePayload
import com.neekhar.pccontrol.core.platform.SharedAttachment
import com.neekhar.pccontrol.core.platform.SharedTarget
import com.neekhar.pccontrol.core.theme.PcTheme
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcConfirmDialog
import com.neekhar.pccontrol.feature.agent.AgentRuleScreen
import com.neekhar.pccontrol.feature.agent.AgentSettingsScreen
import com.neekhar.pccontrol.feature.agent.AgentSettingsViewModel
import com.neekhar.pccontrol.feature.agent.AgentViewScreen
import com.neekhar.pccontrol.feature.agent.AgentViewViewModel
import com.neekhar.pccontrol.feature.agent.UsageAccountDetailScreen
import com.neekhar.pccontrol.feature.agent.UsageWindowKey
import com.neekhar.pccontrol.feature.cams.CamsScreen
import com.neekhar.pccontrol.feature.chat.ChatScreen
import com.neekhar.pccontrol.feature.chat.ChatViewModel
import com.neekhar.pccontrol.feature.chat.PickedLocalFile
import com.neekhar.pccontrol.feature.downloads.DownloadsScreen
import com.neekhar.pccontrol.feature.home.HomeScreen
import com.neekhar.pccontrol.feature.root.RootScreen
import com.neekhar.pccontrol.feature.root.RootViewModel
import com.neekhar.pccontrol.feature.seerr.SeerrDetailScreen
import com.neekhar.pccontrol.feature.seerr.SeerrDetailViewModel
import com.neekhar.pccontrol.feature.seerr.SeerrItem
import com.neekhar.pccontrol.feature.seerr.SeerrPersonScreen
import com.neekhar.pccontrol.feature.seerr.SeerrPersonViewModel
import com.neekhar.pccontrol.feature.seerr.SeerrScreen
import com.neekhar.pccontrol.feature.seerr.SeerrViewModel
import com.neekhar.pccontrol.feature.settings.ConnectionSettingsScreen
import com.neekhar.pccontrol.feature.settings.SettingsScreen
import com.neekhar.pccontrol.feature.settings.SettingsViewModel
import com.neekhar.pccontrol.feature.space.SpaceScreen
import com.neekhar.pccontrol.feature.space.SpaceViewModel
import com.neekhar.pccontrol.feature.system.ServiceDetailScreen
import com.neekhar.pccontrol.feature.system.ServicesScreen
import com.neekhar.pccontrol.feature.system.SystemScreen
import com.neekhar.pccontrol.feature.system.VitalsScreen
import com.neekhar.pccontrol.feature.tvremote.TvRemoteScreen
import com.neekhar.pccontrol.feature.tvremote.TvRemoteViewModel
import com.neekhar.pccontrol.feature.watch.player.WatchPlayerScreen
import com.neekhar.pccontrol.feature.watch.player.WatchPlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/**
 * 5-tab Material 3 Expressive bottom navigation destinations.
 * Chat sits in the center slot between System and Root.
 */
enum class PcBottomTab(
    val label: String,
    val unselectedIcon: ImageVector,
    val selectedIcon: ImageVector,
    val route: PcRoute,
    val routeClass: KClass<out PcRoute>,
) {
    Home("Home", Icons.Outlined.Home, Icons.Rounded.Home, PcRoute.Home, PcRoute.Home::class),
    System("System", Icons.Outlined.GridView, Icons.Rounded.GridView, PcRoute.System(), PcRoute.System::class),
    Chat("Chat", Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble, PcRoute.Chat, PcRoute.Chat::class),
    Root("Root", Icons.Outlined.Shield, Icons.Rounded.Shield, PcRoute.Root(), PcRoute.Root::class),
    Settings("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings, PcRoute.Settings, PcRoute.Settings::class),
}

/**
 * Screen composable registry wired into [AppNavHost].
 */
class PcScreenRegistry(
    val home: @Composable (NavHostController) -> Unit,
    val system: @Composable (NavHostController, PcRoute.System) -> Unit,
    val vitals: @Composable (NavHostController) -> Unit,
    val services: @Composable (NavHostController) -> Unit,
    val serviceDetail: @Composable (NavHostController, PcRoute.ServiceDetail) -> Unit,
    val chat: @Composable (NavHostController) -> Unit,
    val root: @Composable (NavHostController, PcRoute.Root) -> Unit,
    val settings: @Composable (NavHostController) -> Unit,
    val cams: @Composable (NavHostController, PcRoute.Cams) -> Unit,
    val seerr: @Composable (NavHostController) -> Unit,
    val seerrDetail: @Composable (NavHostController, PcRoute.SeerrDetail) -> Unit,
    val seerrPerson: @Composable (NavHostController, PcRoute.SeerrPerson) -> Unit,
    val space: @Composable (NavHostController) -> Unit,
    val downloads: @Composable (NavHostController) -> Unit,
    val agentSettings: @Composable (NavHostController) -> Unit,
    val usageAccountDetail: @Composable (NavHostController, PcRoute.UsageAccountDetail) -> Unit,
    val agentView: @Composable (NavHostController) -> Unit,
    val agentRule: @Composable (NavHostController, PcRoute.AgentRule) -> Unit,
    val connectionSettings: @Composable (NavHostController) -> Unit,
    val tvRemote: @Composable (NavHostController) -> Unit,
    val watchPlayer: @Composable (NavHostController, PcRoute.WatchPlayer) -> Unit,
)

@Serializable
private data class AddMagnetRequest(val urls: String)

private inline fun <reified VM : ViewModel> simpleViewModelFactory(
    crossinline creator: () -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
}

private const val MAX_SHARED_FILE_BYTES = 20L * 1024L * 1024L

private suspend fun readSharedAttachments(
    context: Context,
    attachments: List<SharedAttachment>,
): List<PickedLocalFile> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    attachments.mapNotNull { att ->
        runCatching {
            val uri = Uri.parse(att.uriString)
            val bytes = resolver.openInputStream(uri)?.use { stream ->
                val data = stream.readBytes()
                if (data.size.toLong() > MAX_SHARED_FILE_BYTES) null else data
            } ?: return@runCatching null
            val safeName = att.displayName?.takeIf { it.isNotBlank() }
                ?: if (att.isImage) "shared-image.jpg" else "shared-file"
            PickedLocalFile(
                name = safeName,
                mimeType = att.mimeType.ifBlank { "application/octet-stream" },
                bytes = bytes,
            )
        }.getOrNull()
    }
}

/**
 * Top-level application shell hosting the 5-tab bottom navigation bar, [AppNavHost],
 * real feature screens wired via [AppContainer], deep-link dispatch, and share-intent routing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PcAppShell(
    container: AppContainer,
    activityProvider: () -> FragmentActivity?,
    pendingDeepLink: DeepLinkTarget?,
    onDeepLinkConsumed: () -> Unit,
    pendingShare: SharePayload?,
    onShareConsumed: () -> Unit,
    isInPictureInPictureMode: Boolean = false,
    onOpenExternalUrl: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val colors = PcTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // Activity-scoped ViewModels shared across tabs, badges, and detail screens
    val rootSignerBridge = remember(container) {
        container.createRootSignerBridge(activityProvider)
    }
    val settingsPlatformBridge = remember(container) {
        container.createSettingsPlatformBridge(activityProvider)
    }

    val chatViewModel: ChatViewModel = viewModel(
        key = "pc_chat_vm",
        factory = ChatViewModel.factory(container.chatRepository),
    )
    val rootViewModel: RootViewModel = viewModel(
        key = "pc_root_vm",
        factory = simpleViewModelFactory { RootViewModel(container.apiClient, rootSignerBridge) },
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        key = "pc_settings_vm",
        factory = simpleViewModelFactory {
            SettingsViewModel(container.apiClient, container.prefs, settingsPlatformBridge, autoLoad = false)
        },
    )
    val agentSettingsViewModel: AgentSettingsViewModel = viewModel(
        key = "pc_agent_settings_vm",
        factory = simpleViewModelFactory { AgentSettingsViewModel(container.apiClient, autoLoad = false) },
    )
    val agentViewViewModel: AgentViewViewModel = viewModel(
        key = "pc_agent_view_vm",
        factory = simpleViewModelFactory { AgentViewViewModel(container.apiClient, autoLoad = false) },
    )
    val seerrViewModel: SeerrViewModel = viewModel(
        key = "pc_seerr_vm",
        factory = simpleViewModelFactory {
            SeerrViewModel(
                repository = container.seerrRepository,
                autoLoad = false,
                downloadManager = container.watchDownloadManager,
                transportManager = container.transportManager,
            )
        },
    )
    val spaceViewModel: SpaceViewModel = viewModel(
        key = "pc_space_vm",
        factory = simpleViewModelFactory { SpaceViewModel(container.spaceRepository, autoLoad = false) },
    )
    val tvRemoteViewModel: TvRemoteViewModel = viewModel(
        key = "pc_tv_remote_vm",
        factory = simpleViewModelFactory { TvRemoteViewModel(container.tvRemoteRepository, container.prefs) },
    )

    val rootState by rootViewModel.uiState.collectAsStateWithLifecycle()
    val rootPendingCount = rootState.waitingCount

    // In-memory seeds for instant Seerr detail & person headers
    val seerrItemSeeds = remember { mutableStateMapOf<String, SeerrItem>() }
    val seerrPersonSeeds = remember { mutableStateMapOf<Int, Pair<String, String?>>() }
    val latestInPip = rememberUpdatedState(isInPictureInPictureMode)

    val screens = remember(
        container,
        chatViewModel,
        rootViewModel,
        settingsViewModel,
        agentSettingsViewModel,
        agentViewViewModel,
        seerrViewModel,
        spaceViewModel,
        tvRemoteViewModel,
        onOpenExternalUrl,
    ) {
        PcScreenRegistry(
            home = { nav ->
                HomeScreen(
                    api = container.apiClient,
                    cache = container.cache,
                    onOpenVitals = { nav.navigateToTopTab(PcRoute.System(initialTab = "vitals")) },
                    onOpenServices = { nav.navigateToTopTab(PcRoute.System(initialTab = "services")) },
                    onOpenCams = { tab -> nav.navigate(PcRoute.Cams(initialTab = tab)) { launchSingleTop = true } },
                    onOpenSeerr = { nav.navigate(PcRoute.Seerr) { launchSingleTop = true } },
                    onOpenSpace = { nav.navigate(PcRoute.Space) { launchSingleTop = true } },
                    onOpenDownloads = { nav.navigate(PcRoute.Downloads) { launchSingleTop = true } },
                    onOpenAgentView = { nav.navigate(PcRoute.AgentView) { launchSingleTop = true } },
                    onOpenSettings = { nav.navigateToTopTab(PcRoute.Settings) },
                    onUnlockKeyring = { nav.navigateToTopTab(PcRoute.Settings) },
                    tvRemoteViewModel = tvRemoteViewModel,
                    onOpenTvRemote = { nav.navigate(PcRoute.TvRemote) { launchSingleTop = true } },
                )
            },
            system = { nav, route ->
                SystemScreen(
                    api = container.apiClient,
                    cache = container.cache,
                    onOpenServiceDetail = { unit -> nav.navigate(PcRoute.ServiceDetail(unit)) },
                    onOpenDownloads = { nav.navigate(PcRoute.Downloads) { launchSingleTop = true } },
                    initialTab = route.initialTab,
                )
            },
            vitals = { nav ->
                VitalsScreen(
                    api = container.apiClient,
                    cache = container.cache,
                    onBack = { nav.popBackStack() },
                )
            },
            services = { nav ->
                ServicesScreen(
                    api = container.apiClient,
                    cache = container.cache,
                    onBack = { nav.popBackStack() },
                    onOpenServiceDetail = { unit -> nav.navigate(PcRoute.ServiceDetail(unit)) },
                    onOpenDownloads = { nav.navigate(PcRoute.Downloads) { launchSingleTop = true } },
                )
            },
            serviceDetail = { nav, route ->
                ServiceDetailScreen(
                    unit = route.unit,
                    api = container.apiClient,
                    cache = container.cache,
                    onBack = { nav.popBackStack() },
                )
            },
            chat = { nav ->
                ChatScreen(
                    viewModel = chatViewModel,
                    onOpenExternalUrl = onOpenExternalUrl,
                    onOpenAgentSettings = { nav.navigate(PcRoute.AgentSettings) { launchSingleTop = true } },
                )
            },
            root = { nav, route ->
                RootScreen(
                    viewModel = rootViewModel,
                    initialRequestId = route.requestId,
                    onOpenSettings = { nav.navigateToTopTab(PcRoute.Settings) },
                )
            },
            settings = { nav ->
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onOpenAgentSettings = { nav.navigate(PcRoute.AgentSettings) { launchSingleTop = true } },
                    onOpenAgentView = { nav.navigate(PcRoute.AgentView) { launchSingleTop = true } },
                    onOpenConnectionSettings = { nav.navigate(PcRoute.ConnectionSettings) { launchSingleTop = true } },
                )
            },
            cams = { nav, route ->
                CamsScreen(
                    apiClient = container.apiClient,
                    prefs = container.prefs,
                    cache = container.cache,
                    initialTab = route.initialTab,
                    sharedYoutubeUrl = route.sharedYoutubeUrl,
                    initialReplayCam = route.replayCam,
                    initialReplayTsMs = route.replayTsMs,
                    isInPictureInPictureMode = latestInPip.value,
                    onBack = { nav.popBackStack() },
                )
            },
            seerr = { nav ->
                SeerrScreen(
                    viewModel = seerrViewModel,
                    onBack = { nav.popBackStack() },
                    onOpenDetail = { mediaType, id, seed ->
                        if (seed != null) seerrItemSeeds["$mediaType:$id"] = seed
                        nav.navigate(PcRoute.SeerrDetail(mediaType = mediaType, id = id))
                    },
                    onPlayOnPhone = { jfId, tmdbId, mediaType, season, episode, preferOffline ->
                        nav.navigate(
                            PcRoute.WatchPlayer(
                                jellyfinItemId = jfId,
                                tmdbId = tmdbId,
                                mediaType = mediaType,
                                season = season,
                                episode = episode,
                                preferOffline = preferOffline,
                            ),
                        )
                    },
                    tvRemoteViewModel = tvRemoteViewModel,
                    onOpenTvRemote = { nav.navigate(PcRoute.TvRemote) { launchSingleTop = true } },
                )
            },
            seerrDetail = { nav, route ->
                val seed = seerrItemSeeds["${route.mediaType}:${route.id}"]
                val detailVm: SeerrDetailViewModel = viewModel(
                    key = "seerr_detail_${route.mediaType}_${route.id}",
                    factory = simpleViewModelFactory {
                        SeerrDetailViewModel(
                            repository = container.seerrRepository,
                            mediaType = route.mediaType,
                            tmdbId = route.id,
                            seed = seed,
                            downloadManager = container.watchDownloadManager,
                            transportManager = container.transportManager,
                            watchPlayerRepository = container.watchPlayerRepository,
                        )
                    },
                )
                SeerrDetailScreen(
                    viewModel = detailVm,
                    onBack = { nav.popBackStack() },
                    onOpenDetail = { mediaType, id, nextSeed ->
                        if (nextSeed != null) seerrItemSeeds["$mediaType:$id"] = nextSeed
                        nav.navigate(PcRoute.SeerrDetail(mediaType = mediaType, id = id))
                    },
                    onOpenPerson = { id, name, profilePath ->
                        seerrPersonSeeds[id] = name to profilePath
                        nav.navigate(PcRoute.SeerrPerson(id = id))
                    },
                    onPlayOnPhone = { jfId, tmdbId, mediaType, season, episode, preferOffline ->
                        nav.navigate(
                            PcRoute.WatchPlayer(
                                jellyfinItemId = jfId,
                                tmdbId = tmdbId,
                                mediaType = mediaType,
                                season = season,
                                episode = episode,
                                preferOffline = preferOffline,
                            ),
                        )
                    },
                    tvRemoteViewModel = tvRemoteViewModel,
                    onOpenTvRemote = { nav.navigate(PcRoute.TvRemote) { launchSingleTop = true } },
                )
            },
            seerrPerson = { nav, route ->
                val seed = seerrPersonSeeds[route.id]
                val personVm: SeerrPersonViewModel = viewModel(
                    key = "seerr_person_${route.id}",
                    factory = simpleViewModelFactory {
                        SeerrPersonViewModel(
                            repository = container.seerrRepository,
                            personId = route.id,
                            seedName = seed?.first,
                            seedProfilePath = seed?.second,
                        )
                    },
                )
                SeerrPersonScreen(
                    viewModel = personVm,
                    onBack = { nav.popBackStack() },
                    onOpenDetail = { mediaType, id, nextSeed ->
                        if (nextSeed != null) seerrItemSeeds["$mediaType:$id"] = nextSeed
                        nav.navigate(PcRoute.SeerrDetail(mediaType = mediaType, id = id))
                    },
                )
            },
            space = { nav ->
                SpaceScreen(
                    viewModel = spaceViewModel,
                    onBack = { nav.popBackStack() },
                )
            },
            downloads = { nav ->
                DownloadsScreen(
                    api = container.apiClient,
                    cache = container.cache,
                    onBack = { nav.popBackStack() },
                )
            },
            agentSettings = { nav ->
                AgentSettingsScreen(
                    viewModel = agentSettingsViewModel,
                    onBack = { nav.popBackStack() },
                    onOpenAccountDetail = { accountId, initialWindow ->
                        nav.navigate(
                            PcRoute.UsageAccountDetail(
                                accountId = accountId,
                                initialWindow = initialWindow.wireValue,
                            ),
                        )
                    },
                )
            },
            usageAccountDetail = { nav, route ->
                UsageAccountDetailScreen(
                    viewModel = agentSettingsViewModel,
                    accountId = route.accountId,
                    initialWindow = UsageWindowKey.fromWire(route.initialWindow),
                    onBack = { nav.popBackStack() },
                )
            },
            agentView = { nav ->
                AgentViewScreen(
                    viewModel = agentViewViewModel,
                    onBack = { nav.popBackStack() },
                    onOpenRule = { url, ruleId ->
                        nav.navigate(PcRoute.AgentRule(ruleId = ruleId, url = url))
                    },
                )
            },
            agentRule = { nav, route ->
                AgentRuleScreen(
                    viewModel = agentViewViewModel,
                    url = route.url,
                    ruleId = route.ruleId,
                    onBack = { nav.popBackStack() },
                )
            },
            connectionSettings = { nav ->
                ConnectionSettingsScreen(
                    viewModel = settingsViewModel,
                    onBack = { nav.popBackStack() },
                )
            },
            tvRemote = { nav ->
                TvRemoteScreen(
                    viewModel = tvRemoteViewModel,
                    onBack = { nav.popBackStack() },
                    onOpenSeerr = { nav.navigate(PcRoute.Seerr) { launchSingleTop = true } },
                )
            },
            watchPlayer = { nav, route ->
                val playerKey = "watch_player_${route.jellyfinItemId.orEmpty()}_${route.tmdbId ?: 0}_${route.season ?: -1}_${route.episode ?: -1}_${route.preferOffline}"
                val playerVm: WatchPlayerViewModel = viewModel(
                    key = playerKey,
                    factory = simpleViewModelFactory {
                        WatchPlayerViewModel(
                            playerRepo = container.watchPlayerRepository,
                            downloadManager = container.watchDownloadManager,
                            transportManager = container.transportManager,
                            initialJellyfinItemId = route.jellyfinItemId,
                            initialTmdbId = route.tmdbId,
                            initialMediaType = route.mediaType,
                            initialSeason = route.season,
                            initialEpisode = route.episode,
                            preferOffline = route.preferOffline,
                        )
                    },
                )
                WatchPlayerScreen(
                    viewModel = playerVm,
                    activityProvider = activityProvider,
                    isInPictureInPictureMode = latestInPip.value,
                    mediaStreamingClient = container.apiClient.mediaStreamingClient(),
                    tvRemoteViewModel = tvRemoteViewModel,
                    onBack = { nav.popBackStack() },
                    onHandedOffToTv = {
                        tvRemoteViewModel.notifyPlaybackStarted()
                        nav.popBackStack()
                        nav.navigate(PcRoute.TvRemote) { launchSingleTop = true }
                    },
                )
            },
        )
    }

    var lastSelectedTab by remember { mutableStateOf(PcBottomTab.Home) }
    val activeTab = PcBottomTab.entries.firstOrNull { tab ->
        currentDestination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
    }
    if (activeTab != null) {
        lastSelectedTab = activeTab
    }

    val isOnChat = activeTab == PcBottomTab.Chat
    val isImeVisible = WindowInsets.isImeVisible
    val showBottomBar = !isInPictureInPictureMode && activeTab != null && !(isOnChat && isImeVisible)

    var pendingMagnet by remember { mutableStateOf<SharedTarget.Magnet?>(null) }
    var magnetBusy by remember { mutableStateOf(false) }

    LaunchedEffect(pendingDeepLink) {
        val target = pendingDeepLink ?: return@LaunchedEffect
        when (target) {
            is DeepLinkTarget.Route -> navController.navigateToDeepLink(target.route)
            is DeepLinkTarget.T3Thread -> {
                onOpenExternalUrl(target.originalUrl)
                navController.navigateToDeepLink(PcRoute.Chat)
            }
        }
        onDeepLinkConsumed()
    }

    LaunchedEffect(pendingShare) {
        val payload = pendingShare ?: return@LaunchedEffect
        if (payload.isEmpty) {
            onShareConsumed()
            return@LaunchedEffect
        }
        when (payload.suggestedDestination) {
            ShareDestination.YOUTUBE -> {
                val ytUrl = (payload.target as? SharedTarget.YouTube)?.url
                if (!ytUrl.isNullOrBlank()) {
                    navController.navigate(PcRoute.Cams(initialTab = "music", sharedYoutubeUrl = ytUrl)) {
                        launchSingleTop = true
                    }
                }
                onShareConsumed()
            }

            ShareDestination.MEDIA_REQUEST -> {
                val mediaUrl = (payload.target as? SharedTarget.Media)?.url
                onShareConsumed()
                if (!mediaUrl.isNullOrBlank()) {
                    when (val resolved = container.seerrRepository.resolveUrl(mediaUrl)) {
                        is ApiResult.Success -> {
                            val item = resolved.data
                            seerrItemSeeds["${item.mediaType}:${item.id}"] = item
                            navController.navigate(PcRoute.SeerrDetail(mediaType = item.mediaType, id = item.id)) {
                                launchSingleTop = true
                            }
                        }
                        is ApiResult.Error -> {
                            seerrViewModel.onQueryChange(mediaUrl)
                            navController.navigate(PcRoute.Seerr) { launchSingleTop = true }
                        }
                    }
                } else {
                    navController.navigate(PcRoute.Seerr) { launchSingleTop = true }
                }
            }

            ShareDestination.MAGNET -> {
                val magnet = payload.target as? SharedTarget.Magnet
                onShareConsumed()
                if (magnet != null) {
                    pendingMagnet = magnet
                }
            }

            ShareDestination.CHAT -> {
                onShareConsumed()
                navController.navigateToDeepLink(PcRoute.Chat)
                val sharedText = buildString {
                    val existing = chatViewModel.uiState.value.draft.trim()
                    if (existing.isNotEmpty()) append(existing).append("\n")
                    val incoming = when (val t = payload.target) {
                        is SharedTarget.WebUrl -> t.url
                        is SharedTarget.PlainText -> t.text
                        is SharedTarget.YouTube -> t.url
                        is SharedTarget.Media -> t.url
                        is SharedTarget.Magnet -> t.uri
                        null -> payload.text.orEmpty()
                    }.trim()
                    if (incoming.isNotEmpty()) append(incoming)
                }
                if (sharedText.isNotEmpty()) {
                    chatViewModel.onDraftChanged(sharedText)
                }
                if (payload.attachments.isNotEmpty()) {
                    val files = readSharedAttachments(context, payload.attachments)
                    if (files.isNotEmpty()) {
                        chatViewModel.uploadFiles(files)
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
        containerColor = colors.bg,
        contentColor = colors.text,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing),
                ),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = tween(durationMillis = 160, easing = FastOutLinearInEasing),
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 120, easing = FastOutLinearInEasing),
                ),
            ) {
                PcBottomNavBar(
                    tabs = PcBottomTab.entries,
                    isSelected = { tab -> tab == (activeTab ?: lastSelectedTab) },
                    badgeFor = { tab ->
                        if (tab == PcBottomTab.Root && rootPendingCount > 0) rootPendingCount else null
                    },
                    onSelect = { tab ->
                        navController.navigateToTopTab(tab.route)
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            AppNavHost(
                navController = navController,
                screens = screens,
                modifier = Modifier.fillMaxSize(),
            )
            // Seamless flipped bottom fade scrim right above the nav bar on scrollable tabs
            // (omitted on Chat so the pinned composer stays crisp and unobscured)
            AnimatedVisibility(
                visible = showBottomBar && !isOnChat,
                enter = fadeIn(
                    animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing),
                ),
                exit = fadeOut(
                    animationSpec = tween(durationMillis = 120, easing = FastOutLinearInEasing),
                ),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(
                            Brush.verticalGradient(
                                0.00f to Color.Transparent,
                                0.50f to colors.bg.copy(alpha = 0.5f),
                                1.00f to colors.bg,
                            ),
                        ),
                )
            }
        }
    }

    pendingMagnet?.let { magnet ->
        PcConfirmDialog(
            title = "Add torrent",
            message = "Add \"${magnet.name}\" to qBittorrent on your PC?",
            confirmLabel = "Add",
            dismissLabel = "Cancel",
            confirmVariant = PcButtonVariant.Primary,
            confirmBusy = magnetBusy,
            onConfirm = {
                magnetBusy = true
                scope.launch {
                    runCatching {
                        container.apiClient.postJson<AddMagnetRequest, Unit>(
                            pathOrUrl = "/pc/api/downloads/add",
                            body = AddMagnetRequest(urls = magnet.uri),
                        )
                    }
                    magnetBusy = false
                    pendingMagnet = null
                    navController.navigate(PcRoute.Downloads) { launchSingleTop = true }
                }
            },
            onDismiss = {
                if (!magnetBusy) pendingMagnet = null
            },
        )
    }
}

private fun NavHostController.navigateToTopTab(route: PcRoute) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = route !is PcRoute.System && route !is PcRoute.Root
    }
}

private fun NavHostController.navigateToDeepLink(route: PcRoute) {
    val isTopTab = route is PcRoute.Home ||
        route is PcRoute.System ||
        route is PcRoute.Chat ||
        route is PcRoute.Root ||
        route is PcRoute.Settings

    if (isTopTab) {
        navigateToTopTab(route)
    } else {
        navigate(route) {
            launchSingleTop = true
        }
    }
}

private fun NavDestination?.isTopTabDestination(): Boolean =
    this != null && PcBottomTab.entries.any { tab ->
        hierarchy.any { it.hasRoute(tab.routeClass) }
    }

private fun NavDestination?.isDrillInDestination(): Boolean =
    this != null && !isTopTabDestination()

@Composable
fun AppNavHost(
    navController: NavHostController,
    screens: PcScreenRegistry,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = PcRoute.Home,
        modifier = modifier,
        enterTransition = {
            if (targetState.destination.isDrillInDestination()) {
                slideInHorizontally(
                    initialOffsetX = { (it * 0.22f).toInt() },
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 210, delayMillis = 35, easing = LinearOutSlowInEasing),
                )
            } else {
                fadeIn(
                    animationSpec = tween(durationMillis = 190, delayMillis = 35, easing = LinearOutSlowInEasing),
                ) + scaleIn(
                    initialScale = 0.97f,
                    animationSpec = tween(durationMillis = 190, delayMillis = 35, easing = FastOutSlowInEasing),
                )
            }
        },
        exitTransition = {
            if (targetState.destination.isDrillInDestination()) {
                slideOutHorizontally(
                    targetOffsetX = { -(it * 0.14f).toInt() },
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 110, easing = FastOutLinearInEasing),
                )
            } else {
                fadeOut(
                    animationSpec = tween(durationMillis = 90, easing = FastOutLinearInEasing),
                )
            }
        },
        popEnterTransition = {
            if (initialState.destination.isDrillInDestination()) {
                slideInHorizontally(
                    initialOffsetX = { -(it * 0.14f).toInt() },
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 200, delayMillis = 30, easing = LinearOutSlowInEasing),
                )
            } else {
                fadeIn(
                    animationSpec = tween(durationMillis = 190, delayMillis = 35, easing = LinearOutSlowInEasing),
                ) + scaleIn(
                    initialScale = 0.97f,
                    animationSpec = tween(durationMillis = 190, delayMillis = 35, easing = FastOutSlowInEasing),
                )
            }
        },
        popExitTransition = {
            if (initialState.destination.isDrillInDestination()) {
                slideOutHorizontally(
                    targetOffsetX = { (it * 0.22f).toInt() },
                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 110, easing = FastOutLinearInEasing),
                )
            } else {
                fadeOut(
                    animationSpec = tween(durationMillis = 90, easing = FastOutLinearInEasing),
                )
            }
        },
    ) {
        composable<PcRoute.Home> { screens.home(navController) }
        composable<PcRoute.System> { entry -> screens.system(navController, entry.toRoute()) }
        composable<PcRoute.Vitals> { screens.vitals(navController) }
        composable<PcRoute.Services> { screens.services(navController) }
        composable<PcRoute.ServiceDetail> { entry -> screens.serviceDetail(navController, entry.toRoute()) }
        composable<PcRoute.Chat> { screens.chat(navController) }
        composable<PcRoute.Root> { entry -> screens.root(navController, entry.toRoute()) }
        composable<PcRoute.Settings> { screens.settings(navController) }
        composable<PcRoute.Cams> { entry -> screens.cams(navController, entry.toRoute()) }
        composable<PcRoute.Seerr> { screens.seerr(navController) }
        composable<PcRoute.SeerrDetail> { entry -> screens.seerrDetail(navController, entry.toRoute()) }
        composable<PcRoute.SeerrPerson> { entry -> screens.seerrPerson(navController, entry.toRoute()) }
        composable<PcRoute.Space> { screens.space(navController) }
        composable<PcRoute.Downloads> { screens.downloads(navController) }
        composable<PcRoute.AgentSettings> { screens.agentSettings(navController) }
        composable<PcRoute.UsageAccountDetail> { entry -> screens.usageAccountDetail(navController, entry.toRoute()) }
        composable<PcRoute.AgentView> { screens.agentView(navController) }
        composable<PcRoute.AgentRule> { entry -> screens.agentRule(navController, entry.toRoute()) }
        composable<PcRoute.ConnectionSettings> { screens.connectionSettings(navController) }
        composable<PcRoute.TvRemote> { screens.tvRemote(navController) }
        composable<PcRoute.WatchPlayer> { entry -> screens.watchPlayer(navController, entry.toRoute()) }
    }
}

/**
 * Material 3 Expressive 5-tab bottom bar matching docs/DESIGN.md:
 * - True black (or light bg) fill paired with a seamless flipped gradient fade above the bar
 *   (no hard top hairline divider)
 * - Active tab: smoothly sliding 64x32dp mint-soft pill (`accentSoft`) with mint icon (`accent`)
 *   and crisp text label
 * - Inactive tab: faint icon + label (`textFaint`)
 */
@Composable
fun PcBottomNavBar(
    tabs: List<PcBottomTab>,
    isSelected: (PcBottomTab) -> Boolean,
    badgeFor: (PcBottomTab) -> Int?,
    onSelect: (PcBottomTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PcTheme.colors
    val type = PcTheme.type
    val count = tabs.size
    if (count == 0) return
    val selectedIndex = tabs.indexOfFirst { isSelected(it) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .navigationBarsPadding(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 6.dp),
        ) {
            val tabWidth = maxWidth / count
            val pillWidth = 64.dp.coerceAtMost((tabWidth - 8.dp).coerceAtLeast(36.dp))
            val pillHeight = 32.dp
            val targetPillOffset = if (selectedIndex >= 0) {
                tabWidth * selectedIndex + (tabWidth - pillWidth) / 2f
            } else {
                0.dp
            }
            val animatedPillOffset by animateDpAsState(
                targetValue = targetPillOffset,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "bottomNavPillOffset",
            )
            val pillScaleX = remember { Animatable(1f) }
            var previousSelectedIndex by remember { mutableIntStateOf(selectedIndex) }
            LaunchedEffect(selectedIndex) {
                if (selectedIndex >= 0 && previousSelectedIndex >= 0 && selectedIndex != previousSelectedIndex) {
                    pillScaleX.snapTo(0.72f)
                    pillScaleX.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                }
                previousSelectedIndex = selectedIndex
            }

            if (selectedIndex >= 0) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(animatedPillOffset.roundToPx(), 0) }
                        .width(pillWidth)
                        .height(pillHeight)
                        .graphicsLayer { scaleX = pillScaleX.value }
                        .clip(CircleShape)
                        .background(colors.accentSoft),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val selected = isSelected(tab)
                    val badge = badgeFor(tab)
                    val interactionSource = remember { MutableInteractionSource() }
                    val iconScale = remember { Animatable(1f) }
                    var wasSelected by remember { mutableStateOf(selected) }
                    LaunchedEffect(selected) {
                        if (selected && !wasSelected) {
                            iconScale.snapTo(1f)
                            iconScale.animateTo(
                                targetValue = 0.88f,
                                animationSpec = tween(durationMillis = 60, easing = FastOutLinearInEasing),
                            )
                            iconScale.animateTo(
                                targetValue = 1.12f,
                                animationSpec = tween(durationMillis = 90, easing = LinearOutSlowInEasing),
                            )
                            iconScale.animateTo(
                                targetValue = 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            )
                        } else if (!selected && wasSelected) {
                            iconScale.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(durationMillis = 120),
                            )
                        }
                        wasSelected = selected
                    }
                    val iconTint by animateColorAsState(
                        targetValue = if (selected) colors.accent else colors.textFaint,
                        animationSpec = tween(durationMillis = 180),
                        label = "bottomNavIconTint",
                    )
                    val labelColor by animateColorAsState(
                        targetValue = if (selected) colors.text else colors.textFaint,
                        animationSpec = tween(durationMillis = 180),
                        label = "bottomNavLabelColor",
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 52.dp)
                            .semantics { this.selected = selected }
                            .clickable(
                                role = Role.Tab,
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onSelect(tab) },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .width(pillWidth)
                                    .height(pillHeight)
                                    .clip(CircleShape)
                                    .indication(
                                        interactionSource = interactionSource,
                                        indication = ripple(),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Crossfade(
                                    targetState = selected,
                                    animationSpec = tween(durationMillis = 160),
                                    label = "bottomNavIconCrossfade",
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = iconScale.value
                                        scaleY = iconScale.value
                                    },
                                ) { isTabSelected ->
                                    Icon(
                                        imageVector = if (isTabSelected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label,
                                        tint = iconTint,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                            if (badge != null && badge > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = (-6).dp, y = (-2).dp)
                                        .clip(CircleShape)
                                        .background(colors.warn)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = if (badge > 99) "99+" else badge.toString(),
                                        color = if (colors.isDark) colors.bg else colors.surface,
                                        style = TabularNumStyle.copy(
                                            fontSize = 11.sp,
                                            lineHeight = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = tab.label,
                            color = labelColor,
                            style = type.micro.copy(
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
