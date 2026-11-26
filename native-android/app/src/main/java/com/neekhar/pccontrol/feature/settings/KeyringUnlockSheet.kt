package com.neekhar.pccontrol.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCompactTextField
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcSheetDragHandle

/**
 * Clean ModalBottomSheet for unlocking the laptop's GNOME login keyring or enrolling
 * the password into the phone's biometric-backed Keystore vault.
 * Plaintext password state is cleared immediately on submit or dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyringUnlockSheet(
    mode: KeyringSheetMode,
    canSaveWithBiometrics: Boolean,
    busy: Boolean,
    error: String?,
    onUnlock: (password: String, saveOnPhone: Boolean) -> Unit,
    onSavePassword: (password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPcColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var password by remember { mutableStateOf("") }
    var saveOnPhone by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { password = "" }
    }

    val submit = {
        val entered = password
        password = ""
        if (mode == KeyringSheetMode.SAVE_PASSWORD) {
            onSavePassword(entered)
        } else {
            onUnlock(entered, canSaveWithBiometrics && saveOnPhone)
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!busy) {
                password = ""
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.text,
        dragHandle = { PcSheetDragHandle() },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (mode == KeyringSheetMode.SAVE_PASSWORD) Icons.Filled.Fingerprint else Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = colors.textDim,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = if (mode == KeyringSheetMode.SAVE_PASSWORD) "Save keyring password" else "Unlock keyring",
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = if (mode == KeyringSheetMode.SAVE_PASSWORD) {
                    "Type your laptop login keyring password once. It will be encrypted with your fingerprint in Android Keystore."
                } else {
                    "Enter your laptop login keyring password to unlock GNOME keyring and restart RDP."
                },
                color = colors.textDim,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
            )

            PcCompactTextField(
                value = password,
                onValueChange = { password = it },
                enabled = !busy,
                placeholder = "Keyring password",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (!busy && password.isNotEmpty()) submit() }),
            )

            if (mode == KeyringSheetMode.UNLOCK && canSaveWithBiometrics) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Save on this phone",
                            color = colors.text,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Encrypted with your fingerprint for future reboots",
                            color = colors.textFaint,
                            fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = saveOnPhone,
                        onCheckedChange = { saveOnPhone = it },
                        enabled = !busy,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.bg,
                            checkedTrackColor = colors.text,
                            checkedBorderColor = Color.Transparent,
                            uncheckedThumbColor = colors.textDim,
                            uncheckedTrackColor = colors.surfaceRaised,
                            uncheckedBorderColor = colors.hairline,
                        ),
                    )
                }
            }

            if (!error.isNullOrBlank()) {
                PcErrorBanner(message = error)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PcButton(
                    label = "Cancel",
                    variant = PcButtonVariant.Subtle,
                    enabled = !busy,
                    onClick = {
                        password = ""
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                PcButton(
                    label = if (mode == KeyringSheetMode.SAVE_PASSWORD) "Save" else "Unlock",
                    variant = PcButtonVariant.Primary,
                    enabled = !busy && password.isNotEmpty(),
                    busy = busy,
                    onClick = submit,
                    modifier = Modifier.weight(1.2f),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
