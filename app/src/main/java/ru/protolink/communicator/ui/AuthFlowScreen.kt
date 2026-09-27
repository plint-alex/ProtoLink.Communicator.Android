package ru.protolink.communicator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

private enum class AuthStep { Welcome, Email, Password, CheckEmail, Login }

private val TgBlue = Color(0xFF2AABEE)
private val TgBg = Color(0xFFF0F2F5)
private val TgMuted = Color(0xFF667781)

@Composable
fun AuthFlowScreen(
    vm: MainViewModel,
    status: String,
    onOpenSettings: () -> Unit
) {
    var step by remember { mutableStateOf(AuthStep.Welcome) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun go(next: AuthStep) {
        error = ""
        info = ""
        step = next
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TgBg)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (step) {
            AuthStep.Welcome -> {
                Text("ProtoLink", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Start messaging with people on ProtoLink.",
                    color = TgMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = { go(AuthStep.Email) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TgBlue)
                ) { Text("Start messaging") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { go(AuthStep.Login) }) { Text("I already have an account", color = TgBlue) }
                TextButton(onClick = onOpenSettings) { Text("Server settings", color = TgMuted) }
            }

            AuthStep.Email -> {
                AuthBack { go(AuthStep.Welcome) }
                Text("Your email", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("We'll send a confirmation link to this address.", color = TgMuted)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = {
                        if (isValidEmail(email)) go(AuthStep.Password) else error = "Enter a valid email address."
                    })
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (isValidEmail(email)) go(AuthStep.Password) else error = "Enter a valid email address."
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TgBlue)
                ) { Text("Next") }
            }

            AuthStep.Password -> {
                AuthBack { go(AuthStep.Email) }
                Text("Create a password", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(email.trim(), color = TgMuted)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            error = ""
                            info = ""
                            when {
                                password.length < 4 -> error = "Password must be at least 4 characters."
                                password != confirm -> error = "Passwords do not match."
                                else -> {
                                    busy = true
                                    val outcome = vm.registerAsync(
                                        email.trim(),
                                        password,
                                        Locale.getDefault().toLanguageTag()
                                    )
                                    busy = false
                                    when {
                                        outcome.success -> {
                                            loginPassword = password
                                            go(AuthStep.CheckEmail)
                                        }
                                        !outcome.emailError.isNullOrBlank() ->
                                            error = "Could not send confirmation email: ${outcome.emailError}"
                                        else -> error = outcome.error ?: "Registration failed."
                                    }
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TgBlue)
                ) { Text("Create account") }
            }

            AuthStep.CheckEmail -> {
                AuthBack { go(AuthStep.Password) }
                Text("Check your email", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "We sent a confirmation link to ${email.trim()}. Open the link, then continue to log in.",
                    color = TgMuted,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = ""
                            info = ""
                            val outcome = vm.registerAsync(email.trim(), password, Locale.getDefault().toLanguageTag())
                            busy = false
                            when {
                                outcome.success -> info = "Confirmation email sent again."
                                !outcome.emailError.isNullOrBlank() ->
                                    error = "Could not send confirmation email: ${outcome.emailError}"
                                else -> error = outcome.error ?: "Resend failed."
                            }
                        }
                    },
                    enabled = !busy
                ) { Text("Resend email", color = TgBlue) }
                Button(
                    onClick = {
                        loginPassword = password
                        go(AuthStep.Login)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TgBlue)
                ) { Text("Continue to log in") }
            }

            AuthStep.Login -> {
                AuthBack { go(AuthStep.Welcome) }
                Text("Log in", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = loginPassword,
                    onValueChange = { loginPassword = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (!busy) {
                            busy = true
                            error = ""
                            vm.login(email.trim(), loginPassword)
                            busy = false
                        }
                    })
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        error = ""
                        when {
                            email.isBlank() -> error = "Enter your email."
                            loginPassword.isBlank() -> error = "Enter your password."
                            else -> vm.login(email.trim(), loginPassword)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TgBlue)
                ) { Text("Log in") }
            }
        }

        if (busy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(color = TgBlue)
        }
        if (info.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(info, color = TgMuted, textAlign = TextAlign.Center)
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = Color(0xFFED4956), textAlign = TextAlign.Center)
        }
        if (status.isNotBlank() && step == AuthStep.Login) {
            Spacer(Modifier.height(8.dp))
            Text(status, color = TgMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AuthBack(onBack: () -> Unit) {
    TextButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Text("← Back", color = TgBlue, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
    }
    Spacer(Modifier.height(8.dp))
}

private fun isValidEmail(value: String): Boolean {
    val t = value.trim()
    return t.contains('@') && t.substringAfter('@').contains('.')
}
