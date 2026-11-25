package com.sumaho.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sumaho.agent.agent.Decision
import com.sumaho.agent.llm.ChatMessage
import com.sumaho.agent.llm.Role
import com.sumaho.agent.tools.Risk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val conv by vm.conv.collectAsState()
    val streamText by vm.streamText.collectAsState()
    val streamReasoning by vm.streamReasoning.collectAsState()
    val status by vm.status.collectAsState()
    val running by vm.running.collectAsState()
    val input by vm.input.collectAsState()
    val pending by vm.pendingConfirm.collectAsState()
    val notice by vm.notice.collectAsState()
    val settings by vm.app.settings.state.collectAsState()
    val history by vm.history.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.notice.value = null }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(conv.messages.size, streamText.length / 200, running) {
        val count = conv.messages.size + if (running) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.attach(it) }
    }

    var endpointMenu by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(conv.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
                        Box {
                            Text(
                                "推論先: ${vm.endpointLabel()} ▾",
                                fontSize = 12.sp,
                                color = if (vm.isCloudSelected()) Color(0xFFE65100) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(enabled = !running) { endpointMenu = true },
                            )
                            DropdownMenu(expanded = endpointMenu, onDismissRequest = { endpointMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("自動（ローカル優先）") },
                                    onClick = { vm.setOverride(null); endpointMenu = false },
                                )
                                settings.endpoints.filter { it.enabled }.forEach { ep ->
                                    DropdownMenuItem(
                                        text = { Text((if (ep.cloud) "☁ " else "") + ep.name + "  " + ep.model) },
                                        onClick = { vm.setOverride(ep.id); endpointMenu = false },
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { vm.newConversation() }, enabled = !running) { Text("新規") }
                    TextButton(onClick = { showHistory = true }, enabled = !running) { Text("履歴") }
                    TextButton(onClick = onOpenSettings) { Text("設定") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (vm.isCloudSelected()) {
                Text(
                    "☁ この会話はクラウドに送信されます（ツール結果を含む）",
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE65100))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(conv.messages) { m -> MessageItem(m) }
                if (running) {
                    item { StreamingItem(streamReasoning, streamText, status) }
                }
            }
            HorizontalDivider()
            InputBar(
                text = input,
                running = running,
                onText = { vm.input.value = it },
                onSend = { vm.send() },
                onConsult = { vm.send(consult = true) },
                onStop = { vm.stop() },
                onAttach = { picker.launch(arrayOf("application/pdf", "text/*")) },
                consultName = settings.endpoint(settings.consult.endpointId)?.name ?: "外部",
            )
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(p.request.title) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("ツール: ${p.request.toolName}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.padding(4.dp))
                    SelectionContainer { Text(p.request.detail, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Row {
                    if (p.request.risk != Risk.DEVICE) {
                        TextButton(onClick = { vm.answerConfirm(Decision.ALLOW_ALWAYS) }) { Text("今後も許可") }
                    }
                    Button(onClick = { vm.answerConfirm(Decision.ALLOW) }) { Text("許可") }
                }
            },
            dismissButton = { TextButton(onClick = { vm.answerConfirm(Decision.DENY) }) { Text("拒否") } },
        )
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("会話の履歴") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    if (history.isEmpty()) Text("まだありません")
                    history.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { vm.open(c.id); showHistory = false }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(fmtTime(c.updatedAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                            }
                            TextButton(onClick = { vm.deleteConversation(c.id) }) { Text("削除") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("閉じる") } },
        )
    }
}

private fun fmtTime(t: Long) = SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(t))

@Composable
private fun MessageItem(m: ChatMessage) {
    when (m.role) {
        Role.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = if (m.cloud) Color(0xFFFFE0B2) else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 560.dp),
            ) {
                SelectionContainer {
                    Text(m.text, modifier = Modifier.padding(10.dp), color = if (m.cloud) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Role.ASSISTANT -> Column(Modifier.fillMaxWidth()) {
            m.via?.let {
                Text((if (m.cloud) "☁ " else "") + it, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
            m.reasoning?.let { Collapsible("思考", it) }
            if (m.text.isNotBlank()) SelectionContainer { Text(m.text) }
            m.toolCalls.forEach { c ->
                Text(
                    "🔧 ${c.name} ${c.argumentsJson.take(160)}",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Role.TOOL -> Column(Modifier.fillMaxWidth()) {
            m.toolResults.forEach { r ->
                Collapsible((if (r.isError) "✗ " else "✓ ") + r.name, r.content, error = r.isError)
            }
        }
    }
}

@Composable
private fun Collapsible(label: String, body: String, error: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { open = !open },
    ) {
        Column(Modifier.padding(8.dp)) {
            Text((if (open) "▾ " else "▸ ") + label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (open) {
                SelectionContainer {
                    Text(body.take(20000), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                Text(body.replace("\n", " ").take(100), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun StreamingItem(reasoning: String, text: String, status: String?) {
    Column(Modifier.fillMaxWidth()) {
        status?.let { Text("⏳ $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
        if (reasoning.isNotEmpty()) {
            Text(
                "思考中: " + reasoning.takeLast(400),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
            )
        }
        if (text.isNotEmpty()) Text(text)
    }
}

@Composable
private fun InputBar(
    text: String,
    running: Boolean,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onConsult: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    consultName: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextButton(onClick = onAttach, enabled = !running) { Text("📎", fontSize = 20.sp) }
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.weight(1f),
            placeholder = { Text("メッセージ") },
            maxLines = 6,
        )
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (running) {
                Button(onClick = onStop) { Text("停止") }
            } else {
                Button(onClick = onSend, enabled = text.isNotBlank()) { Text("送信") }
                OutlinedButton(onClick = onConsult) { Text("☁ ${consultName}に聞く", fontSize = 11.sp) }
            }
        }
    }
}
