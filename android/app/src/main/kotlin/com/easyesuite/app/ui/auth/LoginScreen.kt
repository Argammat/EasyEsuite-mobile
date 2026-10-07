package com.easyesuite.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.common.userMessage
import com.easyesuite.core.auth.LoginResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUi(
    val tenant: String = "",
    val email: String = "",
    val password: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val secondFactor: Boolean = false,
    val challengeToken: String? = null,
    val message: String? = null,
) {
    val canSubmit: Boolean get() = !busy && tenant.isNotBlank() && email.contains('@') && password.length >= 4
}

class LoginViewModel(private val container: AppContainer) : ViewModel() {
    val ui = MutableStateFlow(
        LoginUi(tenant = container.tokenStore.lastTenant ?: "", email = container.tokenStore.lastEmail ?: ""),
    )

    fun set(transform: LoginUi.() -> LoginUi) = ui.update { it.transform().copy(error = null) }

    fun submit() {
        val s = ui.value
        if (!s.canSubmit) return
        ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val tenant = s.tenant.trim().lowercase()
            // Firebase sign-in needs the workspace's Identity Platform tenant id; the global directory maps
            // the company name to it (falls back to the build-time default, then to project-level users).
            val identityTenant = if (container.firebaseSignIn) container.tenantDirectory.lookup(tenant)?.firebaseTenantId else null
            val graph = container.graphFor(tenant, identityTenant)
            try {
                when (val r = graph.auth.login(s.email, s.password)) {
                    is LoginResult.Success -> container.onSignedIn(r.session, graph)
                    is LoginResult.SecondFactorRequired -> ui.update {
                        it.copy(busy = false, secondFactor = true, challengeToken = r.challengeToken, message = r.message ?: "Enter the code from your authenticator app.")
                    }
                }
            } catch (e: Exception) {
                ui.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    fun submitCode() {
        val s = ui.value
        if (s.code.length < 4) return
        ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val graph = container.graphFor(s.tenant.trim().lowercase())
            try {
                when (val r = graph.auth.completeSecondFactor(s.email, s.challengeToken, s.code)) {
                    is LoginResult.Success -> container.onSignedIn(r.session, graph)
                    is LoginResult.SecondFactorRequired -> ui.update { it.copy(busy = false, error = "That code was not accepted.") }
                }
            } catch (e: Exception) {
                ui.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    fun cancelSecondFactor() = ui.update { it.copy(secondFactor = false, code = "", challengeToken = null, error = null) }

    private fun friendly(e: Exception): String {
        val m = e.userMessage()
        return when {
            m.contains("404") || m == "Not found." -> "We couldn't find a workspace called “${ui.value.tenant}”. Check the company name."
            m.contains("credentials", true) || m.contains("Unable to log in", true) -> "Email or password is incorrect."
            else -> m
        }
    }
}

@Composable
fun LoginScreen(vm: LoginViewModel) {
    val ui by vm.ui.collectAsState()
    var showPassword by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("EasyEsuite", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Text("Sign in to your workspace", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(32.dp))

        if (!ui.secondFactor) {
            OutlinedTextField(
                value = ui.tenant, onValueChange = { v -> vm.set { copy(tenant = v) } },
                label = { Text("Company (workspace)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("The same company name you use on erp.easyesuite.com") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = ui.email, onValueChange = { v -> vm.set { copy(email = v) } },
                label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = ui.password, onValueChange = { v -> vm.set { copy(password = v) } },
                label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle password")
                    }
                },
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = vm::submit, enabled = ui.canSubmit, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (ui.busy) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("Sign in")
            }
        } else {
            Text(ui.message ?: "Enter your verification code", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = ui.code, onValueChange = { v -> vm.set { copy(code = v.filter { it.isDigit() }.take(8)) } },
                label = { Text("Verification code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = vm::submitCode, enabled = !ui.busy && ui.code.length >= 4, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (ui.busy) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("Verify")
            }
            TextButton(onClick = vm::cancelSecondFactor) { Text("Back") }
        }

        if (ui.error != null) {
            Spacer(Modifier.height(16.dp))
            Text(ui.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
