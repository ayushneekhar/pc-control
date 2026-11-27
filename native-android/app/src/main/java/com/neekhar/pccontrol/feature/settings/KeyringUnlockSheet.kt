package com.neekhar.pccontrol.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.ui.PcBottomSheet
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCompactTextField
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcSwitch

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
    val type = LocalPcType.current
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

    PcBottomSheet(
        onDismissRequest = {
            if (!busy) {
                password = ""
                onDismiss()
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (mode == KeyringSheetMode.SAVE_PASSWORD) Icons.Rounded.Fingerprint else Icons.Rounded.LockOpen,
                    contentDescription = null,
                    tint = colors.textDim,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = if (mode == KeyringSheetMode.SAVE_PASSWORD) "Save keyring password" else "Unlock keyring",
                    color = colors.text,
                    style = type.collapsedTitle,
                )
            }

            Text(
                text = if (mode == KeyringSheetMode.SAVE_PASSWORD) {
                    "Type your laptop login keyring password once. It will be encrypted with your fingerprint in Android Keystore."
                } else {
                    "Enter your laptop login keyring password to unlock GNOME keyring and restart RDP."
                },
                color = colors.textDim,
                style = type.body,
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
                            style = type.cardTitle,
                        )
                        Text(
                            text = "Encrypted with your fingerprint for future reboots",
                            color = colors.textFaint,
                            style = type.caption,
                        )
                    }
                    PcSwitch(
                        checked = saveOnPhone,
                        onCheckedChange = { saveOnPhone = it },
                        enabled = !busy,
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
