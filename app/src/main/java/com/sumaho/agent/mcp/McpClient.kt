package com.sumaho.agent.mcp

import com.sumaho.agent.core.Http
import com.sumaho.agent.core.executeCancellable
import com.sumaho.agent.llm.AppJson
import com.sumaho.agent.settings.McpServerConfig
import com.sumaho.agent.settings.SecretStore
import com.sumaho.agent.tools.AgentTool
import com.sumaho.agent.tools.Risk
import com.sumaho.agent.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP クライアント（Streamable HTTP トランスポート）。
 * JSON-RPC を POST し、応答は application/json か text/event-stream(SSE) のどちらでも受け取る。
 */
class McpClient(
    private val config: McpServerConfig,
    private val token: String?,
) {
    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
    }

    private val ids = AtomicLong(1)
    private var sessionId: String? = null
    private var negotiatedVersion: String = PROTOCOL_VERSION
    private var initialized = false
    private val mutex = Mutex()

    data class RemoteTool(val name: String, val description: String, val inputSchema: JsonObject, val readOnlyHint: Boolean)

    private suspend fun post(payload: JsonObject, expectResponse: Boolean): JsonObject? {
        val rb = Request.Builder()
            .url(config.url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .post(payload.toString().toRequestBody(Http.JSON))
        if (initialized) rb.header("MCP-Protocol-Version", negotiatedVersion)
        sessionId?.let { rb.header("Mcp-Session-Id", it) }
        if (!token.isNullOrBlank()) {
            val value = if (config.authHeader.equals("Authorization", true) && !token.startsWith("Bearer ")) "Bearer $token" else token
            rb.header(config.authHeader, value)
        }
        val resp = Http.client.executeCancellable(rb.build())
        return withContext(Dispatchers.IO) {
            resp.use { r ->
                r.header("Mcp-Session-Id")?.let { sessionId = it }
                if (r.code == 404 && sessionId != null) throw SessionExpired()
                if (r.code == 401 || r.code == 403) throw ToolException("MCP「${config.name}」認証エラー (HTTP ${r.code})")
                if (!r.isSuccessful) throw ToolException("MCP「${config.name}」HTTP ${r.code}: ${r.body?.string()?.take(300)}")
                if (!expectResponse) return@use null
                val reqId = payload["id"]
                val ctype = r.header("Content-Type").orEmpty()
                if (ctype.contains("text/event-stream")) {
                    val src = r.body!!.source()
                    val data = StringBuilder()
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        when {
                            line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                            line.isEmpty() && data.isNotEmpty() -> {
                                val msg = runCatching { AppJson.parseToJsonElement(data.toString()).jsonObject }.getOrNull()
                                data.clear()
                                if (msg != null && msg["id"] == reqId && (msg.containsKey("result") || msg.containsKey("error"))) {
                                    return@use msg
                                }
                            }
                        }
                    }
                    if (data.isNotEmpty()) AppJson.parseToJsonElement(data.toString()).jsonObject
                    else throw ToolException("MCP「${config.name}」から応答がありません")
                } else {
                    val body = r.body?.string().orEmpty()
                    val el = AppJson.parseToJsonElement(body)
                    if (el is JsonArray) el.map { it.jsonObject }.first { it["id"] == reqId } else el.jsonObject
                }
            }
        }
    }

    private class SessionExpired : Exception()

    private suspend fun request(method: String, params: JsonObject? = null): JsonElement {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ids.getAndIncrement())
            put("method", method)
            if (params != null) put("params", params)
        }
        val msg = post(payload, expectResponse = true)!!
        (msg["error"] as? JsonObject)?.let {
            throw ToolException("MCP「${config.name}」エラー: ${(it["message"] as? JsonPrimitive)?.contentOrNull ?: it}")
        }
        return msg["result"] ?: JsonObject(emptyMap())
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        sessionId = null
        val result = request("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") { }
            putJsonObject("clientInfo") { put("name", "SumahoAgent"); put("version", "0.1.0") }
        }).jsonObject
        (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull?.let { negotiatedVersion = it }
        initialized = true
        post(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }, expectResponse = false)
    }

    /** セッション切れ(404)なら1回だけ再初期化して再試行 */
    private suspend fun <T> withSession(block: suspend () -> T): T = mutex.withLock {
        ensureInitialized()
        try {
            block()
        } catch (e: SessionExpired) {
            initialized = false
            ensureInitialized()
            block()
        }
    }

    suspend fun listTools(): List<RemoteTool> = withSession {
        val out = mutableListOf<RemoteTool>()
        var cursor: String? = null
        do {
            val res = request("tools/list", cursor?.let { c -> buildJsonObject { put("cursor", c) } }).jsonObject
            (res["tools"] as? JsonArray)?.forEach { tEl ->
                val t = tEl.jsonObject
                val ann = t["annotations"] as? JsonObject
                out += RemoteTool(
                    name = (t["name"] as JsonPrimitive).content,
                    description = (t["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    inputSchema = (t["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object") },
                    readOnlyHint = (ann?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
            cursor = (res["nextCursor"] as? JsonPrimitive)?.contentOrNull
        } while (cursor != null)
        out
    }

    suspend fun callTool(name: String, arguments: JsonObject): Pair<String, Boolean> = withSession {
        val res = request("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }).jsonObject
        val isError = (res["isError"] as? JsonPrimitive)?.booleanOrNull == true
        val parts = (res["content"] as? JsonArray)?.map { cEl ->
            val c = cEl.jsonObject
            when ((c["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (c["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                "image" -> "[画像: ${(c["mimeType"] as? JsonPrimitive)?.contentOrNull}（このモデルでは表示不可）]"
                "audio" -> "[音声データ（省略）]"
                "resource" -> {
                    val r = c["resource"] as? JsonObject
                    (r?.get("text") as? JsonPrimitive)?.contentOrNull
                        ?: "[リソース: ${(r?.get("uri") as? JsonPrimitive)?.contentOrNull}]"
                }
                "resource_link" -> "[リンク: ${(c["uri"] as? JsonPrimitive)?.contentOrNull}]"
                else -> c.toString()
            }
        } ?: emptyList()
        val text = parts.joinToString("\n").ifBlank { res["structuredContent"]?.toString() ?: "(結果なし)" }
        text to isError
    }
}

/** MCP サーバーのツールを AgentTool として包む */
class McpTool(
    val serverId: String,
    private val serverName: String,
    private val client: McpClient,
    private val remote: McpClient.RemoteTool,
    autoApprove: Boolean,
) : AgentTool {
    override val name: String = mcpToolName(serverName, remote.name)
    override val description: String = "[MCP:$serverName] ${remote.description}".take(1024)
    override val parameters: JsonObject = remote.inputSchema
    /** 自動承認のサーバーは READ 扱い、それ以外は毎回確認 */
    override val risk: Risk = if (autoApprove) Risk.READ else Risk.WRITE

    override fun preview(args: JsonObject) = "MCPサーバー「$serverName」のツール ${remote.name}\n\n$args"

    override suspend fun run(args: JsonObject): String {
        val (text, isError) = client.callTool(remote.name, args)
        if (isError) throw ToolException(text.take(4000))
        return text.take(16000)
    }
}

fun mcpToolName(server: String, tool: String): String {
    val s = server.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "srv" }.take(16)
    val t = tool.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return "mcp_${s}__$t".take(64)
}

/** 有効な MCP サーバーへの接続とツール一覧を管理 */
class McpManager(private val secrets: SecretStore) {
    data class ServerState(val id: String, val name: String, val ok: Boolean, val toolCount: Int, val error: String?)

    private val _states = MutableStateFlow<Map<String, ServerState>>(emptyMap())
    val states: StateFlow<Map<String, ServerState>> = _states
    @Volatile private var tools: List<McpTool> = emptyList()

    fun tools(): List<McpTool> = tools

    suspend fun refresh(servers: List<McpServerConfig>) {
        val newTools = mutableListOf<McpTool>()
        val st = mutableMapOf<String, ServerState>()
        for (cfg in servers.filter { it.enabled && it.url.isNotBlank() }) {
            val client = McpClient(cfg, secrets.get(cfg.secretKey))
            try {
                val list = client.listTools()
                list.forEach { newTools += McpTool(cfg.id, cfg.name, client, it, cfg.autoApprove) }
                st[cfg.id] = ServerState(cfg.id, cfg.name, true, list.size, null)
            } catch (e: Exception) {
                st[cfg.id] = ServerState(cfg.id, cfg.name, false, 0, e.message ?: e.toString())
            }
        }
        tools = newTools
        _states.value = st
    }
}
