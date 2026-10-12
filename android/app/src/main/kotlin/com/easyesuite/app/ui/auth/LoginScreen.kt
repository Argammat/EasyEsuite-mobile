package com.easyesuite.app.ui.auth

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
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
import coil.compose.AsyncImage
import com.easyesuite.app.BuildConfig
import com.easyesuite.app.R
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.common.userMessage
import com.easyesuite.app.ui.theme.GreenText
import com.easyesuite.app.ui.theme.GreenTint
import com.easyesuite.core.auth.LoginResult
import com.easyesuite.core.auth.Session
import com.easyesuite.core.auth.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sign-in as the backend specifies it: email + password list the user's workspaces (no token yet), the chosen
 * workspace is then logged into with the same credentials. The password lives only in this view model, in
 * memory, until the login succeeds.
 */
enum class Step { CREDENTIALS, WORKSPACE, SECOND_FACTOR, PASSWORD_RESET }

data class LoginUi(
    val step: Step = Step.CREDENTIALS,
    val email: String = "",
    val password: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val workspaces: List<Workspace> = emptyList(),
    val selectedTenant: String? = null,
    val switching: Boolean = false,
) {
    val canSubmit: Boolean get() = !busy && email.contains('@') && password.length >= 4
    val selectedWorkspace: Workspace? get() = workspaces.firstOrNull { it.schemaName == selectedTenant }
}

class LoginViewModel(private val container: AppContainer) : ViewModel() {
    private val switching = container.switchingWorkspace.value
    val ui = MutableStateFlow(
        LoginUi(
            email = container.tokenStore.lastEmail ?: "",
            switching = switching,
            message = if (switching) "Enter your password to choose another workspace." else null,
        ),
    )
    private var challengeToken: String? = null
    private var pendingSession: Session? = null

    fun set(transform: LoginUi.() -> LoginUi) = ui.update { it.transform().copy(error = null) }

    /** Step 1: list the workspaces. One workspace → straight to step 2. */
    fun submit() {
        val s = ui.value
        if (!s.canSubmit) return
        ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val list = container.graphFor().auth.workspaces(s.email, s.password)
                val current = container.session.value?.tenant
                val preferred = if (s.switching) list.firstOrNull { it.schemaName != current }
                else list.firstOrNull { it.schemaName == container.tokenStore.lastTenant } ?: list.firstOrNull()
                ui.update { it.copy(workspaces = list, selectedTenant = (preferred ?: list.firstOrNull())?.schemaName) }
                when {
                    list.size == 1 && !s.switching -> login(list.single().schemaName)
                    list.isEmpty() -> ui.update { it.copy(busy = false, error = "Your account has no workspace yet. Ask an administrator to add you to a company.") }
                    else -> ui.update { it.copy(busy = false, step = Step.WORKSPACE) }
                }
            } catch (e: Exception) {
                ui.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    fun selectWorkspace(schemaName: String) = ui.update { it.copy(selectedTenant = schemaName, error = null) }

    /** Step 2: log in to the chosen workspace. */
    fun continueToWorkspace() {
        val s = ui.value
        val tenant = s.selectedTenant ?: return
        if (s.busy) return
        ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { login(tenant) }
    }

    private suspend fun login(tenant: String) {
        val s = ui.value
        try {
            handle(container.graphFor(tenant).auth.login(s.email, s.password))
        } catch (e: Exception) {
            ui.update { it.copy(busy = false, error = friendly(e)) }
        }
    }

    fun submitCode() {
        val s = ui.value
        val tenant = s.selectedTenant ?: return
        if (s.code.length < 4 || s.busy) return
        ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                handle(container.graphFor(tenant).auth.completeSecondFactor(s.email, challengeToken, pendingSession, s.code))
            } catch (e: Exception) {
                ui.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    private fun handle(result: LoginResult) {
        when (result) {
            is LoginResult.Success -> {
                pendingSession = null; challengeToken = null
                ui.update { it.copy(password = "", code = "", busy = false) }
                container.onSignedIn(result.session)
            }
            is LoginResult.SecondFactorRequired -> {
                pendingSession = result.pending; challengeToken = result.challengeToken
                ui.update { it.copy(busy = false, step = Step.SECOND_FACTOR, message = result.message ?: "Enter the code from your authenticator app.") }
            }
            is LoginResult.PasswordResetRequired -> ui.update { it.copy(busy = false, password = "", step = Step.PASSWORD_RESET) }
        }
    }

    fun back() {
        val s = ui.value
        if (s.switching && s.step == Step.CREDENTIALS) { container.cancelWorkspaceSwitch(); return }
        pendingSession = null; challengeToken = null
        ui.update { it.copy(step = Step.CREDENTIALS, code = "", error = null, busy = false, message = if (it.switching) it.message else null) }
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
                Step.WORKSPACE -> WorkspacePicker(ui, vm)
                Step.SECOND_FACTOR -> SecondFactorForm(ui, vm)
                Step.PASSWORD_RESET -> PasswordResetNotice(vm)
            }
            if (ui.error != null) {
                Spacer(Modifier.height(16.dp))
                Text(ui.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (AppContainer.environmentName != "Production") {
            Text(
                "${AppContainer.environmentName} backend · ${Uri.parse(BuildConfig.API_ROOT).host.orEmpty()}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
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
    Text(if (ui.switching) "Switch workspace" else "Log in", style = MaterialTheme.typography.headlineSmall)
    Text(ui.message ?: "Welcome back — sign in to continue.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
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
        if (ui.busy) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text(if (ui.switching) "Continue" else "Log in")
    }
    Spacer(Modifier.height(12.dp))
    if (ui.switching) {
        TextButton(onClick = vm::back, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    } else {
        Text(
            "After signing in you choose which company to work in — the same workspaces you see on erp.easyesuite.com.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun WorkspacePicker(ui: LoginUi, vm: LoginViewModel) {
    Text("Choose your workspace", style = MaterialTheme.typography.headlineSmall)
    Text("Workspaces for ${ui.email}. You can switch any time from Settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(16.dp))
    ui.workspaces.forEach { w -> WorkspaceRow(w, selected = w.schemaName == ui.selectedTenant) { vm.selectWorkspace(w.schemaName) } }
    Spacer(Modifier.height(20.dp))
    Button(onClick = vm::continueToWorkspace, enabled = ui.selectedTenant != null && !ui.busy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
        if (ui.busy) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(ui.selectedWorkspace?.let { "Continue to ${it.name}" } ?: "Continue")
    }
    TextButton(onClick = vm::back) { Text(if (ui.switching) "Cancel" else "Use a different account") }
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
private fun PasswordResetNotice(vm: LoginViewModel) {
    val context = LocalContext.current
    Text("Password reset required", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Your administrator requires a new password before you can continue. Reset it on the web, then sign in here again.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://erp.easyesuite.com/login"))) },
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) { Text("Reset on erp.easyesuite.com") }
    TextButton(onClick = vm::back, modifier = Modifier.fillMaxWidth()) { Text("Back to sign in") }
}

@Composable
private fun WorkspaceRow(w: Workspace, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(shape)
            .background(if (selected) Color(0xFFF3FBF7) else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(GreenTint), contentAlignment = Alignment.Center) {
            if (w.logoUrl.isNullOrBlank()) {
                Text(w.monogram, color = GreenText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            } else {
                AsyncImage(model = w.logoUrl, contentDescription = w.name, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(w.name, fontWeight = FontWeight.SemiBold)
            Text(w.schemaName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = GreenText)
    }
}
