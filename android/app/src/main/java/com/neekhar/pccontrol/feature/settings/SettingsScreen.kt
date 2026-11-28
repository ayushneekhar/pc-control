package com.neekhar.pccontrol.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.neekhar.pccontrol.core.platform.ApkUpdateState
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.MonoTextStyle
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcBottomSheet
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcConfirmDialog
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcScaffold
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcStatusDot
import com.neekhar.pccontrol.core.ui.PcSwitch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenAgentSettings: () -> Unit = {},
    onOpenAgentView: () -> Unit = {},
    onOpenConnectionSettings: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalPcColors.current
    var showForgetConfirm by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showInlineConnectionScreen by remember { mutableStateOf(false) }

    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenResume()
        onPauseOrDispose {}
    }

    if (showInlineConnectionScreen && onOpenConnectionSettings == null) {
        BackHandler { showInlineConnectionScreen = false }
        ConnectionSettingsScreen(
            viewModel = viewModel,
            onBack = { showInlineConnectionScreen = false },
        )
        return
    }

    val openConnection = {
        if (onOpenConnectionSettings != null) {
            onOpenConnectionSettings()
        } else {
            showInlineConnectionScreen = true
        }
    }

    val listState = rememberLazyListState()

    PcScaffold(
        title = "Settings",
        listState = listState,
        applyNavigationBarPadding = false,
    ) { innerPadding ->
        val topPad = innerPadding.calculateTopPadding()
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refreshAll,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing,
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
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = topPad, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // ---- Agent ----
                item(key = "section_agent") {
                    val agentSub = state.agentSummary?.let { a ->
                        val name = if (a.engine == "gemini") "Gemini" else "Claude"
                        val active = if (a.engine == "gemini") a.geminiActive else a.claudeActive
                        when {
                            !a.available -> "Unavailable"
                            a.switching -> "$name · Switching…"
                            else -> "$name · ${if (active) "Running" else "Stopped"}"
                        }
                    } ?: "Harness, models & AI usage"

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PcSectionLabel(label = "Agent")
                        PcListCard {
                            PcListRow(
                                icon = Icons.Rounded.SmartToy,
                                title = "Agent settings",
                                subtitle = agentSub,
                                showDivider = true,
                                onClick = onOpenAgentSettings,
                            )
                            PcListRow(
                                icon = Icons.Rounded.Visibility,
                                title = "Agent view",
                                subtitle = "Capture proxy & website rewrite rules",
                                showDivider = false,
                                onClick = onOpenAgentView,
                            )
                        }
                    }
                }

                // ---- PC Controls (single grouped PcListCard) ----
                item(key = "section_pc_controls") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PcSectionLabel(label = "PC controls")
                        PcListCard {
                            VpnListRow(
                                state = state.vpnState,
                                pending = state.vpnPending,
                                busy = state.vpnBusy,
                                error = state.vpnError,
                                onToggle = viewModel::toggleVpn,
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp),
                                color = colors.hairline,
                                thickness = 0.5.dp,
                            )
                            KeyboardLockListRow(
                                state = state.kbdLockState,
                                pending = state.kbdLockPending,
                                busy = state.kbdLockBusy,
                                error = state.kbdLockError,
                                onToggle = viewModel::toggleKbdLock,
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp),
                                color = colors.hairline,
                                thickness = 0.5.dp,
                            )
                            KeyringListRow(
                                status = state.keyringStatus,
                                vaultSupported = state.keyringVaultSupported,
                                hasSaved = state.keyringHasSaved,
                                busy = state.keyringBusy,
                                error = state.keyringError,
                                notice = state.keyringNotice,
                                onUnlock = viewModel::unlockKeyringWithBiometricsOrPrompt,
                                onSavePassword = viewModel::openKeyringSaveSheet,
                                onForget = { showForgetConfirm = true },
                                onDismissNotice = viewModel::clearKeyringFeedback,
                            )
                        }
                    }
                }

                // ---- Notifications (compact 2-column check table) ----
                item(key = "section_notifications") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PcSectionLabel(label = "Notifications")
                        NotificationRoutingCard(
                            settings = state.notifSettings,
                            expanded = state.notifExpanded,
                            error = state.notifError,
                            onToggleExpand = viewModel::toggleNotifExpanded,
                            onToggleMaster = viewModel::setNotifMaster,
                            onToggleCategory = viewModel::setNotifCategory,
                        )
                    }
                }

                // ---- App (Connection, Theme, Updates) ----
                item(key = "section_app") {
                    val hostSummary = remember(state.serverInfo, state.savedServerUrl) {
                        connectionHostSummary(state.serverInfo, state.savedServerUrl)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PcSectionLabel(label = "App")
                        PcListCard {
                            PcListRow(
                                icon = Icons.Rounded.Dns,
                                title = "Connection",
                                subtitle = if (!state.connectionError.isNullOrBlank()) {
                                    "Offline · ${state.activeTransport.label}"
                                } else {
                                    state.activeTransport.label
                                },
                                trailing = hostSummary,
                                showChevron = true,
                                showDivider = true,
                                onClick = openConnection,
                            )
                            PcListRow(
                                icon = Icons.Rounded.DarkMode,
                                title = "Theme",
                                trailing = state.themeMode.label,
                                showChevron = true,
                                showDivider = true,
                                onClick = { showThemeSheet = true },
                            )
                            AppUpdatesListRow(
                                currentVersionName = state.currentVersionName,
                                currentVersionCode = state.currentVersionCode,
                                latestRelease = state.latestRelease,
                                hasNewer = state.hasNewerApk,
                                checking = state.updateChecking,
                                error = state.updateError,
                                apkUpdateState = state.apkUpdateState,
                                onCheck = viewModel::checkForApkUpdate,
                                onDownloadInstall = viewModel::installLatestApk,
                            )
                        }
                    }
                }

                // ---- Diagnostics ----
                item(key = "section_diagnostics") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PcSectionLabel(label = "Diagnostics")
                        DiagnosticsListCard(
                            fcm = state.fcmDiagnostics,
                            serverRegistered = state.fcmServerRegistered,
                            checking = state.fcmChecking,
                            crashCount = state.crashReports.size,
                            onRetryPush = viewModel::retryPushRegistration,
                            onOpenNotifSettings = viewModel::openNotificationSettings,
                            onOpenCrashViewer = { viewModel.setCrashViewerVisible(true) },
                        )
                    }
                }
            }
        }
    }

    if (showThemeSheet) {
        ThemePickerSheet(
            selected = state.themeMode,
            onSelect = { mode ->
                viewModel.setThemeMode(mode)
                showThemeSheet = false
            },
            onDismiss = { showThemeSheet = false },
        )
    }

    if (state.showKeyringSheet) {
        KeyringUnlockSheet(
            mode = state.keyringSheetMode,
            canSaveWithBiometrics = state.keyringVaultSupported,
            busy = state.keyringBusy,
            error = state.keyringError,
            onUnlock = viewModel::submitKeyringManualUnlock,
            onSavePassword = viewModel::saveKeyringPassword,
            onDismiss = viewModel::closeKeyringSheet,
        )
    }

    if (showForgetConfirm) {
        PcConfirmDialog(
            title = "Forget keyring password?",
            message = "Removes the encrypted password and its Keystore key from this phone. You can still unlock by typing it manually.",
            confirmLabel = "Forget",
            dismissLabel = "Cancel",
            confirmVariant = PcButtonVariant.Danger,
            onConfirm = {
                showForgetConfirm = false
                viewModel.forgetKeyringPassword()
            },
            onDismiss = { showForgetConfirm = false },
        )
    }

    if (state.showCrashViewer) {
        PcBottomSheet(
            onDismissRequest = { viewModel.setCrashViewerVisible(false) },
            contentPadding = PaddingValues(0.dp),
        ) {
            CrashReportsSheetContent(
                reports = state.crashReports,
                onClear = viewModel::clearCrashReports,
                onClose = { viewModel.setCrashViewerVisible(false) },
            )
        }
    }
}

@Composable
private fun VpnListRow(
    state: VpnState?,
    pending: Boolean?,
    busy: Boolean,
    error: String?,
    onToggle: (Boolean) -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val enabled = pending ?: state?.enabled ?: false
    val down = enabled && state != null && !state.tunnelUp
    val noPort = enabled && state != null && state.tunnelUp && state.forwardedPort == null
    val statusText = when {
        busy -> if (enabled) "Turning on…" else "Turning off…"
        !enabled -> "Off"
        down -> "Tunnel down"
        noPort -> "Connected · no forwarded port"
        else -> buildList {
            add("Proton VPN")
            state?.forwardedPort?.let { add(":$it") }
            state?.publicIp?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
    }
    val dotColor = when {
        down || noPort -> colors.warn
        enabled -> colors.ok
        else -> colors.textFaint
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = if (down || noPort) colors.warn else colors.textDim,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Torrent VPN",
                    color = colors.text,
                    style = type.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PcStatusDot(color = dotColor, size = 6.dp)
                    Text(
                        text = statusText,
                        color = colors.textFaint,
                        style = type.caption.tabular(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PcSwitch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = state != null && !busy,
            )
        }

        val lastError = state?.lastError
        if (!error.isNullOrBlank()) {
            PcErrorBanner(message = error)
        } else if (!lastError.isNullOrBlank()) {
            Text(
                text = lastError,
                color = if (down) colors.warn else colors.textFaint,
                style = type.caption,
                modifier = Modifier.padding(start = 34.dp),
            )
        }
    }
}

@Composable
private fun KeyboardLockListRow(
    state: KbdLockState?,
    pending: Boolean?,
    busy: Boolean,
    error: String?,
    onToggle: (Boolean) -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val enabled = pending ?: state?.enabled ?: false
    val unavailable = state != null && !state.available
    val statusText = when {
        busy -> if (enabled) "Locking…" else "Unlocking…"
        unavailable -> "Unavailable"
        state == null -> "Checking…"
        enabled -> "Locked"
        else -> "Unlocked"
    }
    val dotColor = when {
        busy -> colors.warn
        unavailable || state == null -> colors.textFaint
        enabled -> colors.ok
        else -> colors.textFaint
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Keyboard,
                contentDescription = null,
                tint = colors.textDim,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Keyboard lock",
                    color = colors.text,
                    style = type.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PcStatusDot(color = dotColor, size = 6.dp)
                    Text(
                        text = statusText,
                        color = colors.textFaint,
                        style = type.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PcSwitch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = state != null && !unavailable && !busy,
            )
        }

        if (!error.isNullOrBlank()) {
            PcErrorBanner(message = error)
        }
    }
}

@Composable
private fun KeyringListRow(
    status: KeyringStatus?,
    vaultSupported: Boolean,
    hasSaved: Boolean,
    busy: Boolean,
    error: String?,
    notice: String?,
    onUnlock: () -> Unit,
    onSavePassword: () -> Unit,
    onForget: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val locked = status?.locked == true
    val dotColor = when {
        status == null -> colors.textFaint
        locked -> colors.warn
        else -> colors.ok
    }
    val statusLine = remember(status, hasSaved) { keyringStatusSummary(status, hasSaved) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = if (locked) Icons.Rounded.Lock else Icons.Rounded.Fingerprint,
                contentDescription = null,
                tint = if (locked) colors.warn else colors.textDim,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Keyring",
                    color = colors.text,
                    style = type.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PcStatusDot(color = dotColor, size = 6.dp)
                    Text(
                        text = statusLine,
                        color = colors.textFaint,
                        style = type.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (locked) {
                PcButton(
                    label = "Unlock",
                    variant = PcButtonVariant.Primary,
                    compact = true,
                    busy = busy,
                    onClick = onUnlock,
                )
            } else if (vaultSupported && !hasSaved) {
                PcButton(
                    label = "Save",
                    variant = PcButtonVariant.Subtle,
                    compact = true,
                    enabled = !busy,
                    onClick = onSavePassword,
                )
            }
        }

        if (!error.isNullOrBlank()) {
            PcErrorBanner(message = error)
        }

        if (!notice.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = notice,
                    color = colors.textDim,
                    style = type.caption,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Dismiss",
                    color = colors.accent,
                    style = type.caption.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismissNotice)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        if (vaultSupported && (hasSaved || locked)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 34.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PcButton(
                    label = if (hasSaved) "Change password" else "Save password",
                    variant = PcButtonVariant.Subtle,
                    compact = true,
                    enabled = !busy,
                    onClick = onSavePassword,
                )
                if (hasSaved) {
                    PcButton(
                        label = "Forget",
                        variant = PcButtonVariant.Danger,
                        compact = true,
                        enabled = !busy,
                        onClick = onForget,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationRoutingCard(
    settings: NotifSettings?,
    expanded: Boolean,
    error: String?,
    onToggleExpand: () -> Unit,
    onToggleMaster: (channel: String, enabled: Boolean) -> Unit,
    onToggleCategory: (categoryId: String, channel: String, enabled: Boolean) -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val summary = remember(settings) { notifRoutingSummary(settings) }

    PcListCard(modifier = Modifier.animateContentSize()) {
        // Expandable header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Notifications,
                contentDescription = null,
                tint = colors.textDim,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = "Where notifications go",
                    color = colors.text,
                    style = type.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    color = colors.textFaint,
                    style = type.caption.tabular(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = colors.textFaint,
                modifier = Modifier.size(20.dp),
            )
        }

        if (!error.isNullOrBlank()) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                PcErrorBanner(message = error)
            }
        }

        if (settings != null) {
            val tgMaster = settings.masters["telegram"] == true
            val appMaster = settings.masters["app"] == true

            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = colors.hairline,
                thickness = 0.5.dp,
            )

            // Column headers + master row aligned to the same 2-column grid
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Channel",
                    color = colors.textFaint,
                    style = type.micro,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (settings.channels.telegram.configured) "Telegram" else "Telegram · off",
                    color = colors.textFaint,
                    style = type.micro,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(68.dp),
                )
                Text(
                    text = "App",
                    color = colors.textFaint,
                    style = type.micro,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(52.dp),
                )
            }

            // Master row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "All notifications",
                    color = colors.text,
                    style = type.body.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.width(68.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CompactCheckToggle(
                        checked = tgMaster,
                        dimmed = !settings.channels.telegram.configured,
                        contentDescription = "Telegram notifications",
                        onToggle = { onToggleMaster("telegram", !tgMaster) },
                    )
                }
                Box(
                    modifier = Modifier.width(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CompactCheckToggle(
                        checked = appMaster,
                        dimmed = !settings.channels.app.configured,
                        contentDescription = "App notifications",
                        onToggle = { onToggleMaster("app", !appMaster) },
                    )
                }
            }

            // Per-category compact 2-column check table
            if (expanded && settings.categories.isNotEmpty()) {
                settings.categories.forEach { cat ->
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = colors.hairline,
                        thickness = 0.5.dp,
                    )
                    val supportsTelegram = cat.channels == null || "telegram" in cat.channels
                    val anyActive = (supportsTelegram && cat.telegram && tgMaster) || (cat.app && appMaster)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 42.dp)
                            .padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = cat.label,
                            color = if (anyActive) colors.text else colors.textDim,
                            style = type.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier.width(68.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (supportsTelegram) {
                                CompactCheckToggle(
                                    checked = cat.telegram,
                                    dimmed = !tgMaster,
                                    contentDescription = "${cat.label} Telegram",
                                    onToggle = { onToggleCategory(cat.id, "telegram", !cat.telegram) },
                                )
                            } else {
                                Text(
                                    text = "—",
                                    color = colors.textFaint,
                                    style = type.caption,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier.width(52.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CompactCheckToggle(
                                checked = cat.app,
                                dimmed = !appMaster,
                                contentDescription = "${cat.label} App",
                                onToggle = { onToggleCategory(cat.id, "app", !cat.app) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactCheckToggle(
    checked: Boolean,
    dimmed: Boolean,
    contentDescription: String,
    onToggle: () -> Unit,
) {
    val colors = LocalPcColors.current
    val shape = RoundedCornerShape(6.dp)
    val boxBg = when {
        checked && !dimmed -> colors.accentSoft
        checked -> colors.surfaceRaised
        else -> Color.Transparent
    }
    val boxBorder = when {
        checked && !dimmed -> colors.accent.copy(alpha = 0.5f)
        checked -> colors.hairline
        else -> colors.hairline
    }
    val iconTint = if (dimmed) colors.textFaint else colors.accent

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Checkbox, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(shape)
                .background(boxBg)
                .border(width = 1.dp, color = boxBorder, shape = shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
private fun AppUpdatesListRow(
    currentVersionName: String,
    currentVersionCode: Int,
    latestRelease: ApkReleaseInfo?,
    hasNewer: Boolean,
    checking: Boolean,
    error: String?,
    apkUpdateState: ApkUpdateState,
    onCheck: () -> Unit,
    onDownloadInstall: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current

    val isDownloading = apkUpdateState is ApkUpdateState.Downloading
    val isVerifying = apkUpdateState is ApkUpdateState.Verifying
    val isReady = apkUpdateState is ApkUpdateState.ReadyToInstall
    val isApkError = apkUpdateState is ApkUpdateState.Error
    val isChecking = checking || apkUpdateState is ApkUpdateState.Checking
    val isBusy = isDownloading || isVerifying || isChecking

    val subtitle = when (apkUpdateState) {
        is ApkUpdateState.Downloading -> formatApkDownloadSubtitle(apkUpdateState)
        is ApkUpdateState.Verifying -> "Verifying SHA-256…"
        is ApkUpdateState.ReadyToInstall -> formatApkReadySubtitle(apkUpdateState.release)
        is ApkUpdateState.Error -> apkUpdateState.message.ifBlank { "APK update failed." }
        is ApkUpdateState.Checking -> "Checking GitHub Releases…"
        else -> when {
            hasNewer && latestRelease != null -> "v$currentVersionName → v${latestRelease.versionName} available"
            checking -> "Checking GitHub Releases…"
            !error.isNullOrBlank() -> error
            latestRelease != null -> "v$currentVersionName · Up to date"
            else -> "v$currentVersionName ($currentVersionCode)"
        }
    }
    val subtitleColor = when {
        isApkError -> colors.bad
        isDownloading || isVerifying || isReady -> colors.accent
        hasNewer -> colors.accent
        !error.isNullOrBlank() -> colors.bad
        else -> colors.textFaint
    }

    val rowClick: (() -> Unit)? = when {
        isBusy -> null
        isReady || isApkError || hasNewer -> onDownloadInstall
        else -> onCheck
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        PcListRow(
            icon = Icons.Rounded.SystemUpdate,
            title = "App updates",
            subtitleContent = {
                Text(
                    text = subtitle,
                    color = subtitleColor,
                    style = type.caption.tabular(),
                    maxLines = if (isApkError) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                when {
                    isBusy -> CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 2.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                    isApkError -> PcButton(
                        label = "Retry",
                        variant = PcButtonVariant.Subtle,
                        compact = true,
                        onClick = onDownloadInstall,
                    )
                    isReady || hasNewer -> PcButton(
                        label = "Install",
                        variant = PcButtonVariant.Primary,
                        compact = true,
                        onClick = onDownloadInstall,
                    )
                    else -> Text(
                        text = "Check",
                        color = colors.accent,
                        style = type.body.copy(fontWeight = FontWeight.Medium),
                        modifier = Modifier.padding(end = 2.dp),
                    )
                }
            },
            showChevron = false,
            showDivider = false,
            onClick = rowClick,
        )

        if (apkUpdateState is ApkUpdateState.Downloading) {
            val barModifier = Modifier
                .fillMaxWidth()
                .padding(start = 52.dp, end = 16.dp, bottom = 12.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
            if (apkUpdateState.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { apkUpdateState.progress.coerceIn(0f, 1f) },
                    modifier = barModifier,
                    color = colors.accent,
                    trackColor = colors.surfaceRaised,
                    strokeCap = StrokeCap.Round,
                )
            } else {
                LinearProgressIndicator(
                    modifier = barModifier,
                    color = colors.accent,
                    trackColor = colors.surfaceRaised,
                    strokeCap = StrokeCap.Round,
                )
            }
        }

        val releaseNotes = latestRelease?.releaseNotes
        if (hasNewer && !releaseNotes.isNullOrBlank()) {
            Text(
                text = releaseNotes,
                color = colors.textDim,
                style = type.caption,
                modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun DiagnosticsListCard(
    fcm: FcmDiagnostics,
    serverRegistered: Boolean?,
    checking: Boolean,
    crashCount: Int,
    onRetryPush: () -> Unit,
    onOpenNotifSettings: () -> Unit,
    onOpenCrashViewer: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val fcmOk = fcm.notificationsEnabled && !fcm.token.isNullOrBlank() && serverRegistered == true
    val fcmStatus = when {
        !fcm.notificationsEnabled -> "Notifications off"
        checking && !fcmOk -> "Checking…"
        fcm.token.isNullOrBlank() -> fcm.error ?: "No FCM token"
        serverRegistered == true -> "Connected · registered"
        serverRegistered == false -> "Not registered on laptop"
        else -> "Token present"
    }
    val fcmDotColor = when {
        fcmOk -> colors.ok
        checking -> colors.textFaint
        else -> colors.warn
    }

    PcListCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.NotificationsActive,
                contentDescription = null,
                tint = colors.textDim,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Push notifications",
                    color = colors.text,
                    style = type.cardTitle,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PcStatusDot(color = fcmDotColor, size = 6.dp)
                    Text(
                        text = fcmStatus,
                        color = colors.textFaint,
                        style = type.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!fcm.notificationsEnabled) {
                PcButton(
                    label = "Allow",
                    variant = PcButtonVariant.Subtle,
                    compact = true,
                    onClick = onOpenNotifSettings,
                )
            } else if (checking && !fcmOk) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 2.dp)
                        .size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.accent,
                )
            } else if (!fcmOk) {
                PcButton(
                    label = "Retry",
                    variant = PcButtonVariant.Subtle,
                    compact = true,
                    onClick = onRetryPush,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 52.dp),
            color = colors.hairline,
            thickness = 0.5.dp,
        )
        PcListRow(
            icon = Icons.Rounded.BugReport,
            title = "Crash reports",
            trailing = if (crashCount == 0) "None" else "$crashCount",
            showChevron = true,
            showDivider = false,
            onClick = onOpenCrashViewer,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemePickerSheet(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current

    PcBottomSheet(
        onDismissRequest = onDismiss,
        title = "Theme",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceRaised),
        ) {
            ThemeMode.entries.forEachIndexed { idx, mode ->
                if (idx > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = colors.hairline,
                        thickness = 0.5.dp,
                    )
                }
                val isSelected = mode == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mode) }
                        .defaultMinSize(minHeight = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = mode.label,
                        color = colors.text,
                        style = type.cardTitle.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Selected",
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashReportsSheetContent(
    reports: List<CrashReportEntry>,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Crash reports",
                color = colors.text,
                style = type.collapsedTitle,
            )
            if (reports.isNotEmpty()) {
                PcButton(
                    label = "Clear all",
                    variant = PcButtonVariant.Danger,
                    compact = true,
                    onClick = onClear,
                )
            }
        }

        if (reports.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceRaised)
                    .padding(16.dp),
            ) {
                Text(
                    text = "No crash reports stored on this device.",
                    color = colors.textDim,
                    style = type.body,
                )
            }
        } else {
            reports.forEach { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceRaised)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = entry.summary,
                        color = colors.text,
                        style = type.cardTitle.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = entry.timestamp,
                        color = colors.textFaint,
                        style = type.caption.tabular(),
                    )
                    Text(
                        text = entry.stackTrace.take(1500),
                        color = colors.textDim,
                        style = MonoTextStyle,
                    )
                }
            }
        }

        PcButton(
            label = "Close",
            variant = PcButtonVariant.Subtle,
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
