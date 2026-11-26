package com.neekhar.pccontrol.navigation

import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.neekhar.pccontrol.feature.settings.SettingsScreen
import com.neekhar.pccontrol.feature.settings.SettingsViewModel
import com.neekhar.pccontrol.feature.space.SpaceScreen
import com.neekhar.pccontrol.feature.space.SpaceViewModel
import com.neekhar.pccontrol.feature.system.ServiceDetailScreen
import com.neekhar.pccontrol.feature.system.ServicesScreen
import com.neekhar.pccontrol.feature.system.SystemScreen
import com.neekhar.pccontrol.feature.system.VitalsScreen
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
    val icon: ImageVector,
    val route: PcRoute,
    val routeClass: KClass<out PcRoute>,
) {
    Home("Home", Icons.Filled.Home, PcRoute.Home, PcRoute.Home::class),
    System("System", Icons.Filled.Dashboard, PcRoute.System(), PcRoute.System::class),
    Chat("Chat", Icons.Filled.ChatBubble, PcRoute.Chat, PcRoute.Chat::class),
    Root("Root", Icons.Filled.Security, PcRoute.Root(), PcRoute.Root::class),
    Settings("Settings", Icons.Filled.Settings, PcRoute.Settings, PcRoute.Settings::class),
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
            SettingsViewModel(container.apiClient, container.prefs, settingsPlatformBridge)
        },
    )
    val agentSettingsViewModel: AgentSettingsViewModel = viewModel(
        key = "pc_agent_settings_vm",
        factory = simpleViewModelFactory { AgentSettingsViewModel(container.apiClient) },
    )
    val agentViewViewModel: AgentViewViewModel = viewModel(
        key = "pc_agent_view_vm",
        factory = simpleViewModelFactory { AgentViewViewModel(container.apiClient) },
    )
    val seerrViewModel: SeerrViewModel = viewModel(
        key = "pc_seerr_vm",
        factory = simpleViewModelFactory { SeerrViewModel(container.seerrRepository) },
    )
    val spaceViewModel: SpaceViewModel = viewModel(
        key = "pc_space_vm",
        factory = simpleViewModelFactory { SpaceViewModel(container.spaceRepository) },
    )

    val rootState by rootViewModel.uiState.collectAsStateWithLifecycle()
    val rootPendingCount = rootState.waitingCount

    // In-memory seeds for instant Seerr detail & person headers
    val seerrItemSeeds = remember { mutableStateMapOf<String, SeerrItem>() }
    val seerrPersonSeeds = remember { mutableStateMapOf<Int, Pair<String, String?>>() }

    val screens = remember(
        container,
        chatViewModel,
        rootViewModel,
        settingsViewModel,
        agentSettingsViewModel,
        agentViewViewModel,
        seerrViewModel,
        spaceViewModel,
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
                    onOpenAgentSettings = { nav.navigate(PcRoute.AgentSettings) { launchSingleTop = true } },
                    onOpenAgentView = { nav.navigate(PcRoute.AgentView) { launchSingleTop = true } },
                    onOpenSettings = { nav.navigateToTopTab(PcRoute.Settings) },
                    onUnlockKeyring = { nav.navigateToTopTab(PcRoute.Settings) },
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PcTheme.colors.bg)
                        .statusBarsPadding(),
                ) {
                    RootScreen(
                        viewModel = rootViewModel,
                        initialRequestId = route.requestId,
                        onOpenSettings = { nav.navigateToTopTab(PcRoute.Settings) },
                    )
                }
            },
            settings = { nav ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PcTheme.colors.bg)
                        .statusBarsPadding(),
                ) {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onOpenAgentSettings = { nav.navigate(PcRoute.AgentSettings) { launchSingleTop = true } },
                        onOpenAgentView = { nav.navigate(PcRoute.AgentView) { launchSingleTop = true } },
                    )
                }
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PcTheme.colors.bg)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
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
                }
            },
            usageAccountDetail = { nav, route ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PcTheme.colors.bg)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    UsageAccountDetailScreen(
                        viewModel = agentSettingsViewModel,
                        accountId = route.accountId,
                        initialWindow = UsageWindowKey.fromWire(route.initialWindow),
                        onBack = { nav.popBackStack() },
                    )
                }
            },
            agentView = { nav ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PcTheme.colors.bg)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    AgentViewScreen(
                        viewModel = agentViewViewModel,
                        onBack = { nav.popBackStack() },
                        onOpenRule = { url, ruleId ->
                            nav.navigate(PcRoute.AgentRule(ruleId = ruleId, url = url))
                        },
                    )
                }
            },
            agentRule = { nav, route ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PcTheme.colors.bg)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    AgentRuleScreen(
                        viewModel = agentViewViewModel,
                        url = route.url,
                        ruleId = route.ruleId,
                        onBack = { nav.popBackStack() },
                    )
                }
            },
        )
    }

    val showBottomBar = !isInPictureInPictureMode && PcBottomTab.entries.any { tab ->
        currentDestination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
    }

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
            if (showBottomBar) {
                PcBottomNavBar(
                    tabs = PcBottomTab.entries,
                    isSelected = { tab ->
                        currentDestination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
                    },
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
        AppNavHost(
            navController = navController,
            screens = screens,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
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
        enterTransition = { fadeIn(animationSpec = tween(durationMillis = 150)) },
        exitTransition = { fadeOut(animationSpec = tween(durationMillis = 150)) },
        popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = 150)) },
        popExitTransition = { fadeOut(animationSpec = tween(durationMillis = 150)) },
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
    }
}

/**
 * Material 3 Expressive 5-tab bottom bar matching docs/DESIGN.md:
 * - True black (or light bg) fill with hairline top divider
 * - Active tab: 64x32dp mint-soft pill (`accentSoft`) with mint icon (`accent`) and crisp text label
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = isSelected(tab)
                val badge = badgeFor(tab)
                val interactionSource = remember { MutableInteractionSource() }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics { this.selected = selected }
                        .clickable(
                            role = Role.Tab,
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelect(tab) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .width(64.dp)
                                .height(32.dp)
                                .clip(CircleShape)
                                .background(if (selected) colors.accentSoft else Color.Transparent)
                                .indication(
                                    interactionSource = interactionSource,
                                    indication = ripple(),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = if (selected) colors.accent else colors.textFaint,
                                modifier = Modifier.size(24.dp),
                            )
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
                                    color = colors.bg,
                                    style = TabularNumStyle.copy(
                                        fontSize = 10.sp,
                                        lineHeight = 12.sp,
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
                        color = if (selected) colors.text else colors.textFaint,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
