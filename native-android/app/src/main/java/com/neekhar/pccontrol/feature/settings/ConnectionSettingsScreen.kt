package com.neekhar.pccontrol.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcCompactTextField
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcKeyValueRow
import com.neekhar.pccontrol.core.ui.PcScreenHeader
import com.neekhar.pccontrol.core.ui.PcSectionLabel

@Composable
fun ConnectionSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalPcColors.current

    val statusText = when {
        state.connectionTesting -> "Checking connection…"
        !state.connectionError.isNullOrBlank() -> "Offline"
        state.serverInfo != null -> state.serverInfo!!.host.ifBlank { "Connected" }
        else -> null
    }
    val statusColor = when {
        state.connectionTesting -> colors.warn
        !state.connectionError.isNullOrBlank() -> colors.bad
        state.serverInfo != null -> colors.ok
        else -> null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        PcScreenHeader(
            title = "Connection",
            statusText = statusText,
            statusColor = statusColor,
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 28.dp),
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
                                    fontSize = 12.5.sp,
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
                                    fontSize = 12.5.sp,
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
                                    fontSize = 12.5.sp,
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
                                    value = info.host.ifBlank { "—" },
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
    }
}
