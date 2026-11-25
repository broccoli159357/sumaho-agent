package com.sumaho.agent.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import com.sumaho.agent.core.toPlain
import com.sumaho.agent.settings.Endpoint
import com.sumaho.agent.settings.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Claude（Anthropic Messages API, 公式 Java SDK）。
 * - 思考ブロックを含むアシスタント応答は SDK の Message を JSON で丸ごと保持し、次のリクエストでそのまま返す
 * - 他の推論先が作ったターンは text + tool_use ブロックとして組み直す
 */
class AnthropicProvider(
    private val endpoint: Endpoint,
    private val apiKey: String?,
) : LlmProvider {

    companion object {
        private val clients = ConcurrentHashMap<String, AnthropicClient>()

        /** サーバー側フォールバック（拒否時に別モデルへ）を既定で有効にするモデル */
        private val FALLBACK_MODELS = setOf(
            "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5",
            "claude-fable-5-2", "claude-fable-5-1",
        )

        fun encodeMessage(m: Message): String = jsonMapper().writeValueAsString(m)
        fun decodeMessage(raw: String): Message = jsonMapper().readValue(raw, Message::class.java)
    }

    private fun client(): AnthropicClient {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: throw LlmException("Claude の APIキーが未設定です")
        val cacheKey = endpoint.baseUrl + "|" + key.hashCode()
        return clients.getOrPut(cacheKey) {
            AnthropicOkHttpClient.builder()
                .apiKey(key)
                .baseUrl(endpoint.baseUrl.trimEnd('/'))
                .build()
        }
    }

    override suspend fun complete(request: LlmRequest, sink: StreamSink): LlmResult {
        if (endpoint.model.isBlank()) throw LlmException("Claude のモデル名が未設定です")
        val params = buildParams(request)
        val client = client()
        val ctx = currentCoroutineContext()
        val message: Message = try {
            runInterruptible(Dispatchers.IO) {
                val acc = MessageAccumulator.create()
                client.messages().createStreaming(params).use { stream ->
                    stream.stream().forEach { event ->
                        ctx.ensureActive()
                        acc.accumulate(event)
                        event.contentBlockDelta().ifPresent { d ->
                            d.delta().text().ifPresent { sink.onText(it.text()) }
                            d.delta().thinking().ifPresent { sink.onReasoning(it.thinking()) }
                        }
                    }
                }
                acc.message()
            }
        } catch (e: AnthropicServiceException) {
            throw LlmException("Claude エラー (HTTP ${e.statusCode()}): ${e.message}", e)
        }

        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (block in message.content()) {
            block.text().ifPresent { text.append(it.text()) }
            block.thinking().ifPresent { reasoning.append(it.thinking()) }
            block.toolUse().ifPresent { tu ->
                calls += ToolCall(
                    id = tu.id(),
                    name = tu.name(),
                    argumentsJson = jsonMapper().writeValueAsString(tu._input()),
                )
            }
        }
        val refused = message.stopReason().map { it == StopReason.REFUSAL }.orElse(false)
        if (refused && text.isEmpty()) text.append("（Claude がこのリクエストへの回答を拒否しました）")

        return LlmResult(
            message = ChatMessage(
                role = Role.ASSISTANT,
                text = text.toString().trim(),
                reasoning = reasoning.toString().trim().ifEmpty { null },
                toolCalls = calls,
                providerKind = ProviderKind.ANTHROPIC.name,
                raw = encodeMessage(message),
                via = endpoint.name,
                cloud = endpoint.cloud,
            ),
            stopReason = message.stopReason().map { it.toString() }.orElse(null),
            refused = refused,
        )
    }

    private fun buildParams(request: LlmRequest): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(endpoint.model)
            .maxTokens(request.maxTokens.toLong())
            .system(request.system)
            // 思考の要約を表示する（既定は空テキスト）
            .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())

        effortOf(endpoint.effort)?.let { b.outputConfig(OutputConfig.builder().effort(it).build()) }

        if (endpoint.model in FALLBACK_MODELS) {
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        request.tools.forEach { b.addTool(toTool(it)) }

        for (m in request.messages) {
            when (m.role) {
                Role.USER -> b.addUserMessage(m.text.ifEmpty { " " })
                Role.ASSISTANT -> {
                    val raw = m.raw
                    if (raw != null && m.providerKind == ProviderKind.ANTHROPIC.name) {
                        b.addMessage(decodeMessage(raw))
                    } else {
                        val blocks = mutableListOf<ContentBlockParam>()
                        if (m.text.isNotBlank()) {
                            blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(m.text).build())
                        }
                        m.toolCalls.forEach { c ->
                            blocks += ContentBlockParam.ofToolUse(
                                ToolUseBlockParam.builder()
                                    .id(sanitizeId(c.id))
                                    .name(c.name)
                                    .input(JsonValue.from(parseArgs(c.argumentsJson)))
                                    .build()
                            )
                        }
                        if (blocks.isEmpty()) {
                            blocks += ContentBlockParam.ofText(TextBlockParam.builder().text("（空の応答）").build())
                        }
                        b.addAssistantMessageOfContentBlockParams(blocks)
                    }
                }
                Role.TOOL -> b.addUserMessageOfContentBlockParams(
                    m.toolResults.map { r ->
                        ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                .toolUseId(sanitizeId(r.callId))
                                .content(r.content.ifEmpty { "(empty)" })
                                .isError(r.isError)
                                .build()
                        )
                    }
                )
            }
        }
        return b.build()
    }

    private fun toTool(spec: ToolSpec): Tool {
        val props = (spec.parameters["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        val required = (spec.parameters["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        val propsBuilder = Tool.InputSchema.Properties.builder()
        props.forEach { (k, v) -> propsBuilder.putAdditionalProperty(k, JsonValue.from(v.toPlain())) }
        return Tool.builder()
            .name(spec.name)
            .description(spec.description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(propsBuilder.build())
                    .required(required)
                    .build()
            )
            .build()
    }

    private fun parseArgs(json: String): Any? =
        runCatching { AppJson.parseToJsonElement(json).jsonObject.toPlain() }.getOrDefault(emptyMap<String, Any>())

    /** tool_use の id は ^[a-zA-Z0-9_-]+$ のみ */
    private fun sanitizeId(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "call" }

    private fun effortOf(s: String): OutputConfig.Effort? = when (s.lowercase()) {
        "low" -> OutputConfig.Effort.LOW
        "medium" -> OutputConfig.Effort.MEDIUM
        "high" -> OutputConfig.Effort.HIGH
        "xhigh" -> OutputConfig.Effort.XHIGH
        "max" -> OutputConfig.Effort.MAX
        else -> null
    }
}
