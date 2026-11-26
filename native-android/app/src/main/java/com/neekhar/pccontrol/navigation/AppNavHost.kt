package com.neekhar.pccontrol.navigation

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.PcTheme
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcConfirmDialog
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcScreenHeader
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcTone
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
 * Pluggable screen content registry so feature packages can be wired into [AppNavHost]
 * cleanly during integration while keeping the foundation shell self-contained.
 */
class PcScreenRegistry(
    val home: @Composable (NavHostController) -> Unit = { nav -> StubHomeScreen(nav) },
    val system: @Composable (NavHostController, PcRoute.System) -> Unit = { _, r ->
        StubScreen(title = "System", status = r.initialTab?.let { "Tab · $it" } ?: "Vitals & Services", icon = Icons.Filled.Dashboard)
    },
    val vitals: @Composable (NavHostController) -> Unit = { nav ->
        StubScreen(title = "Vitals", status = "Live · every 2 s", icon = Icons.Filled.Dashboard, onBack = { nav.popBackStack() })
    },
    val services: @Composable (NavHostController) -> Unit = { nav ->
        StubScreen(title = "Services", status = "systemd units", icon = Icons.Filled.Dashboard, onBack = { nav.popBackStack() })
    },
    val serviceDetail: @Composable (NavHostController, PcRoute.ServiceDetail) -> Unit = { nav, r ->
        StubScreen(title = r.unit, status = "Service detail", icon = Icons.Filled.Dashboard, onBack = { nav.popBackStack() })
    },
    val chat: @Composable (NavHostController) -> Unit = { _ ->
        StubScreen(title = "Chat", status = "Agent chat", icon = Icons.Filled.ChatBubble)
    },
    val root: @Composable (NavHostController, PcRoute.Root) -> Unit = { _, r ->
        StubScreen(
            title = "Root",
            status = r.requestId?.let { "Request · $it" } ?: "Pending approvals",
            icon = Icons.Filled.Security,
        )
    },
    val settings: @Composable (NavHostController) -> Unit = { nav ->
        StubSettingsScreen(nav)
    },
    val cams: @Composable (NavHostController, PcRoute.Cams) -> Unit = { nav, r ->
        StubScreen(
            title = "Cameras",
            status = buildString {
                append(r.initialTab ?: "live")
                if (!r.sharedYoutubeUrl.isNullOrBlank()) append(" · shared link")
            },
            icon = Icons.Filled.Videocam,
            onBack = { nav.popBackStack() },
        )
    },
    val seerr: @Composable (NavHostController) -> Unit = { nav ->
        StubScreen(title = "Seerr", status = "Movies & TV requests", icon = Icons.Filled.Movie, onBack = { nav.popBackStack() })
    },
    val seerrDetail: @Composable (NavHostController, PcRoute.SeerrDetail) -> Unit = { nav, r ->
        StubScreen(title = "${r.mediaType} #${r.id}", status = "Seerr detail", icon = Icons.Filled.Movie, onBack = { nav.popBackStack() })
    },
    val seerrPerson: @Composable (NavHostController, PcRoute.SeerrPerson) -> Unit = { nav, r ->
        StubScreen(title = "Person #${r.id}", status = "Seerr credits", icon = Icons.Filled.Movie, onBack = { nav.popBackStack() })
    },
    val space: @Composable (NavHostController) -> Unit = { nav ->
        StubScreen(title = "Space", status = "Media storage & Drive offload", icon = Icons.Filled.Storage, onBack = { nav.popBackStack() })
    },
    val downloads: @Composable (NavHostController) -> Unit = { nav ->
        StubScreen(title = "Downloads", status = "qBittorrent & *arr queues", icon = Icons.Filled.Download, onBack = { nav.popBackStack() })
    },
    val agentSettings: @Composable (NavHostController) -> Unit = { nav ->
        StubScreen(title = "Agent", status = "Models, usage & chat endpoint", icon = Icons.Filled.SmartToy, onBack = { nav.popBackStack() })
    },
    val usageAccountDetail: @Composable (NavHostController, PcRoute.UsageAccountDetail) -> Unit = { nav, r ->
        StubScreen(title = r.accountId, status = "Account usage", icon = Icons.Filled.SmartToy, onBack = { nav.popBackStack() })
    },
    val agentView: @Composable (NavHostController) -> Unit = { nav ->
        StubScreen(title = "Agent view", status = "Watcher rules & activity", icon = Icons.Filled.SmartToy, onBack = { nav.popBackStack() })
    },
    val agentRule: @Composable (NavHostController, PcRoute.AgentRule) -> Unit = { nav, r ->
        StubScreen(
            title = r.ruleId ?: "New rule",
            status = "Agent rule",
            icon = Icons.Filled.SmartToy,
            onBack = { nav.popBackStack() },
        )
    },
)

/**
 * Top-level application shell hosting the 5-tab bottom navigation bar, [AppNavHost],
 * deep link dispatch, and incoming `ACTION_SEND` share handling.
 */
@Composable
fun PcAppShell(
    pendingDeepLink: DeepLinkTarget?,
    onDeepLinkConsumed: () -> Unit,
    pendingShare: SharedContent?,
    onShareConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    rootPendingCount: Int = 0,
    onT3ThreadDeepLink: (DeepLinkTarget.T3Thread) -> Unit = {},
    screens: PcScreenRegistry = remember { PcScreenRegistry() },
    navController: NavHostController = rememberNavController(),
) {
    val colors = PcTheme.colors
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val showBottomBar = PcBottomTab.entries.any { tab ->
        currentDestination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
    }

    LaunchedEffect(pendingDeepLink) {
        val target = pendingDeepLink ?: return@LaunchedEffect
        when (target) {
            is DeepLinkTarget.Route -> navController.navigateToDeepLink(target.route)
            is DeepLinkTarget.T3Thread -> {
                onT3ThreadDeepLink(target)
                navController.navigateToDeepLink(PcRoute.Chat)
            }
        }
        onDeepLinkConsumed()
    }

    LaunchedEffect(pendingShare) {
        val share = pendingShare ?: return@LaunchedEffect
        if (share is SharedContent.Youtube) {
            navController.navigate(PcRoute.Cams(initialTab = "music", sharedYoutubeUrl = share.url)) {
                launchSingleTop = true
            }
            onShareConsumed()
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
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
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
                .padding(innerPadding),
        )
    }

    // Handle non-YouTube shared content (magnet, media URL, or unsupported text)
    when (val share = pendingShare) {
        is SharedContent.Magnet -> {
            PcConfirmDialog(
                title = "Add torrent",
                message = "Open Downloads to add \"${share.name}\" to qBittorrent?",
                confirmLabel = "Open Downloads",
                onConfirm = {
                    onShareConsumed()
                    navController.navigate(PcRoute.Downloads) { launchSingleTop = true }
                },
                onDismiss = onShareConsumed,
            )
        }
        is SharedContent.MediaUrl -> {
            PcConfirmDialog(
                title = "Request media",
                message = "Open Seerr to resolve ${share.url}?",
                confirmLabel = "Open Seerr",
                onConfirm = {
                    onShareConsumed()
                    navController.navigate(PcRoute.Seerr) { launchSingleTop = true }
                },
                onDismiss = onShareConsumed,
            )
        }
        is SharedContent.Unsupported -> {
            PcConfirmDialog(
                title = "Unsupported link",
                message = "Share a YouTube video, magnet link, or IMDb/TMDB/Letterboxd link.",
                confirmLabel = "OK",
                dismissLabel = "Close",
                confirmVariant = PcButtonVariant.Subtle,
                onConfirm = onShareConsumed,
                onDismiss = onShareConsumed,
            )
        }
        is SharedContent.Youtube, null -> Unit
    }
}

private fun NavHostController.navigateToDeepLink(route: PcRoute) {
    val isTopTab = route is PcRoute.Home ||
        route is PcRoute.System ||
        route is PcRoute.Chat ||
        route is PcRoute.Root ||
        route is PcRoute.Settings

    if (isTopTab) {
        navigate(route) {
            popUpTo(graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = route !is PcRoute.System && route !is PcRoute.Root
        }
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
                                    indication = ripple(color = colors.ripple),
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

@Composable
private fun StubHomeScreen(navController: NavHostController) {
    val colors = PcTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        PcScreenHeader(
            title = "Home",
            statusText = "Foundation shell ready",
            statusTone = PcTone.Ok,
            showStatusDot = true,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PcDimens.ScreenPad),
            verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
        ) {
            PcSectionLabel(label = "Routes")
            PcListCard {
                PcListRow(
                    title = "Cameras",
                    subtitle = "Live · Replay · Music",
                    icon = Icons.Filled.Videocam,
                    onClick = { navController.navigate(PcRoute.Cams()) },
                )
                PcListRow(
                    title = "Downloads",
                    subtitle = "qBittorrent & *arr queues",
                    icon = Icons.Filled.Download,
                    onClick = { navController.navigate(PcRoute.Downloads) },
                )
                PcListRow(
                    title = "Seerr",
                    subtitle = "Search & request movies or TV",
                    icon = Icons.Filled.Movie,
                    onClick = { navController.navigate(PcRoute.Seerr) },
                )
                PcListRow(
                    title = "Space",
                    subtitle = "Media storage & Drive offload",
                    icon = Icons.Filled.Storage,
                    onClick = { navController.navigate(PcRoute.Space) },
                )
                PcListRow(
                    title = "Agent view",
                    subtitle = "Watcher rules & activity",
                    icon = Icons.Filled.SmartToy,
                    showDivider = false,
                    onClick = { navController.navigate(PcRoute.AgentView) },
                )
            }
            Spacer(modifier = Modifier.height(PcDimens.Space4))
        }
    }
}

@Composable
private fun StubSettingsScreen(navController: NavHostController) {
    val colors = PcTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        PcScreenHeader(
            title = "Settings",
            statusText = "App configuration",
            statusTone = PcTone.Dim,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PcDimens.ScreenPad),
            verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
        ) {
            PcSectionLabel(label = "Agent")
            PcListCard {
                PcListRow(
                    title = "Agent settings",
                    subtitle = "Models, usage & chat endpoint",
                    icon = Icons.Filled.SmartToy,
                    showDivider = false,
                    onClick = { navController.navigate(PcRoute.AgentSettings) },
                )
            }
        }
    }
}

@Composable
private fun StubScreen(
    title: String,
    status: String,
    icon: ImageVector,
    onBack: (() -> Unit)? = null,
) {
    val colors = PcTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .let { if (onBack != null) it.navigationBarsPadding() else it },
    ) {
        PcScreenHeader(
            title = title,
            statusText = status,
            statusTone = PcTone.Dim,
            onBack = onBack,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PcDimens.ScreenPad),
            contentAlignment = Alignment.Center,
        ) {
            PcEmptyState(
                icon = icon,
                title = title,
                body = "Placeholder screen in the native foundation shell. Feature package will be wired here.",
            )
        }
    }
}
