package com.easyesuite.app.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.easyesuite.app.R
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.common.userMessage
import com.easyesuite.app.ui.theme.GreenText
import com.easyesuite.app.ui.theme.GreenTint
import com.easyesuite.core.auth.LoginResult
import com.easyesuite.core.auth.Session
import com.easyesuite.core.model.TenantInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sign-in like the web app: email + password, then choose the workspace (company).
 * Steps: [Step.CREDENTIALS] → optional [Step.SECOND_FACTOR] → [Step.WORKSPACE] (skipped with a single workspace).
 */
enum class Step { CREDENTIALS, SECOND_FACTOR, WORKSPACE }

data class LoginUi(
    val step: Step = Step.CREDENTIALS,
    val email: String = "",
    val password: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val challengeToken: String? = null,
    /** Tokens waiting for a workspace. */
    val pending: Session? = null,
    val workspaces: List<TenantInfo> = emptyList(),
    val selectedTenant: String? = null,
    /** Typed slug when the backend returned no workspace list. */
    val manualTenant: String = "",
    val switching: Boolean = false,
) {
    val canSubmit: Boolean get() = !busy && email.contains('@') && password.length >= 4
    val chosenTenant: String? get() = (selectedTenant ?: manualTenant.trim().lowercase()).takeIf { !it.isNullOrBlank() }
}

class LoginViewModel(private val container: AppContainer) : ViewModel() {
    val ui = MutableStateFlow(LoginUi(email = container.tokenStore.lastEmail ?: ""))

    init {
        // "Switch workspace" from Settings: tokens are still valid, go straight to the picker.
        container.pendingSwitch?.let { pending -> openPicker(pending, emptyList(), switching = true) }
    }

    fun set(transform: LoginUi.() -> LoginUi) = ui.update { it.transform().copy(error = null) }

    fun submit() {
        val s = ui.value
        if (!s.canSubmit) return
        ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val graph = container.graphFor()   // no tenant yet: global sign-in
            try {
                handle(graph.auth.login(s.email, s.password))
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
            try {
                handle(container.graphFor().auth.completeSecondFactor(s.email, s.challengeToken, s.code))
            } catch (e: Exception) {
                ui.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    private fun handle(result: LoginResult) {
        when (result) {
            is LoginResult.Success -> container.onSignedIn(result.session)
            is LoginResult.SecondFactorRequired -> ui.update {
                it.copy(busy = false, step = Step.SECOND_FACTOR, challengeToken = result.challengeToken, message = result.message ?: "Enter the code from your authenticator app.")
            }
            is LoginResult.WorkspaceRequired -> openPicker(result.pending, result.workspaces, switching = false)
        }
    }

    private fun openPicker(pending: Session, workspaces: List<TenantInfo>, switching: Boolean) {
        val last = container.tokenStore.lastTenant
        ui.update {
            it.copy(
                step = Step.WORKSPACE, busy = workspaces.isEmpty() && switching, pending = pending, workspaces = workspaces, switching = switching,
                selectedTenant = workspaces.firstOrNull { w -> w.slug == last }?.slug ?: workspaces.firstOrNull()?.slug,
            )
        }
        if (workspaces.isEmpty() && switching) {
            viewModelScope.launch {
                val list = runCatching { container.graphFor().auth.workspaces() }.getOrDefault(emptyList())
                ui.update { it.copy(busy = false, workspaces = list, selectedTenant = list.firstOrNull { w -> w.slug == last }?.slug ?: list.firstOrNull()?.slug) }
            }
        }
    }

    fun selectWorkspace(slug: String) = ui.update { it.copy(selectedTenant = slug, error = null) }

    fun continueToWorkspace() {
        val s = ui.value
        val pending = s.pending ?: return
        val tenant = s.chosenTenant ?: return
        val session = container.graphFor().auth.selectWorkspace(pending, tenant)
        container.onSignedIn(session)
    }

    fun back() {
        val s = ui.value
        if (s.step == Step.WORKSPACE && s.switching) { container.cancelWorkspaceSwitch(); return }
        container.graphFor().auth.logout()
        ui.update { LoginUi(email = it.email) }
    }

    private fun friendly(e: Exception): String {
        val m = e.userMessage()
        return when {
            m.contains("credentials", true) || m.contains("Unable to log in", true) || m.contains("incorrect", true) -> "Email or password is incorrect."
            else -> m
        }
    }
}

@Composable
fun LoginScreen(vm: LoginViewModel) {
    val ui by vm.ui.collectAsState()
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        BrandBand()
        Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
            when (ui.step) {
                Step.CREDENTIALS -> CredentialsForm(ui, vm)
                Step.SECOND_FACTOR -> SecondFactorForm(ui, vm)
                Step.WORKSPACE -> WorkspacePicker(ui, vm)
            }
            if (ui.error != null) {
                Spacer(Modifier.height(16.dp))
                Text(ui.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The web login's navy panel: logo + tagline. */
@Composable
private fun BrandBand() {
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A1426), Color(0xFF101F3C))))
            .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 28.dp),
    ) {
        Image(painterResource(R.drawable.easyesuite_logo), contentDescription = "EasyEsuite", modifier = Modifier.height(54.dp))
        Spacer(Modifier.height(16.dp))
        Text("EASYESUITE ERP", color = Color(0xFF1FC487), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Spacer(Modifier.height(6.dp))
        Text("One system for everything you sell.", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text("Marketplaces, inventory, fulfillment, and accounting — synchronized in one operation.", color = Color(0xFF9CB2C9), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CredentialsForm(ui: LoginUi, vm: LoginViewModel) {
    var showPassword by remember { mutableStateOf(false) }
    Text("Log in", style = MaterialTheme.typography.headlineSmall)
    Text("Welcome back — sign in to continue.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(20.dp))
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
        if (ui.busy) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("Log in")
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "After signing in you choose which company to work in — the same workspaces you see on erp.easyesuite.com.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun SecondFactorForm(ui: LoginUi, vm: LoginViewModel) {
    Text("Verification", style = MaterialTheme.typography.headlineSmall)
    Text(ui.message ?: "Enter your verification code", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = ui.code, onValueChange = { v -> vm.set { copy(code = v.filter { it.isDigit() }.take(8)) } },
        label = { Text("Verification code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = vm::submitCode, enabled = !ui.busy && ui.code.length >= 4, modifier = Modifier.fillMaxWidth().height(50.dp)) {
        if (ui.busy) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("Verify")
    }
    TextButton(onClick = vm::back) { Text("Back") }
}

@Composable
private fun WorkspacePicker(ui: LoginUi, vm: LoginViewModel) {
    Text("Choose your workspace", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Signed in as ${ui.pending?.email ?: ui.email}. You can switch workspaces any time from Settings.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(16.dp))
    when {
        ui.busy && ui.workspaces.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Loading your workspaces…") }
        ui.workspaces.isEmpty() -> {
            Text("We couldn't list your workspaces — enter the company name you use on the web.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ui.manualTenant, onValueChange = { v -> vm.set { copy(manualTenant = v) } },
                label = { Text("Company (workspace)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            )
        }
        else -> ui.workspaces.forEach { w -> WorkspaceRow(w, selected = w.slug == ui.selectedTenant) { vm.selectWorkspace(w.slug) } }
    }
    Spacer(Modifier.height(20.dp))
    Button(onClick = vm::continueToWorkspace, enabled = ui.chosenTenant != null && !ui.busy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
        Text(ui.chosenTenant?.let { "Continue to $it" } ?: "Continue")
    }
    TextButton(onClick = vm::back) { Text(if (ui.switching) "Cancel" else "Use a different account") }
}

@Composable
private fun WorkspaceRow(w: TenantInfo, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(shape)
            .background(if (selected) Color(0xFFF3FBF7) else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(GreenTint), contentAlignment = Alignment.Center) {
            Text(w.slug.take(2).uppercase(), color = GreenText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(w.slug, fontWeight = FontWeight.SemiBold)
            if (w.name.isNotBlank() && !w.name.equals(w.slug, ignoreCase = true)) Text(w.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = GreenText)
    }
}
