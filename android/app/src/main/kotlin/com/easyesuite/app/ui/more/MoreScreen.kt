package com.easyesuite.app.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.easyesuite.app.BuildConfig
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.Routes
import com.easyesuite.app.ui.common.KeyValueRow
import com.easyesuite.app.ui.common.RowDivider
import com.easyesuite.app.ui.common.SectionTitle
import com.easyesuite.app.ui.common.userMessage
import com.easyesuite.core.model.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(graph: AppContainer.Graph, nav: NavHostController, container: AppContainer) {
    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SectionTitle("Warehouse", Modifier.padding(horizontal = 16.dp))
            MoreRow("Receive purchase orders", Icons.Default.CallReceived) { nav.navigate(Routes.RECEIVE) }
            MoreRow("Transfers", Icons.Default.SwapHoriz) { nav.navigate(Routes.TRANSFERS) }
            MoreRow("Stock adjustment", Icons.Default.Tune) { nav.navigate(Routes.adjust()) }
            SectionTitle("Insights", Modifier.padding(horizontal = 16.dp))
            MoreRow("Sales by item & carrier spend", Icons.Default.BarChart) { nav.navigate(Routes.REPORTS) }
            MoreRow("Copilot", Icons.Default.SmartToy) { nav.navigate(Routes.ASSISTANT) }
            SectionTitle("Account", Modifier.padding(horizontal = 16.dp))
            MoreRow("Settings & sign out", Icons.Default.Settings) { nav.navigate(Routes.SETTINGS) }
        }
    }
}

@Composable
private fun MoreRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
    RowDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppContainer.Graph, nav: NavHostController, container: AppContainer) {
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { graph.auth.me() }.onSuccess { profile = it }.onFailure { error = it.userMessage() } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            SectionTitle("Signed in as")
            KeyValueRow("Name", profile?.displayName)
            KeyValueRow("Email", profile?.email ?: container.session.value?.email)
            KeyValueRow("Role", profile?.groups?.joinToString { it.name }?.ifBlank { null } ?: if (profile?.isSuperuser == true) "Superuser" else null)
            KeyValueRow("Workspace", graph.tenant)
            KeyValueRow("API", BuildConfig.API_ROOT)
            KeyValueRow("App version", BuildConfig.VERSION_NAME)
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { container.switchWorkspace() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.SwapHoriz, null); Spacer(Modifier.width(8.dp)); Text("Switch workspace") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out") }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("You'll need your email and password to sign back in.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; container.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}
