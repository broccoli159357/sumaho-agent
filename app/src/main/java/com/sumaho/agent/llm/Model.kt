package com.sumaho.agent.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

@Serializable
enum class Role { USER, ASSISTANT, TOOL }

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    /** JSON object as string */
    val argumentsJson: String,
)

@Serializable
data class ToolResult(
    val callId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false,
)

/**
 * アプリ内の共通メッセージ形式。
 * - 推論先を途中で切り替えても会話が続くよう、テキスト・ツール呼び出し・結果を共通形式で持つ
 * - 各社固有のデータ（Claudeの思考ブロック、Geminiの thought signature 等）は [raw] に保持し、
 *   同じ種類の推論先に送るときだけそのまま返す
 */
@Serializable
data class ChatMessage(
    val role: Role,
    val text: String = "",
    val reasoning: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolResult> = emptyList(),
    /** 生成した推論先の種類（ProviderKind.name） */
    val providerKind: String? = null,
    /** 推論先固有の生データ（JSON文字列） */
    val raw: String? = null,
    /** 表示用：どの推論先で生成したか */
    val via: String? = null,
    /** 表示用：クラウド経由か */
    val cloud: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema (type: object) */
    val parameters: JsonObject,
)

data class LlmRequest(
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>,
    val maxTokens: Int,
)

data class LlmResult(
    val message: ChatMessage,
    val stopReason: String? = null,
    val refused: Boolean = false,
)

interface StreamSink {
    fun onText(delta: String) {}
    fun onReasoning(delta: String) {}
}

object NoopSink : StreamSink

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface LlmProvider {
    suspend fun complete(request: LlmRequest, sink: StreamSink = NoopSink): LlmResult
}
