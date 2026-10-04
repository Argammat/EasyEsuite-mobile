package com.easyesuite.app.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.common.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(val role: String, val text: String, val suggestions: List<String> = emptyList(), val error: Boolean = false)

data class AssistantUi(
    val messages: List<ChatMessage> = listOf(
        ChatMessage("assistant", "Hi — I'm the EasyEsuite Copilot. Ask me about orders, stock, sales or shipping, e.g. “How many orders came in today?” or “What's low on stock in Sunvalley?”",
            suggestions = listOf("Orders today by marketplace", "Items below reorder point", "Top sellers this week", "Shipments on hold")),
    ),
    val input: String = "",
    val threadId: String? = null,
    val busy: Boolean = false,
)

class AssistantViewModel(private val graph: AppContainer.Graph) : ViewModel() {
    val ui = MutableStateFlow(AssistantUi())

    fun setInput(s: String) = ui.update { it.copy(input = s) }
    fun reset() { ui.value = AssistantUi() }

    fun send(text: String = ui.value.input) {
        val msg = text.trim()
        if (msg.isEmpty() || ui.value.busy) return
        ui.update { it.copy(messages = it.messages + ChatMessage("user", msg), input = "", busy = true) }
        viewModelScope.launch {
            try {
                val reply = graph.assistant.ask(msg, ui.value.threadId)
                ui.update { it.copy(messages = it.messages + ChatMessage("assistant", reply.text, reply.suggestions), threadId = reply.threadId, busy = false) }
            } catch (e: Exception) {
                ui.update { it.copy(messages = it.messages + ChatMessage("assistant", e.userMessage(), error = true), busy = false) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(graph: AppContainer.Graph, nav: NavHostController) {
    val vm: AssistantViewModel = viewModel { AssistantViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(ui.messages.size, ui.busy) {
        val last = ui.messages.lastIndex + (if (ui.busy) 1 else 0)
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Copilot") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = vm::reset) { Icon(Icons.Default.Refresh, contentDescription = "New conversation") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ui.messages.size) { i ->
                    val m = ui.messages[i]
                    val mine = m.role == "user"
                    Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(14.dp))
                                .background(
                                    when {
                                        m.error -> MaterialTheme.colorScheme.errorContainer
                                        mine -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ).padding(12.dp),
                        ) {
                            Text(m.text, color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (m.suggestions.isNotEmpty() && i == ui.messages.lastIndex) {
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(m.suggestions.size) { j -> AssistChip(onClick = { vm.send(m.suggestions[j]) }, label = { Text(m.suggestions[j]) }) }
                            }
                        }
                    }
                }
                if (ui.busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = ui.input, onValueChange = vm::setInput, placeholder = { Text("Ask about your business…") }, modifier = Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(20.dp))
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { vm.send() }, enabled = ui.input.isNotBlank() && !ui.busy) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary) }
            }
            Text("Copilot answers come from your ERP data via ai/ai_copilot_agent_v2.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}
