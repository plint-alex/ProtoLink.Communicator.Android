package ru.protolink.communicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.protolink.communicator.util.ChangePasswordStrings

/**
 * Settings → Account Change password form (docs/features/change-password).
 * Calls authenticated Api via [MainViewModel.changePasswordAsync].
 */
@Composable
fun ChangePasswordDialog(
    vm: MainViewModel,
    lang: String?,
    onDismiss: () -> Unit
) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val tgMuted = Color(0xFF8E8E93)
    val tgBlue = Color(0xFF2AABEE)

    if (successMessage != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(ChangePasswordStrings.title(lang)) },
            text = { Text(successMessage!!) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("OK", color = tgBlue) }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(ChangePasswordStrings.title(lang)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = current,
                    onValueChange = {
                        current = it
                        error = null
                    },
                    label = { Text(ChangePasswordStrings.fieldCurrent(lang)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = next,
                    onValueChange = {
                        next = it
                        error = null
                    },
                    label = { Text(ChangePasswordStrings.fieldNew(lang)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        error = null
                    },
                    label = { Text(ChangePasswordStrings.fieldConfirm(lang)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!error.isNullOrBlank()) {
                    Text(error!!, color = Color(0xFFFF3B30), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val validation = ChangePasswordStrings.validate(current, next, confirm, lang)
                    if (validation != null) {
                        error = validation
                        return@TextButton
                    }
                    busy = true
                    error = null
                    scope.launch {
                        val outcome = vm.changePasswordAsync(current, next)
                        busy = false
                        if (outcome.success) {
                            successMessage =
                                "${ChangePasswordStrings.success(lang)}\n${ChangePasswordStrings.successHint(lang)}"
                        } else {
                            error = ChangePasswordStrings.mapApiError(outcome.error, lang)
                        }
                    }
                }
            ) {
                Text(
                    if (busy) "…" else ChangePasswordStrings.submit(lang),
                    color = tgBlue
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(ChangePasswordStrings.cancel(lang), color = tgMuted)
            }
        }
    )
}
