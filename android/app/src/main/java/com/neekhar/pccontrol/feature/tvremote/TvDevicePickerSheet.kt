package com.neekhar.pccontrol.feature.tvremote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.ui.PcBottomSheet
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcIconButton
import kotlinx.coroutines.launch

/**
 * Reusable Smart Cast controller for Watch Hero, Continue Watching, Detail, Episode Browser,
 * and WatchPlayer Cast-to-TV handoff:
 * - 1 active device -> casts immediately with zero friction
 * - 2+ active devices (or long-press / forcePicker) -> opens [TvDevicePickerSheet]
 * - 0 active devices -> calls [onNoDevices] with a clear message
 */
class SmartCastController(
    private val launchCast: (forcePicker: Boolean, onTargetSelected: (TvDeviceInfo?) -> Unit) -> Unit,
) {
    fun cast(
        forcePicker: Boolean = false,
        onTargetSelected: (TvDeviceInfo?) -> Unit,
    ) {
        launchCast(forcePicker, onTargetSelected)
    }
}

@Composable
fun rememberSmartCastController(
    tvRemoteViewModel: TvRemoteViewModel?,
    onNoDevices: (String) -> Unit,
): SmartCastController {
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    var pendingCastAction by remember { mutableStateOf<((TvDeviceInfo) -> Unit)?>(null) }

    val tvState = tvRemoteViewModel?.uiState?.collectAsStateWithLifecycle()?.value

    if (showPicker && tvRemoteViewModel != null && tvState != null) {
        TvDevicePickerSheet(
            devices = tvState.availableDevices,
            loading = tvState.loadingDevices,
            selectedSessionId = tvState.selectedSessionId,
            selectedDeviceName = tvState.selectedDeviceName,
            onSelect = { device ->
                showPicker = false
                tvRemoteViewModel.selectDevice(device)
                val action = pendingCastAction
                pendingCastAction = null
                action?.invoke(device)
            },
            onRefresh = { tvRemoteViewModel.refreshDevices() },
            onDismiss = {
                showPicker = false
                pendingCastAction = null
            },
        )
    }

    return remember(tvRemoteViewModel, onNoDevices) {
        SmartCastController { forcePicker, onTargetSelected ->
            if (tvRemoteViewModel == null) {
                onTargetSelected(null)
                return@SmartCastController
            }
            scope.launch {
                when (val res = tvRemoteViewModel.fetchActiveDevices()) {
                    is ApiResult.Error -> {
                        onNoDevices(res.message)
                    }
                    is ApiResult.Success -> {
                        val devices = res.data
                        when {
                            devices.isEmpty() -> {
                                onNoDevices("Open Jellyfin on any TV to cast")
                            }
                            !forcePicker && devices.size == 1 -> {
                                val only = devices.first()
                                tvRemoteViewModel.selectDevice(only)
                                onTargetSelected(only)
                            }
                            else -> {
                                pendingCastAction = { picked -> onTargetSelected(picked) }
                                showPicker = true
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvDevicePickerSheet(
    devices: List<TvDeviceInfo>,
    loading: Boolean,
    selectedSessionId: String?,
    selectedDeviceName: String?,
    onSelect: (TvDeviceInfo) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Cast to TV",
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current

    PcBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = PcDimens.Space3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = colors.text,
                    style = type.collapsedTitle,
                )
                Text(
                    text = if (devices.isEmpty()) {
                        "Active Jellyfin sessions"
                    } else {
                        "${devices.size} ${if (devices.size == 1) "device" else "devices"} online"
                    },
                    color = colors.textDim,
                    fontSize = 12.5.sp,
                )
            }
            if (loading) {
                CircularProgressIndicator(
                    color = colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                PcIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "Refresh TV devices",
                    onClick = onRefresh,
                    tint = colors.textDim,
                )
            }
        }

        if (devices.isEmpty()) {
            PcEmptyState(
                icon = Icons.Rounded.Tv,
                title = "No TVs open in Jellyfin",
                body = "Open Jellyfin on any TV or projector, then tap Refresh.",
                action = {
                    PcButton(
                        label = "Refresh",
                        onClick = onRefresh,
                        variant = PcButtonVariant.Subtle,
                        icon = Icons.Rounded.Refresh,
                        busy = loading,
                    )
                },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(PcDimens.RadiusLg))
                    .background(colors.surfaceRaised),
            ) {
                devices.forEachIndexed { idx, device ->
                    val isSelected = when {
                        !selectedSessionId.isNullOrBlank() -> device.sessionId == selectedSessionId
                        !selectedDeviceName.isNullOrBlank() -> device.deviceName.equals(selectedDeviceName, ignoreCase = true)
                        else -> idx == 0
                    }
                    val subtitle = buildList {
                        device.client?.takeIf { it.isNotBlank() }?.let { add(it) }
                        if (device.isPlaying && !device.nowPlayingTitle.isNullOrBlank()) {
                            add("Playing ${device.nowPlayingTitle}")
                        } else if (device.isPlaying) {
                            add("Playing")
                        } else {
                            add("Ready")
                        }
                    }.joinToString(" · ")

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .clickable { onSelect(device) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) colors.accentSoft else colors.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (device.isTv) Icons.Rounded.Tv else Icons.Rounded.Devices,
                                contentDescription = null,
                                tint = if (isSelected) colors.accent else colors.textDim,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = device.deviceName.ifBlank { "Jellyfin Device" },
                                    color = colors.text,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (device.isPlaying) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(colors.accent),
                                    )
                                }
                            }
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    text = subtitle,
                                    color = colors.textFaint,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Selected",
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (idx < devices.lastIndex) {
                        HorizontalDivider(
                            color = colors.hairline,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 62.dp),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(PcDimens.Space2))
    }
}
