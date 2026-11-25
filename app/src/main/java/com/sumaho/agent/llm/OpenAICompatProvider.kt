package com.sumaho.agent.llm

import com.sumaho.agent.core.Http
import com.sumaho.agent.core.executeCancellable
import com.sumaho.agent.settings.Endpoint
import com.sumaho.agent.settings.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * OpenAI Chat Completions 互換（llama-server / LocalAI / BigMoeOnEdge / OpenAI）。
 * ストリーミング(SSE)で受け取り、テキスト・思考(reasoning_content)・ツール呼び出しを組み立てる。
 */
class OpenAICompatProvider(
    private val endpoint: Endpoint,
    private val apiKey: String?,
) : LlmProvider {

    private val isOpenAICloud get() = endpoint.cloud && endpoint.baseUrl.contains("api.openai.com")

    override suspend fun complete(request: LlmRequest, sink: StreamSink): LlmResult {
        if (endpoint.model.isBlank()) throw LlmException("「${endpoint.name}」のモデル名が未設定です")
        val body = buildBody(request)
        val url = endpoint.baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(Http.JSON))
            .header("Accept", "text/event-stream")
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .build()

        val resp = try {
            Http.client.executeCancellable(req)
        } catch (e: java.io.IOException) {
            throw LlmException("「${endpoint.name}」に接続できません: ${e.message}", e)
        }
        return withContext(Dispatchers.IO) {
            resp.use { r ->
                if (!r.isSuccessful) {
                    val err = r.body?.string()?.take(1500)
                    throw LlmException("「${endpoint.name}」エラー HTTP ${r.code}: $err")
                }
                val ctype = r.header("Content-Type").orEmpty()
                if (ctype.contains("application/json")) {
                    // stream 非対応サーバー
                    parseNonStream(r.body!!.string(), sink)
                } else {
                    parseStream(r, sink)
                }
            }
        }
    }

    private fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", endpoint.model)
        put("stream", true)
        if (isOpenAICloud) put("max_completion_tokens", request.maxTokens)
        else put("max_tokens", request.maxTokens)
        if (endpoint.localReasoningEffort.isNotBlank() && !endpoint.cloud) {
            putJsonObject("chat_template_kwargs") { put("reasoning_effort", endpoint.localReasoningEffort) }
        }
        if (endpoint.effort.isNotBlank() && isOpenAICloud) put("reasoning_effort", endpoint.effort)
        put("messages", buildMessages(request))
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        }
                    })
                }
            }
        }
    }

    private fun buildMessages(request: LlmRequest): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("role", "system")
            put("content", request.system)
        })
        for (m in request.messages) {
            when (m.role) {
                Role.USER -> add(buildJsonObject {
                    put("role", "user")
                    put("content", m.text)
                })
                Role.ASSISTANT -> add(buildJsonObject {
                    put("role", "assistant")
                    if (m.text.isNotEmpty() || m.toolCalls.isEmpty()) put("content", m.text) else put("content", JsonNull)
                    if (m.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            m.toolCalls.forEach { c ->
                                add(buildJsonObject {
                                    put("id", c.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", c.name)
                                        put("arguments", c.argumentsJson)
                                    }
                                })
                            }
                        }
                    }
                })
                Role.TOOL -> m.toolResults.forEach { r ->
                    add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", r.callId)
                        put("content", if (r.isError) "ERROR: ${r.content}" else r.content)
                    })
                }
            }
        }
    }

    private class PartialCall(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private suspend fun parseStream(r: okhttp3.Response, sink: StreamSink): LlmResult {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = sortedMapOf<Int, PartialCall>()
        var finish: String? = null
        val source = r.body!!.source()
        while (true) {
            coroutineContext.ensureActive()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isEmpty()) continue
            val obj = runCatching { AppJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            obj["error"]?.let { throw LlmException("「${endpoint.name}」エラー: $it") }
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: continue
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { finish = it }
            val delta = choice["delta"] as? JsonObject ?: continue
            (delta["content"] as? JsonPrimitive)?.contentOrNull?.let {
                text.append(it); sink.onText(it)
            }
            val rc = (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                ?: (delta["reasoning"] as? JsonPrimitive)?.contentOrNull
            rc?.let { reasoning.append(it); sink.onReasoning(it) }
            (delta["tool_calls"] as? JsonArray)?.forEach { tcEl ->
                val tc = tcEl.jsonObject
                val idx = tc["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: calls.size
                val pc = calls.getOrPut(idx) { PartialCall() }
                (tc["id"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotEmpty()) pc.id = it }
                val fn = tc["function"] as? JsonObject
                (fn?.get("name") as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotEmpty()) pc.name = it }
                (fn?.get("arguments") as? JsonPrimitive)?.contentOrNull?.let { pc.args.append(it) }
            }
        }
        return finishResult(text.toString(), reasoning.toString(), calls.values.toList(), finish)
    }

    private fun parseNonStream(body: String, sink: StreamSink): LlmResult {
        val obj = AppJson.parseToJsonElement(body).jsonObject
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw LlmException("「${endpoint.name}」から不正な応答: ${body.take(500)}")
        val msg = choice["message"]!!.jsonObject
        val text = (msg["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val reasoning = (msg["reasoning_content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        sink.onReasoning(reasoning); sink.onText(text)
        val calls = (msg["tool_calls"] as? JsonArray)?.map { el ->
            val o = el.jsonObject
            val fn = o["function"]!!.jsonObject
            PartialCall(
                id = (o["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                name = fn["name"]!!.jsonPrimitive.content,
            ).also { it.args.append((fn["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}") }
        } ?: emptyList()
        return finishResult(text, reasoning, calls, (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull)
    }

    private fun finishResult(text: String, reasoning: String, calls: List<PartialCall>, finish: String?): LlmResult {
        val toolCalls = calls.filter { it.name.isNotBlank() }.map {
            ToolCall(
                id = it.id.ifBlank { "call_" + UUID.randomUUID().toString().replace("-", "").take(16) },
                name = it.name,
                argumentsJson = it.args.toString().ifBlank { "{}" },
            )
        }
        return LlmResult(
            message = ChatMessage(
                role = Role.ASSISTANT,
                text = text.trim(),
                reasoning = reasoning.trim().ifEmpty { null },
                toolCalls = toolCalls,
                providerKind = ProviderKind.OPENAI_COMPAT.name,
                via = endpoint.name,
                cloud = endpoint.cloud,
            ),
            stopReason = finish,
        )
    }
}
