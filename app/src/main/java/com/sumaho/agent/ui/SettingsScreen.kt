package com.sumaho.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sumaho.agent.AppContainer
import com.sumaho.agent.data.newId
import com.sumaho.agent.settings.AppSettings
import com.sumaho.agent.settings.Endpoint
import com.sumaho.agent.settings.McpServerConfig
import com.sumaho.agent.settings.ProviderKind
import com.sumaho.agent.settings.SearchKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AppContainer, onBack: () -> Unit) {
    val s by app.settings.state.collectAsState()
    val mcpStates by app.mcp.states.collectAsState()
    var memoryVersion by remember { mutableIntStateOf(0) }

    fun update(t: (AppSettings) -> AppSettings) {
        app.settings.update(t)
        app.router.invalidateHealth()
    }

    fun updateEndpoint(id: String, t: (Endpoint) -> Endpoint) =
        update { st -> st.copy(endpoints = st.endpoints.map { if (it.id == id) t(it) else it }) }

    fun updateMcp(id: String, t: (McpServerConfig) -> McpServerConfig) =
        update { st -> st.copy(mcpServers = st.mcpServers.map { if (it.id == id) t(it) else it }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← 戻る") } },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------- スマホ内エンジン ----------------
            EngineSection(app, s) { t -> update(t) }

            // ---------------- 推論先 ----------------
            SectionTitle("推論先")
            s.endpoints.forEach { ep ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SwitchRow((if (ep.cloud) "☁ " else "") + ep.name, ep.enabled) { v -> updateEndpoint(ep.id) { it.copy(enabled = v) } }
                        if (ep.enabled) {
                            TextSetting("URL", ep.baseUrl) { v -> updateEndpoint(ep.id) { it.copy(baseUrl = v.trim()) } }
                            TextSetting("モデル名", ep.model) { v -> updateEndpoint(ep.id) { it.copy(model = v.trim()) } }
                            SecretSetting(app, if (ep.cloud) "APIキー" else "APIキー（必要な場合のみ）", ep.secretKey)
                            if (!ep.cloud && ep.kind == ProviderKind.OPENAI_COMPAT) {
                                Choice(
                                    "reasoning_effort（Inkling向け）",
                                    ep.localReasoningEffort,
                                    listOf("", "none", "minimal", "low", "medium", "high"),
                                ) { v -> updateEndpoint(ep.id) { it.copy(localReasoningEffort = v) } }
                            }
                            if (ep.cloud && ep.kind != ProviderKind.GEMINI) {
                                Choice("effort", ep.effort, listOf("", "low", "medium", "high", "xhigh", "max")) { v ->
                                    updateEndpoint(ep.id) { it.copy(effort = v) }
                                }
                            }
                            NumberSetting("最大出力トークン", ep.maxTokens) { v -> updateEndpoint(ep.id) { it.copy(maxTokens = v) } }
                        }
                    }
                }
            }

            // ---------------- ルーティング ----------------
            SectionTitle("自動ルーティング")
            Hint("クラウドは自動では使いません。会話ごとに画面上部の「推論先」から明示的に選んだときだけ送信されます。")
            val locals = s.endpoints.filter { !it.cloud }
            Choice("優先", s.primaryEndpointId, locals.map { it.id }, labels = locals.associate { it.id to it.name }) { v ->
                update { it.copy(primaryEndpointId = v) }
            }
            Choice("繋がらないとき", s.fallbackEndpointId, locals.map { it.id }, labels = locals.associate { it.id to it.name }) { v ->
                update { it.copy(fallbackEndpointId = v) }
            }
            NumberSetting("1回の依頼での最大ステップ数", s.maxAgentSteps) { v -> update { it.copy(maxAgentSteps = v.coerceIn(1, 50)) } }

            // ---------------- 外部への相談 ----------------
            SectionTitle("外部への相談（consult_external）")
            Hint("ローカルモデルが難しいと判断したとき、質問と前提だけを外部AIに送り、回答を受け取って推論を続けます。会話履歴そのものは送りません。本日の使用: ${app.consult.usedToday()}回")
            val consultTargets = s.endpoints
            Choice("相談先", s.consult.endpointId, consultTargets.map { it.id }, labels = consultTargets.associate { it.id to it.name }) { v ->
                update { it.copy(consult = it.consult.copy(endpointId = v)) }
            }
            SwitchRow("送信前に毎回確認する", s.consult.confirmEachTime) { v -> update { it.copy(consult = it.consult.copy(confirmEachTime = v)) } }
            SwitchRow("ツール失敗が続いたら相談を促す", s.consult.hintOnRepeatedErrors) { v ->
                update { it.copy(consult = it.consult.copy(hintOnRepeatedErrors = v)) }
            }
            NumberSetting("1日の上限回数", s.consult.dailyLimit) { v -> update { it.copy(consult = it.consult.copy(dailyLimit = v)) } }
            NumberSetting("回答の目安トークン", s.consult.maxTokens) { v -> update { it.copy(consult = it.consult.copy(maxTokens = v)) } }

            // ---------------- Web検索 ----------------
            SectionTitle("Web検索")
            Choice("検索エンジン", s.search.kind.name, SearchKind.entries.map { it.name },
                labels = mapOf("NONE" to "なし", "SEARXNG" to "SearXNG（自宅など）", "BRAVE" to "Brave Search API", "TAVILY" to "Tavily")) { v ->
                update { it.copy(search = it.search.copy(kind = SearchKind.valueOf(v))) }
            }
            when (s.search.kind) {
                SearchKind.SEARXNG -> {
                    TextSetting("SearXNG のURL", s.search.searxngUrl) { v -> update { it.copy(search = it.search.copy(searxngUrl = v.trim())) } }
                    Hint("SearXNG 側の settings.yml で formats に json を追加してください。")
                }
                SearchKind.BRAVE -> SecretSetting(app, "Brave APIキー", "key:brave")
                SearchKind.TAVILY -> SecretSetting(app, "Tavily APIキー", "key:tavily")
                SearchKind.NONE -> {}
            }

            // ---------------- MCP ----------------
            SectionTitle("MCPサーバー（Streamable HTTP）")
            Hint("ツール定義は毎回プロンプトに含まれるため、スマホ内推論では必要なサーバーだけ有効にしてください。")
            s.mcpServers.forEach { srv ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val st = mcpStates[srv.id]
                        SwitchRow(srv.name, srv.enabled) { v -> updateMcp(srv.id) { it.copy(enabled = v) } }
                        st?.let {
                            Text(
                                if (it.ok) "接続OK・ツール${it.toolCount}個" else "接続失敗: ${it.error}",
                                fontSize = 12.sp, color = if (it.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            )
                        }
                        TextSetting("名前", srv.name) { v -> updateMcp(srv.id) { it.copy(name = v) } }
                        TextSetting("URL", srv.url) { v -> updateMcp(srv.id) { it.copy(url = v.trim()) } }
                        TextSetting("認証ヘッダー名", srv.authHeader) { v -> updateMcp(srv.id) { it.copy(authHeader = v.trim()) } }
                        SecretSetting(app, "トークン（不要なら空）", srv.secretKey)
                        SwitchRow("確認なしで実行（信頼できるサーバーのみ）", srv.autoApprove) { v -> updateMcp(srv.id) { it.copy(autoApprove = v) } }
                        TextButton(onClick = {
                            update { st2 -> st2.copy(mcpServers = st2.mcpServers.filterNot { it.id == srv.id }) }
                            app.secrets.put(srv.secretKey, null)
                            app.refreshMcp()
                        }) { Text("このサーバーを削除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    update { it.copy(mcpServers = it.mcpServers + McpServerConfig(id = newId(), name = "server${it.mcpServers.size + 1}", url = "")) }
                }) { Text("＋ サーバー追加") }
                OutlinedButton(onClick = { app.refreshMcp() }) { Text("再接続") }
            }

            // ---------------- ツール ----------------
            SectionTitle("組み込みツール")
            app.builtinTools().forEach { t ->
                SwitchRow("${t.name}（${riskLabel(t.risk.name)}）", s.toolEnabled[t.name] ?: true) { v ->
                    update { it.copy(toolEnabled = it.toolEnabled + (t.name to v)) }
                }
            }

            // ---------------- 長期記憶 ----------------
            SectionTitle("長期記憶")
            val mem = remember(memoryVersion) { app.notes.memory() }
            if (mem.isEmpty()) Hint("まだありません")
            mem.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.text, Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { app.notes.forget(item.id); memoryVersion++ }) { Text("削除") }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun EngineSection(app: AppContainer, s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val em = app.engineManager
    val st by em.state.collectAsState()
    var models by remember { mutableStateOf(em.availableModels().map { it.name }) }
    SectionTitle("スマホ内エンジン（BigMoeOnEdge 内蔵）")
    if (!em.isBundled()) {
        Hint("このビルド／端末ではエンジンを使えません（arm64 の実機のみ）")
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val color = when (st.phase) {
                com.sumaho.agent.engine.EngineManager.Phase.READY -> Color(0xFF2E7D32)
                com.sumaho.agent.engine.EngineManager.Phase.ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            Text(
                "状態: ${st.message.ifEmpty { "停止" }}" +
                    (st.elapsedS?.let { "（${it.toInt()}秒）" } ?: "") +
                    (st.modelName?.let { "\nモデル: $it" } ?: ""),
                color = color, fontSize = 13.sp,
            )
            Hint("モデル置き場: ${em.modelsDir.absolutePath}")
            if (models.isEmpty()) {
                Hint("（.gguf がありません。PCから adb push でここに置いてください）")
            } else {
                Choice("モデル", s.engine.modelFile.ifEmpty { models.first() }, models) { v ->
                    update { it.copy(engine = it.engine.copy(modelFile = v)) }
                }
            }
            NumberSetting("コンテキスト長", s.engine.ctxSize) { v -> update { it.copy(engine = it.engine.copy(ctxSize = v)) } }
            SwitchRow("必要なとき自動で起動", s.engine.autoStartOnDemand) { v ->
                update { it.copy(engine = it.engine.copy(autoStartOnDemand = v)) }
            }
            SwitchRow("読み込みをNPUで行う（再起動で反映）", s.engine.npuPrefill) { v ->
                update { it.copy(engine = it.engine.copy(npuPrefill = v)) }
            }
            TextSetting("追加引数（上級者向け）", s.engine.extraArgs) { v -> update { it.copy(engine = it.engine.copy(extraArgs = v)) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (em.isRunning) {
                    OutlinedButton(onClick = { em.stop() }) { Text("停止") }
                } else {
                    OutlinedButton(onClick = { em.start() }) { Text("起動") }
                }
                OutlinedButton(onClick = { models = em.availableModels().map { it.name } }) { Text("モデル再検索") }
            }
            if (st.log.isNotBlank()) {
                var showLog by remember { mutableStateOf(false) }
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "ログを隠す" else "ログを表示") }
                if (showLog) Text(st.log.takeLast(2500), fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

private fun riskLabel(r: String) = when (r) {
    "READ" -> "自動"
    "WRITE" -> "確認あり"
    "EXTERNAL" -> "外部送信"
    else -> "端末操作"
}

@Composable
private fun SectionTitle(t: String) =
    Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Hint(t: String) = Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun TextSetting(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberSetting(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v.filter { it.isDigit() }; text.toIntOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecretSetting(app: AppContainer, label: String, secretName: String) {
    var has by remember(secretName) { mutableStateOf(app.secrets.has(secretName)) }
    var text by remember(secretName) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text(label + if (has) "（設定済み）" else "（未設定）") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        if (text.isNotBlank()) {
            TextButton(onClick = { app.secrets.put(secretName, text); text = ""; has = true }) { Text("保存") }
        } else if (has) {
            TextButton(onClick = { app.secrets.put(secretName, null); has = false }) { Text("消去") }
        }
    }
}

@Composable
private fun Choice(
    label: String,
    value: String,
    options: List<String>,
    labels: Map<String, String> = emptyMap(),
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun show(v: String) = labels[v] ?: v.ifEmpty { "（送らない）" }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(show(value) + " ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(show(o)) }, onClick = { onChange(o); open = false })
                }
            }
        }
    }
}
