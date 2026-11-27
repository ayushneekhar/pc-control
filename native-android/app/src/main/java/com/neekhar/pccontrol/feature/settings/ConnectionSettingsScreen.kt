package com.neekhar.pccontrol.feature.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcCompactTextField
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcKeyValueRow
import com.neekhar.pccontrol.core.ui.PcScaffold
import com.neekhar.pccontrol.core.ui.PcSectionLabel

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

    val statusText = when {
        state.connectionTesting -> "Checking connection…"
        !state.connectionError.isNullOrBlank() -> "Offline"
        state.serverInfo != null -> formatShortHostname(state.serverInfo!!.host).ifBlank { "Connected" }
        else -> null
    }
    val statusColor = when {
        state.connectionTesting -> colors.warn
        !state.connectionError.isNullOrBlank() -> colors.bad
        state.serverInfo != null -> colors.ok
        else -> null
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
                item(key = "sec_endpoints") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PcSectionLabel(label = "Endpoints")
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
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Chat API URL",
                                        color = colors.textDim,
                                        style = type.caption,
                                    )
                                    PcCompactTextField(
                                        value = state.chatBaseUrlInput,
                                        onValueChange = viewModel::onChatBaseUrlChange,
                                        placeholder = "https://…",
                                        mono = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            autoCorrectEnabled = false,
                                        ),
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Chat token",
                                        color = colors.textDim,
                                        style = type.caption,
                                    )
                                    PcCompactTextField(
                                        value = state.chatTokenInput,
                                        onValueChange = viewModel::onChatTokenChange,
                                        placeholder = "Optional bearer token",
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
                                        key = "Signed in as",
                                        value = state.whoAmI ?: "—",
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

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(navBottom + 20.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, colors.bg),
                        ),
                    ),
            )
        }
    }
}
