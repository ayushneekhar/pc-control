package com.neekhar.pccontrol.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcCompactTextField
import com.neekhar.pccontrol.core.ui.PcConfirmDialog
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcScreenHeader
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcSegmentedControl
import com.neekhar.pccontrol.core.ui.PcSheetDragHandle
import com.neekhar.pccontrol.core.ui.PcStatusDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenAgentSettings: () -> Unit = {},
    onOpenAgentView: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalPcColors.current
    var showForgetConfirm by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refreshAll,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                PcScreenHeader(title = "Settings")
            }

            // ---- Agent links ----
            item(key = "sec_agent") {
                PcSectionLabel(label = "Agent")
            }
            item(key = "card_agent_links") {
                val agentSub = state.agentSummary?.let { a ->
                    val name = if (a.engine == "gemini") "Gemini" else "Claude"
                    val active = if (a.engine == "gemini") a.geminiActive else a.claudeActive
                    when {
                        !a.available -> "Unavailable"
                        a.switching -> "$name · Switching…"
                        else -> "$name · ${if (active) "Running" else "Stopped"}"
                    }
                } ?: "Harness, models & AI usage"

                PcListCard {
                    PcListRow(
                        icon = Icons.Filled.SmartToy,
                        title = "Agent settings",
                        subtitle = agentSub,
                        showDivider = true,
                        onClick = onOpenAgentSettings,
                    )
                    PcListRow(
                        icon = Icons.Filled.Visibility,
                        title = "Agent view",
                        subtitle = "Capture proxy & website rewrite rules",
                        showDivider = false,
                        onClick = onOpenAgentView,
                    )
                }
            }

            // ---- PC Controls ----
            item(key = "sec_pc_controls") {
                Spacer(modifier = Modifier.height(4.dp))
                PcSectionLabel(label = "PC controls")
            }
            item(key = "card_vpn") {
                VpnCard(
                    state = state.vpnState,
                    pending = state.vpnPending,
                    busy = state.vpnBusy,
                    error = state.vpnError,
                    onToggle = viewModel::toggleVpn,
                )
            }
            item(key = "card_kbd_lock") {
                KeyboardLockCard(
                    state = state.kbdLockState,
                    pending = state.kbdLockPending,
                    busy = state.kbdLockBusy,
                    error = state.kbdLockError,
                    onToggle = viewModel::toggleKbdLock,
                )
            }
            item(key = "card_keyring") {
                KeyringCard(
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

            // ---- Notifications ----
            item(key = "sec_notifications") {
                Spacer(modifier = Modifier.height(4.dp))
                PcSectionLabel(label = "Notifications")
            }
            item(key = "card_notifications") {
                NotificationsCard(
                    settings = state.notifSettings,
                    expanded = state.notifExpanded,
                    error = state.notifError,
                    onToggleExpand = viewModel::toggleNotifExpanded,
                    onToggleMaster = viewModel::setNotifMaster,
                    onToggleCategory = viewModel::setNotifCategory,
                )
            }

            // ---- Appearance ----
            item(key = "sec_appearance") {
                Spacer(modifier = Modifier.height(4.dp))
                PcSectionLabel(label = "Appearance")
            }
            item(key = "card_appearance") {
                PcSegmentedControl(
                    options = ThemeMode.entries.map { it.label },
                    selectedIndex = ThemeMode.entries.indexOf(state.themeMode),
                    onSelect = { idx -> viewModel.setThemeMode(ThemeMode.entries[idx]) },
                )
            }

            // ---- Connection ----
            item(key = "sec_connection") {
                Spacer(modifier = Modifier.height(4.dp))
                PcSectionLabel(label = "Connection")
            }
            item(key = "card_connection") {
                ConnectionCard(
                    serverUrl = state.serverUrlInput,
                    chatBaseUrl = state.chatBaseUrlInput,
                    chatToken = state.chatTokenInput,
                    dirty = state.connectionDirty,
                    testing = state.connectionTesting,
                    info = state.serverInfo,
                    whoAmI = state.whoAmI,
                    error = state.connectionError,
                    onServerUrlChange = viewModel::onServerUrlChange,
                    onChatBaseUrlChange = viewModel::onChatBaseUrlChange,
                    onChatTokenChange = viewModel::onChatTokenChange,
                    onSave = viewModel::saveConnection,
                    onTest = viewModel::testConnection,
                )
            }

            // ---- App Updates (Native GitHub Releases APK) ----
            item(key = "sec_updates") {
                Spacer(modifier = Modifier.height(4.dp))
                PcSectionLabel(label = "App updates")
            }
            item(key = "card_updates") {
                AppUpdatesCard(
                    currentVersionName = state.currentVersionName,
                    currentVersionCode = state.currentVersionCode,
                    latestRelease = state.latestRelease,
                    hasNewer = state.hasNewerApk,
                    checking = state.updateChecking,
                    error = state.updateError,
                    onCheck = viewModel::checkForApkUpdate,
                    onDownloadInstall = viewModel::installLatestApk,
                )
            }

            // ---- Diagnostics ----
            item(key = "sec_diagnostics") {
                Spacer(modifier = Modifier.height(4.dp))
                PcSectionLabel(label = "Diagnostics")
            }
            item(key = "card_diagnostics") {
                DiagnosticsCard(
                    fcm = state.fcmDiagnostics,
                    serverRegistered = state.fcmServerRegistered,
                    crashCount = state.crashReports.size,
                    onRetryPush = viewModel::retryPushRegistration,
                    onOpenNotifSettings = viewModel::openNotificationSettings,
                    onOpenCrashViewer = { viewModel.setCrashViewerVisible(true) },
                )
            }

            item(key = "bottom_pad") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
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
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.setCrashViewerVisible(false) },
            sheetState = sheetState,
            containerColor = colors.bg,
            contentColor = colors.text,
            dragHandle = { PcSheetDragHandle() },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
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
private fun VpnCard(
    state: VpnState?,
    pending: Boolean?,
    busy: Boolean,
    error: String?,
    onToggle: (Boolean) -> Unit,
) {
    val colors = LocalPcColors.current
    val enabled = pending ?: state?.enabled ?: false
    val down = enabled && state != null && !state.tunnelUp
    val noPort = enabled && state != null && state.tunnelUp && state.forwardedPort == null
    val statusText = when {
        busy -> if (enabled) "Turning on…" else "Turning off…"
        !enabled -> "Off"
        down -> "Tunnel down"
        noPort -> "Connected, no forwarded port"
        else -> "Connected via Proton VPN"
    }
    val dotColor = when {
        down || noPort -> colors.warn
        enabled -> colors.ok
        else -> colors.textFaint
    }

    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = if (down || noPort) colors.warn else if (enabled) colors.accent else colors.textDim,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Torrent VPN",
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PcStatusDot(color = dotColor)
                        Text(text = statusText, color = colors.textDim, fontSize = 13.sp)
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    enabled = state != null && !busy,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.bg,
                        checkedTrackColor = colors.accent,
                        uncheckedThumbColor = colors.textFaint,
                        uncheckedTrackColor = colors.surfaceRaised,
                    ),
                )
            }

            if (!error.isNullOrBlank()) {
                PcErrorBanner(message = error)
            }
            if (!state?.lastError.isNullOrBlank()) {
                Text(
                    text = state!!.lastError!!,
                    color = if (down) colors.warn else colors.textFaint,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
            }

            SettingsKvRow(
                label = "Forwarded port",
                value = if (enabled) state?.forwardedPort?.toString() ?: "Waiting for NAT-PMP…" else "—",
                mono = enabled && state?.forwardedPort != null,
            )
            SettingsKvRow(
                label = "VPN public IP",
                value = if (enabled) state?.publicIp ?: "—" else "—",
                mono = enabled && !state?.publicIp.isNullOrBlank(),
            )
        }
    }
}

@Composable
private fun KeyboardLockCard(
    state: KbdLockState?,
    pending: Boolean?,
    busy: Boolean,
    error: String?,
    onToggle: (Boolean) -> Unit,
) {
    val colors = LocalPcColors.current
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

    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Keyboard,
                    contentDescription = null,
                    tint = if (enabled) colors.accent else colors.textDim,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Keyboard lock",
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PcStatusDot(color = dotColor)
                        Text(text = statusText, color = colors.textDim, fontSize = 13.sp)
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    enabled = state != null && !unavailable && !busy,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.bg,
                        checkedTrackColor = colors.accent,
                        uncheckedThumbColor = colors.textFaint,
                        uncheckedTrackColor = colors.surfaceRaised,
                    ),
                )
            }
            if (!error.isNullOrBlank()) {
                PcErrorBanner(message = error)
            }
        }
    }
}

@Composable
private fun KeyringCard(
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
    val locked = status?.locked == true
    val dotColor = when {
        status == null -> colors.textFaint
        locked -> colors.warn
        else -> colors.ok
    }
    val subtitle = when {
        status == null -> "Checking…"
        locked -> "Locked · RDP unavailable (${status.rdp})"
        else -> "Unlocked · RDP ${status.rdp}"
    }

    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = if (locked) colors.warn else if (hasSaved) colors.ok else colors.textDim,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Keyring",
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PcStatusDot(color = dotColor)
                        Text(text = subtitle, color = colors.textDim, fontSize = 13.sp)
                    }
                }
                if (locked) {
                    PcPill(text = "Locked", color = colors.warn, backgroundColor = colors.warnSoft)
                } else if (hasSaved) {
                    PcPill(text = "Saved", color = colors.ok, backgroundColor = colors.okSoft)
                }
            }

            SettingsKvRow(
                label = "Saved password",
                value = if (hasSaved) "Encrypted (fingerprint)" else "Not saved",
            )
            SettingsKvRow(
                label = "Storage",
                value = "Phone Keystore only",
            )

            if (!error.isNullOrBlank()) {
                PcErrorBanner(message = error)
            }
            if (!notice.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.okSoft)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = notice,
                        color = colors.ok,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Dismiss",
                        color = colors.text,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onDismissNotice)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }

            if (locked) {
                PcButton(
                    label = if (hasSaved) "Unlock (fingerprint)" else "Unlock keyring",
                    variant = PcButtonVariant.Primary,
                    busy = busy,
                    onClick = onUnlock,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (vaultSupported) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PcButton(
                        label = if (hasSaved) "Change password" else "Save password",
                        variant = if (hasSaved || locked) PcButtonVariant.Subtle else PcButtonVariant.Primary,
                        enabled = !busy,
                        onClick = onSavePassword,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasSaved) {
                        PcButton(
                            label = "Forget",
                            variant = PcButtonVariant.Danger,
                            enabled = !busy,
                            onClick = onForget,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsCard(
    settings: NotifSettings?,
    expanded: Boolean,
    error: String?,
    onToggleExpand: () -> Unit,
    onToggleMaster: (channel: String, enabled: Boolean) -> Unit,
    onToggleCategory: (categoryId: String, channel: String, enabled: Boolean) -> Unit,
) {
    val colors = LocalPcColors.current

    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "Where notifications go",
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = colors.textDim,
                    modifier = Modifier.size(22.dp),
                )
            }

            if (!error.isNullOrBlank()) {
                PcErrorBanner(message = error)
            }

            if (settings == null) {
                Text(text = "Loading…", color = colors.textDim, fontSize = 13.5.sp)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceRaised)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val tgOn = settings.masters["telegram"] == true
                    val appOn = settings.masters["app"] == true

                    MasterChannelToggle(
                        label = "Telegram",
                        subtitle = if (!settings.channels.telegram.configured) "not set up" else if (tgOn) "on" else "off",
                        icon = Icons.Filled.Send,
                        checked = tgOn,
                        onCheckedChange = { onToggleMaster("telegram", it) },
                        modifier = Modifier.weight(1f),
                    )
                    MasterChannelToggle(
                        label = "App",
                        subtitle = if (!settings.channels.app.configured) "not set up" else if (appOn) "on" else "off",
                        icon = Icons.Filled.PhoneAndroid,
                        checked = appOn,
                        onCheckedChange = { onToggleMaster("app", it) },
                        modifier = Modifier.weight(1f),
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Category",
                                color = colors.textDim,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "Telegram",
                                color = colors.textDim,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(64.dp),
                            )
                            Text(
                                text = "App",
                                color = colors.textDim,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(56.dp),
                            )
                        }
                        HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)

                        val tgMaster = settings.masters["telegram"] == true
                        val appMaster = settings.masters["app"] == true

                        settings.categories.forEachIndexed { idx, cat ->
                            if (idx > 0) {
                                HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                            }
                            val supportsTelegram = cat.channels == null || "telegram" in cat.channels
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cat.label,
                                        color = if (cat.telegram || cat.app) colors.text else colors.textFaint,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Box(
                                    modifier = Modifier.width(64.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (supportsTelegram) {
                                        Switch(
                                            checked = cat.telegram,
                                            onCheckedChange = { onToggleCategory(cat.id, "telegram", it) },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = colors.bg,
                                                checkedTrackColor = if (tgMaster) colors.accent else colors.hairline,
                                                uncheckedThumbColor = colors.textFaint,
                                                uncheckedTrackColor = colors.surfaceRaised,
                                            ),
                                        )
                                    } else {
                                        Text(text = "–", color = colors.textFaint, fontSize = 15.sp)
                                    }
                                }
                                Box(
                                    modifier = Modifier.width(56.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Switch(
                                        checked = cat.app,
                                        onCheckedChange = { onToggleCategory(cat.id, "app", it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = colors.bg,
                                            checkedTrackColor = if (appMaster) colors.accent else colors.hairline,
                                            uncheckedThumbColor = colors.textFaint,
                                            uncheckedTrackColor = colors.surfaceRaised,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MasterChannelToggle(
    label: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) colors.accent else colors.textFaint,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = colors.textFaint, fontSize = 11.5.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.bg,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.textFaint,
                uncheckedTrackColor = colors.surface,
            ),
        )
    }
}

@Composable
private fun ConnectionCard(
    serverUrl: String,
    chatBaseUrl: String,
    chatToken: String,
    dirty: Boolean,
    testing: Boolean,
    info: ServerInfo?,
    whoAmI: String?,
    error: String?,
    onServerUrlChange: (String) -> Unit,
    onChatBaseUrlChange: (String) -> Unit,
    onChatTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    val colors = LocalPcColors.current

    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "Laptop server URL (tailnet)", color = colors.textDim, fontSize = 12.5.sp)
                PcCompactTextField(
                    value = serverUrl,
                    onValueChange = onServerUrlChange,
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "Chat API URL", color = colors.textDim, fontSize = 12.5.sp)
                PcCompactTextField(
                    value = chatBaseUrl,
                    onValueChange = onChatBaseUrlChange,
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "Chat token", color = colors.textDim, fontSize = 12.5.sp)
                PcCompactTextField(
                    value = chatToken,
                    onValueChange = onChatTokenChange,
                    mono = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PcButton(
                    label = "Test",
                    variant = PcButtonVariant.Subtle,
                    busy = testing,
                    onClick = onTest,
                    modifier = Modifier.weight(1f),
                )
                PcButton(
                    label = "Save",
                    variant = PcButtonVariant.Primary,
                    enabled = dirty,
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                )
            }

            if (!error.isNullOrBlank()) {
                PcErrorBanner(message = error)
            }

            if (info != null) {
                HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                SettingsKvRow(label = "Host", value = info.host.ifBlank { "—" })
                SettingsKvRow(label = "Signed in as", value = whoAmI ?: "—")
                val rootdLabel = if (info.rootd.state == "ok") {
                    "running · ${info.rootd.devices ?: 0} phone(s)"
                } else {
                    info.rootd.state.replace('_', ' ')
                }
                SettingsKvRow(label = "rootd", value = rootdLabel)
            }
        }
    }
}

@Composable
private fun AppUpdatesCard(
    currentVersionName: String,
    currentVersionCode: Int,
    latestRelease: ApkReleaseInfo?,
    hasNewer: Boolean,
    checking: Boolean,
    error: String?,
    onCheck: () -> Unit,
    onDownloadInstall: () -> Unit,
) {
    val colors = LocalPcColors.current

    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = if (hasNewer) colors.accent else colors.textDim,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "GitHub Releases APK",
                        color = colors.text,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Installed v$currentVersionName ($currentVersionCode)",
                        color = colors.textDim,
                        fontSize = 12.5.sp,
                        style = TabularNumStyle,
                    )
                }
                if (hasNewer) {
                    PcPill(text = "Update ready", color = colors.accent, backgroundColor = colors.accentSoft)
                }
            }

            if (hasNewer && latestRelease != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.accentSoft)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "New version: v${latestRelease.versionName} (${latestRelease.versionCode})",
                        color = colors.accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TabularNumStyle,
                    )
                    if (!latestRelease.releaseNotes.isNullOrBlank()) {
                        Text(
                            text = latestRelease.releaseNotes,
                            color = colors.textDim,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            } else if (latestRelease != null) {
                Text(
                    text = "Up to date (latest v${latestRelease.versionName}).",
                    color = colors.textFaint,
                    fontSize = 12.5.sp,
                )
            }

            if (!error.isNullOrBlank()) {
                PcErrorBanner(message = error)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PcButton(
                    label = "Check updates",
                    variant = PcButtonVariant.Subtle,
                    busy = checking,
                    onClick = onCheck,
                    modifier = Modifier.weight(1f),
                )
                if (hasNewer) {
                    PcButton(
                        label = "Download & Install",
                        variant = PcButtonVariant.Primary,
                        onClick = onDownloadInstall,
                        modifier = Modifier.weight(1.3f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    fcm: FcmDiagnostics,
    serverRegistered: Boolean?,
    crashCount: Int,
    onRetryPush: () -> Unit,
    onOpenNotifSettings: () -> Unit,
    onOpenCrashViewer: () -> Unit,
) {
    val colors = LocalPcColors.current
    val fcmOk = fcm.notificationsEnabled && !fcm.token.isNullOrBlank() && serverRegistered == true
    val fcmStatus = when {
        !fcm.notificationsEnabled -> "Notifications off"
        fcm.token.isNullOrBlank() -> fcm.error ?: "No FCM token"
        serverRegistered == true -> "Connected · registered"
        serverRegistered == false -> "Not registered on laptop"
        else -> "Token present"
    }

    PcListCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PcStatusDot(color = if (fcmOk) colors.ok else colors.warn)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "FCM push status",
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = fcmStatus,
                        color = colors.textFaint,
                        fontSize = 12.5.sp,
                    )
                }
                if (!fcm.notificationsEnabled) {
                    PcButton(
                        label = "Allow",
                        variant = PcButtonVariant.Subtle,
                        onClick = onOpenNotifSettings,
                    )
                } else if (!fcmOk) {
                    PcButton(
                        label = "Retry",
                        variant = PcButtonVariant.Subtle,
                        onClick = onRetryPush,
                    )
                }
            }
            if (!fcm.token.isNullOrBlank()) {
                Text(
                    text = "Token: ${fcm.token.take(24)}…",
                    color = colors.textFaint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                )
            }
        }
        HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
        PcListRow(
            icon = Icons.Filled.BugReport,
            title = "Crash reports",
            subtitle = if (crashCount == 0) "No crash reports recorded" else "$crashCount recorded crash report${if (crashCount == 1) "" else "s"}",
            showDivider = false,
            onClick = onOpenCrashViewer,
        )
    }
}

@Composable
private fun CrashReportsSheetContent(
    reports: List<CrashReportEntry>,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalPcColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 12.dp),
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
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (reports.isNotEmpty()) {
                PcButton(
                    label = "Clear all",
                    variant = PcButtonVariant.Danger,
                    onClick = onClear,
                )
            }
        }

        if (reports.isEmpty()) {
            PcCard {
                Text(
                    text = "No crash reports stored on this device.",
                    color = colors.textDim,
                    fontSize = 13.5.sp,
                )
            }
        } else {
            reports.forEach { entry ->
                PcCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = entry.summary,
                            color = colors.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = entry.timestamp,
                            color = colors.textFaint,
                            fontSize = 12.sp,
                            style = TabularNumStyle,
                        )
                        Text(
                            text = entry.stackTrace.take(1500),
                            color = colors.textDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }

        PcButton(
            label = "Close",
            variant = PcButtonVariant.Subtle,
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsKvRow(label: String, value: String, mono: Boolean = false) {
    val colors = LocalPcColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = colors.textDim, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = colors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TabularNumStyle,
        )
    }
}
