package com.sumaho.agent.agent

import com.sumaho.agent.AppContainer
import com.sumaho.agent.core.truncateChars
import com.sumaho.agent.data.Conversation
import com.sumaho.agent.llm.AppJson
import com.sumaho.agent.llm.ChatMessage
import com.sumaho.agent.llm.LlmRequest
import com.sumaho.agent.llm.Role
import com.sumaho.agent.llm.StreamSink
import com.sumaho.agent.llm.ToolResult
import com.sumaho.agent.tools.AgentTool
import com.sumaho.agent.tools.ConsultExternalTool
import com.sumaho.agent.tools.Risk
import com.sumaho.agent.tools.ToolException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ConfirmRequest(
    val toolName: String,
    val risk: Risk,
    val title: String,
    val detail: String,
)

enum class Decision { ALLOW, ALLOW_ALWAYS, DENY }

interface AgentCallbacks {
    fun onConversation(conv: Conversation)
    fun onStreamReset()
    fun onStreamText(delta: String)
    fun onStreamReasoning(delta: String)
    fun onStatus(status: String?)
    suspend fun confirm(request: ConfirmRequest): Decision
}

/**
 * エージェントループ：LLM 呼び出し → ツール実行（危険度に応じて確認）→ 結果を返して再推論、を繰り返す。
 */
class AgentEngine(private val app: AppContainer) {

    /** このアプリ起動中に「常に許可」されたツール */
    private val sessionAllowed = mutableSetOf<String>()

    fun availableTools(): List<AgentTool> {
        val s = app.settings.current
        val builtins = app.builtinTools().filter { s.toolEnabled[it.name] ?: true }
        return builtins + app.mcp.tools()
    }

    private fun systemPrompt(tools: List<AgentTool>): String {
        val now = Date()
        // 分単位の時刻を入れると毎分プロンプト先頭が変わり、エンジンのKVキャッシュ（前回の読み込み結果）が使えなくなる。日付だけにする
        val date = SimpleDateFormat("yyyy年M月d日(E)", Locale.JAPAN).format(now)
        val names = tools.map { it.name }.toSet()
        val memory = app.notes.memory()
        val docs = app.attachments.list()
        return buildString {
            appendLine("あなたはユーザーのスマートフォン上で動くAIエージェントです。現在: $date")
            appendLine("- 必要ならツールを使い、結果を確かめてから答える。推測で事実を作らない。")
            if ("run_js" in names) appendLine("- 計算・集計は暗算せず run_js を使う。")
            if ("consult_external" in names) {
                appendLine("- 難しい・自信がない・知識が足りないときは consult_external で外部AIに相談し、回答を踏まえて続ける。")
            }
            if ("memory_save" in names) appendLine("- 今後も役立つユーザー情報を知ったら memory_save で記憶する。")
            appendLine("- 日本語で簡潔に答える。")
            if (memory.isNotEmpty()) {
                appendLine()
                appendLine("【長期記憶】")
                memory.takeLast(40).forEach { appendLine("- (${it.id}) ${it.text}") }
            }
            if (docs.isNotEmpty()) {
                appendLine()
                appendLine("【添付文書】${docs.take(10).joinToString(", ") { it.name }}（document_read で読める）")
            }
        }.trim()
    }

    suspend fun run(start: Conversation, overrideId: String?, cb: AgentCallbacks): Conversation {
        var conv = start
        val s = app.settings.current
        var consecutiveErrors = 0
        try {
            for (step in 0 until s.maxAgentSteps) {
                cb.onStatus("推論先を確認中…")
                var resolved = app.router.resolve(overrideId)
                resolved.note?.let { cb.onStatus(it) }
                // 「スマホ内」になったら内蔵エンジンを必要に応じて起動し、読み込み完了まで待つ
                if (resolved.endpoint.id == "phone" && app.engineManager.isBundled() &&
                    (app.engineManager.isRunning || s.engine.autoStartOnDemand)
                ) {
                    app.engineManager.ensureReady { cb.onStatus(it) }
                    resolved = app.router.resolve(overrideId ?: "phone")
                }
                val tools = availableTools()
                cb.onStreamReset()
                cb.onStatus("${resolved.endpoint.name} で推論中…")
                val result = resolved.provider.complete(
                    LlmRequest(
                        system = systemPrompt(tools),
                        messages = conv.messages,
                        tools = tools.map { it.spec() },
                        maxTokens = resolved.endpoint.maxTokens,
                    ),
                    object : StreamSink {
                        override fun onText(delta: String) = cb.onStreamText(delta)
                        override fun onReasoning(delta: String) = cb.onStreamReasoning(delta)
                    }
                )
                conv = conv.append(result.message)
                cb.onStreamReset()
                cb.onConversation(conv)

                val calls = result.message.toolCalls
                if (calls.isEmpty()) break

                val results = mutableListOf<ToolResult>()
                for (call in calls) {
                    val tool = tools.firstOrNull { it.name == call.name }
                    val r = if (tool == null) {
                        ToolResult(call.id, call.name, "不明なツールです: ${call.name}", isError = true)
                    } else {
                        executeTool(tool, call.id, call.argumentsJson, cb)
                    }
                    results += r
                    consecutiveErrors = if (r.isError) consecutiveErrors + 1 else 0
                }
                // 失敗が続いたら外部への相談を促す（アプリ側の判断）
                if (consecutiveErrors >= 2 && s.consult.hintOnRepeatedErrors &&
                    tools.any { it is ConsultExternalTool } && app.consult.remainingToday() > 0
                ) {
                    val last = results.removeAt(results.lastIndex)
                    results += last.copy(
                        content = last.content + "\n\n[システム] ツールの失敗が続いています。" +
                            "方針を見直すか、consult_external で外部AIに相談することを検討してください。"
                    )
                    consecutiveErrors = 0
                }
                conv = conv.append(ChatMessage(role = Role.TOOL, toolResults = results))
                cb.onConversation(conv)

                if (step == s.maxAgentSteps - 1) {
                    conv = conv.append(
                        ChatMessage(role = Role.ASSISTANT, text = "（ステップ上限 ${s.maxAgentSteps} に達したため中断しました。「続けて」と送ると再開します）")
                    )
                    cb.onConversation(conv)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            conv = conv.append(ChatMessage(role = Role.ASSISTANT, text = "⚠ エラー: ${e.message ?: e}", via = "system"))
            cb.onConversation(conv)
        } finally {
            cb.onStreamReset()
            cb.onStatus(null)
        }
        return conv
    }

    private suspend fun executeTool(tool: AgentTool, callId: String, argsJson: String, cb: AgentCallbacks): ToolResult {
        val args: JsonObject = try {
            AppJson.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) {
            return ToolResult(callId, tool.name, "引数のJSONが不正です: ${argsJson.take(200)}", isError = true)
        }
        val needConfirm = when (tool.risk) {
            Risk.READ -> false
            Risk.WRITE -> tool.name !in sessionAllowed
            Risk.EXTERNAL -> app.settings.current.consult.confirmEachTime && tool.name !in sessionAllowed
            Risk.DEVICE -> true
        }
        if (needConfirm) {
            val title = when (tool.risk) {
                Risk.EXTERNAL -> "外部AIに送信しますか？"
                Risk.DEVICE -> "端末を操作しますか？"
                else -> "ツールを実行しますか？"
            }
            val detail = tool.preview(args) ?: args.toString()
            when (cb.confirm(ConfirmRequest(tool.name, tool.risk, title, detail))) {
                Decision.DENY -> return ToolResult(callId, tool.name, "ユーザーが実行を拒否しました。別の方法を検討するか、ユーザーに確認してください。", isError = true)
                Decision.ALLOW_ALWAYS -> if (tool.risk != Risk.DEVICE) sessionAllowed += tool.name
                Decision.ALLOW -> Unit
            }
        }
        cb.onStatus("ツール実行中: ${tool.name}")
        return try {
            ToolResult(callId, tool.name, tool.run(args).truncateChars(20000))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            ToolResult(callId, tool.name, e.message ?: "失敗", isError = true)
        } catch (e: Exception) {
            ToolResult(callId, tool.name, "${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    /**
     * 「外部に聞く」ボタン：直前の質問（と最近の流れ）を外部AIに送り、その見解を会話に足してから推論を続ける。
     */
    suspend fun consultThenRun(start: Conversation, overrideId: String?, cb: AgentCallbacks): Conversation {
        var conv = start
        val lastUser = conv.messages.lastOrNull { it.role == Role.USER }?.text
            ?: return run(conv, overrideId, cb)
        val context = conv.messages.dropLast(1).takeLast(6)
            .filter { it.role != Role.TOOL && it.text.isNotBlank() }
            .joinToString("\n") { (if (it.role == Role.USER) "ユーザー: " else "エージェント: ") + it.text.take(400) }
            .truncateChars(2000)
        val preview = app.consult.previewText(lastUser, context, "consult")
        if (app.settings.current.consult.confirmEachTime) {
            val d = cb.confirm(ConfirmRequest("consult_external", Risk.EXTERNAL, "外部AIに送信しますか？", preview))
            if (d == Decision.DENY) return run(conv, overrideId, cb)
        }
        try {
            cb.onStatus("${app.consult.targetName()} に相談中…")
            val out = app.consult.consult(lastUser, context, "consult")
            conv = conv.append(
                ChatMessage(
                    role = Role.USER,
                    text = "[外部AI（${out.via}）の見解]\n${out.text}\n\n上の見解も踏まえて、元の質問に答えてください。",
                    via = out.via, cloud = true,
                )
            )
            cb.onConversation(conv)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            conv = conv.append(ChatMessage(role = Role.ASSISTANT, text = "⚠ 外部への相談に失敗: ${e.message}", via = "system"))
            cb.onConversation(conv)
            return conv
        }
        return run(conv, overrideId, cb)
    }
}

fun Conversation.append(m: ChatMessage): Conversation {
    val title = if (messages.isEmpty() && m.role == Role.USER) m.text.lineSequence().first().take(30).ifBlank { title } else title
    return copy(messages = messages + m, title = title, updatedAt = System.currentTimeMillis())
}
