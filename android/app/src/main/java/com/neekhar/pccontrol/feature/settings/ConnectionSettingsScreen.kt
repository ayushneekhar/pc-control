package com.neekhar.pccontrol.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.neekhar.pccontrol.core.network.ApiTransport
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcCompactTextField
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcKeyValueRow
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcScaffold
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcTone

@Composable
fun ConnectionSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val listState = rememberLazyListState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val transportLabel = state.activeTransport.label
    val statusText = when {
        state.connectionTesting -> "Checking connection…"
        !state.connectionError.isNullOrBlank() -> "Offline · $transportLabel"
        state.serverInfo != null -> {
            val host = formatShortHostname(state.serverInfo!!.host).ifBlank { "Connected" }
            "$host · $transportLabel"
        }
        else -> transportLabel
    }
    val statusColor = when {
        state.connectionTesting -> colors.warn
        !state.connectionError.isNullOrBlank() -> colors.bad
        state.serverInfo != null -> colors.ok
        else -> colors.textFaint
    }

    PcScaffold(
        modifier = modifier,
        title = "Connection",
        statusText = statusText,
        statusColor = statusColor,
        onBack = onBack,
        listState = listState,
        applyNavigationBarPadding = false,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    top = innerPadding.calculateTopPadding(),
                    bottom = 28.dp + navBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // ---- Public API (Cloudflare) ----
                item(key = "sec_public_api") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PcSectionLabel(
                            label = "Public API (Cloudflare)",
                            trailing = {
                                PcPill(
                                    label = state.activeTransport.label,
                                    tone = if (state.activeTransport == ApiTransport.CLOUDFLARE || state.serverInfo != null) {
                                        PcTone.Ok
                                    } else {
                                        PcTone.Dim
                                    },
                                    showDot = true,
                                )
                            },
                        )
                        PcCard {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Active transport",
                                        color = colors.textDim,
                                        style = type.caption,
                                    )
                                    Text(
                                        text = when {
                                            !state.publicApiConfigured -> "Tailscale (public API not set)"
                                            state.activeTransport == ApiTransport.CLOUDFLARE -> "Cloudflare · off-Tailscale ready"
                                            else -> "Tailscale · direct VPN"
                                        },
                                        color = colors.text,
                                        style = type.caption,
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "API URL",
                                        color = colors.textDim,
                                        style = type.caption,
                                    )
                                    PcCompactTextField(
                                        value = state.apiUrlInput,
                                        onValueChange = viewModel::onApiUrlChange,
                                        placeholder = "https://api.neekhar.dev",
                                        mono = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            autoCorrectEnabled = false,
                                        ),
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "API Bearer token",
                                        color = colors.textDim,
                                        style = type.caption,
                                    )
                                    PcCompactTextField(
                                        value = state.apiTokenInput,
                                        onValueChange = viewModel::onApiTokenChange,
                                        placeholder = "Bearer token from laptop",
                                        mono = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            autoCorrectEnabled = false,
                                        ),
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "CF Access Client ID",
                                        color = colors.textDim,
                                        style = type.caption,
                                    )
                                    PcCompactTextField(
                                        value = state.cfAccessClientIdInput,
                                        onValueChange = viewModel::onCfAccessClientIdChange,
                                        placeholder = "Cloudflare Access service token ID",
                                        mono = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Ascii,
                                            autoCorrectEnabled = false,
                                        ),
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "CF Access Client Secret",
                                        color = colors.textDim,
                                        style = type.caption,
                                    )
                                    PcCompactTextField(
                                        value = state.cfAccessClientSecretInput,
                                        onValueChange = viewModel::onCfAccessClientSecretChange,
                                        placeholder = "Cloudflare Access service token secret",
                                        mono = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            autoCorrectEnabled = false,
                                        ),
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    PcButton(
                                        label = "Sync from Tailscale",
                                        variant = PcButtonVariant.Subtle,
                                        busy = state.publicApiSyncing,
                                        enabled = !state.publicApiTesting,
                                        onClick = viewModel::syncPublicApiFromTailscale,
                                        modifier = Modifier.weight(1f),
                                    )
                                    PcButton(
                                        label = "Test API",
                                        variant = PcButtonVariant.Subtle,
                                        busy = state.publicApiTesting,
                                        enabled = !state.publicApiSyncing,
                                        onClick = viewModel::testPublicApi,
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                if (!state.publicApiNotice.isNullOrBlank()) {
                                    Text(
                                        text = state.publicApiNotice!!,
                                        color = colors.ok,
                                        style = type.caption.tabular(),
                                    )
                                }

                                if (!state.publicApiError.isNullOrBlank()) {
                                    PcErrorBanner(message = state.publicApiError!!)
                                }
                            }
                        }
                    }
                }

                // ---- Tailscale server (direct VPN + WebRTC cams / HLS replay) ----
                item(key = "sec_tailscale") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PcSectionLabel(label = "Tailscale server")
                        PcCard {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Laptop server URL (tailnet)",
                                        color = colors.textDim,
                                        style = type.caption,
                                    )
                                    PcCompactTextField(
                                        value = state.serverUrlInput,
                                        onValueChange = viewModel::onServerUrlChange,
                                        placeholder = "https://hp-pavilion.tail….ts.net",
                                        mono = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            autoCorrectEnabled = false,
                                        ),
                                    )
                                    Text(
                                        text = "Used automatically when Tailscale VPN is on, and always for live WebRTC cams and HLS replay.",
                                        color = colors.textFaint,
                                        style = type.micro,
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    PcButton(
                                        label = "Test",
                                        variant = PcButtonVariant.Subtle,
                                        busy = state.connectionTesting,
                                        onClick = viewModel::testConnection,
                                        modifier = Modifier.weight(1f),
                                    )
                                    PcButton(
                                        label = "Save",
                                        variant = PcButtonVariant.Primary,
                                        enabled = state.connectionDirty,
                                        onClick = viewModel::saveConnection,
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                if (!state.connectionError.isNullOrBlank()) {
                                    PcErrorBanner(message = state.connectionError!!)
                                }
                            }
                        }
                    }
                }

                val info = state.serverInfo
                if (info != null) {
                    item(key = "sec_server") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PcSectionLabel(label = "Server")
                            PcCard {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    PcKeyValueRow(
                                        key = "Host",
                                        value = formatShortHostname(info.host).ifBlank { "—" },
                                    )
                                    PcKeyValueRow(
                                        key = "Transport",
                                        value = state.activeTransport.label,
                                    )
                                    PcKeyValueRow(
                                        key = "Signed in as",
                                        value = buildString {
                                            append(state.whoAmI ?: "—")
                                            state.whoAmIAuth?.takeIf { it.isNotBlank() }?.let { auth ->
                                                append(" (")
                                                append(auth.replace('_', ' '))
                                                append(")")
                                            }
                                        },
                                    )
                                    val rootdLabel = if (info.rootd.state == "ok") {
                                        "running · ${info.rootd.devices ?: 0} phone(s)"
                                    } else {
                                        info.rootd.state.replace('_', ' ')
                                    }
                                    PcKeyValueRow(
                                        key = "rootd",
                                        value = rootdLabel,
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "bottom_pad") {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
