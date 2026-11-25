package com.sumaho.agent.llm

import com.sumaho.agent.core.Http
import com.sumaho.agent.core.executeCancellable
import com.sumaho.agent.settings.Endpoint
import com.sumaho.agent.settings.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Gemini（Google AI Studio, generateContent REST）。
 * モデル応答の content（thoughtSignature を含む）は raw に保持し、次のリクエストでそのまま返す。
 */
class GeminiProvider(
    private val endpoint: Endpoint,
    private val apiKey: String?,
) : LlmProvider {

    override suspend fun complete(request: LlmRequest, sink: StreamSink): LlmResult {
        if (endpoint.model.isBlank()) throw LlmException("Gemini のモデル名が未設定です")
        val key = apiKey?.takeIf { it.isNotBlank() } ?: throw LlmException("Gemini の APIキーが未設定です")
        val url = endpoint.baseUrl.trimEnd('/') + "/models/${endpoint.model}:generateContent"
        val body = buildBody(request)
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        val resp = try {
            Http.client.executeCancellable(req)
        } catch (e: java.io.IOException) {
            throw LlmException("Gemini に接続できません: ${e.message}", e)
        }
        val text = withContext(Dispatchers.IO) { resp.use { it.body?.string().orEmpty() } }
        if (!resp.isSuccessful) throw LlmException("Gemini エラー HTTP ${resp.code}: ${text.take(1500)}")
        return parse(text, sink)
    }

    private fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", request.system) } }
        }
        put("contents", buildContents(request.messages))
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        request.tools.forEach { t ->
                            addJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("parametersJsonSchema", t.parameters)
                            }
                        }
                    }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("maxOutputTokens", request.maxTokens)
            putJsonObject("thinkingConfig") { put("includeThoughts", true) }
        }
    }

    private fun buildContents(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        for (m in messages) {
            when (m.role) {
                Role.USER -> addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", m.text.ifEmpty { " " }) } }
                }
                Role.ASSISTANT -> {
                    val raw = m.raw
                    if (raw != null && m.providerKind == ProviderKind.GEMINI.name) {
                        add(AppJson.parseToJsonElement(raw))
                    } else {
                        addJsonObject {
                            put("role", "model")
                            putJsonArray("parts") {
                                if (m.text.isNotBlank()) addJsonObject { put("text", m.text) }
                                m.toolCalls.forEach { c ->
                                    addJsonObject {
                                        putJsonObject("functionCall") {
                                            put("name", c.name)
                                            put("args", runCatching { AppJson.parseToJsonElement(c.argumentsJson) }
                                                .getOrDefault(JsonObject(emptyMap())))
                                        }
                                        // 他の推論先が作った関数呼び出しには署名が無いので検証をスキップさせる
                                        put("thoughtSignature", "skip_thought_signature_validator")
                                    }
                                }
                                if (m.text.isBlank() && m.toolCalls.isEmpty()) addJsonObject { put("text", "（空の応答）") }
                            }
                        }
                    }
                }
                Role.TOOL -> addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        m.toolResults.forEach { r ->
                            addJsonObject {
                                putJsonObject("functionResponse") {
                                    put("name", r.name)
                                    putJsonObject("response") {
                                        put(if (r.isError) "error" else "content", r.content)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parse(body: String, sink: StreamSink): LlmResult {
        val obj = AppJson.parseToJsonElement(body).jsonObject
        val cand = (obj["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
        val content = cand?.get("content") as? JsonObject
        val finish = (cand?.get("finishReason") as? JsonPrimitive)?.contentOrNull
        if (content == null) {
            val block = obj["promptFeedback"]?.toString()
            return LlmResult(
                message = ChatMessage(
                    role = Role.ASSISTANT,
                    text = "（Gemini が応答しませんでした: ${finish ?: block ?: "不明"}）",
                    providerKind = ProviderKind.GEMINI.name, via = endpoint.name, cloud = endpoint.cloud,
                ),
                stopReason = finish, refused = true,
            )
        }
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        (content["parts"] as? JsonArray)?.forEach { pEl ->
            val p = pEl.jsonObject
            val t = (p["text"] as? JsonPrimitive)?.contentOrNull
            val isThought = (p["thought"] as? JsonPrimitive)?.booleanOrNull == true
            if (t != null) {
                if (isThought) reasoning.append(t) else text.append(t)
            }
            (p["functionCall"] as? JsonObject)?.let { fc ->
                calls += ToolCall(
                    id = (fc["id"] as? JsonPrimitive)?.contentOrNull
                        ?: ("gem_" + UUID.randomUUID().toString().replace("-", "").take(12)),
                    name = fc["name"]!!.let { (it as JsonPrimitive).content },
                    argumentsJson = (fc["args"] ?: JsonObject(emptyMap())).toString(),
                )
            }
        }
        sink.onReasoning(reasoning.toString())
        sink.onText(text.toString())
        // role が無い場合に備えて補う
        val rawContent = if (content.containsKey("role")) content
        else JsonObject(content + ("role" to JsonPrimitive("model")))
        return LlmResult(
            message = ChatMessage(
                role = Role.ASSISTANT,
                text = text.toString().trim(),
                reasoning = reasoning.toString().trim().ifEmpty { null },
                toolCalls = calls,
                providerKind = ProviderKind.GEMINI.name,
                raw = rawContent.toString(),
                via = endpoint.name,
                cloud = endpoint.cloud,
            ),
            stopReason = finish,
            refused = finish == "SAFETY" || finish == "PROHIBITED_CONTENT",
        )
    }
}
