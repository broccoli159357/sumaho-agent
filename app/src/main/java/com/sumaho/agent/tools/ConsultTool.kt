package com.sumaho.agent.tools

import android.content.Context
import com.sumaho.agent.llm.ChatMessage
import com.sumaho.agent.llm.LlmRequest
import com.sumaho.agent.llm.Role
import com.sumaho.agent.llm.Router
import com.sumaho.agent.settings.SettingsRepository
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * 外部モデル（Claude / OpenAI / Gemini など）への相談。
 * 会話履歴は送らず、ローカルモデルがまとめた「質問＋必要な前提」だけを送る。
 */
class ConsultService(
    context: Context,
    private val settings: SettingsRepository,
    private val router: Router,
) {
    private val prefs = context.getSharedPreferences("consult_usage", Context.MODE_PRIVATE)

    data class Outcome(val text: String, val via: String, val refused: Boolean)

    fun targetName(): String =
        settings.current.endpoint(settings.current.consult.endpointId)?.name ?: "未設定"

    fun usedToday(): Int = prefs.getInt(LocalDate.now().toString(), 0)

    fun remainingToday(): Int = (settings.current.consult.dailyLimit - usedToday()).coerceAtLeast(0)

    private fun count() {
        val k = LocalDate.now().toString()
        prefs.edit().clear().putInt(k, prefs.getInt(k, 0) + 1).apply()
    }

    suspend fun consult(question: String, context: String, mode: String): Outcome {
        val s = settings.current
        val ep = s.endpoint(s.consult.endpointId)
            ?: throw ToolException("相談先が未設定です（設定 → 外部への相談）")
        if (!ep.enabled) throw ToolException("相談先「${ep.name}」が無効です（設定で有効化してください）")
        if (remainingToday() <= 0) throw ToolException("本日の相談上限（${s.consult.dailyLimit}回）に達しました")

        val modeInstruction = when (mode) {
            "review" -> "渡された下書き・回答の誤りや抜けを指摘し、修正案を示してください。"
            "plan" -> "課題を達成するための具体的な手順を番号付きで示してください。実行は相談者が行います。"
            else -> "質問に正確に答えてください。"
        }
        val system = """
            あなたはアドバイザーです。スマートフォン上で動く別のAIエージェント（小型のローカルモデル）から相談を受けています。
            $modeInstruction
            回答は相談者のコンテキストに追加されるため、要点を簡潔に（目安800字以内）まとめてください。前置きは不要です。
            不確かな点はそう明記してください。
        """.trimIndent()
        val user = buildString {
            append("【相談内容】\n").append(question.trim())
            if (context.isNotBlank()) append("\n\n【前提・状況】\n").append(context.trim())
        }
        val provider = router.providerFor(ep)
        val result = provider.complete(
            LlmRequest(
                system = system,
                messages = listOf(ChatMessage(role = Role.USER, text = user)),
                tools = emptyList(),
                // 思考トークンも max_tokens に含まれるため、回答の目安に余裕を足す
                maxTokens = s.consult.maxTokens + 6000,
            )
        )
        count()
        return Outcome(result.message.text, ep.name, result.refused)
    }

    fun previewText(question: String, context: String, mode: String): String = buildString {
        append("送信先: ${targetName()}（本日あと${remainingToday()}回）\nモード: $mode\n\n")
        append("【相談内容】\n").append(question)
        if (context.isNotBlank()) append("\n\n【前提・状況】\n").append(context)
    }
}

class ConsultExternalTool(private val service: ConsultService) : AgentTool {
    override val name = "consult_external"
    override val description =
        "自分では難しい・自信がない・知識が足りないときに、より高性能な外部AIに相談する。" +
            "会話履歴は送られないので、質問と必要な前提を自己完結した文章で書くこと。" +
            "mode: consult=質問に答えてもらう / review=自分の下書きを検証してもらう / plan=手順を立ててもらう。" +
            "回答を受け取ったら、それを踏まえて推論を続けること。"
    override val parameters = schema {
        string("question", "相談内容（自己完結した文章）", required = true)
        string("context", "必要な前提・状況・下書き（個人情報は必要最小限に）")
        string("mode", "consult / review / plan", enum = listOf("consult", "review", "plan"))
    }
    override val risk = Risk.EXTERNAL

    override fun preview(args: JsonObject) =
        service.previewText(args.str("question").orEmpty(), args.str("context").orEmpty(), args.str("mode") ?: "consult")

    override suspend fun run(args: JsonObject): String {
        val out = service.consult(args.reqStr("question"), args.str("context").orEmpty(), args.str("mode") ?: "consult")
        if (out.refused) return "外部モデル（${out.via}）は回答を拒否しました。自分で判断するか、別の聞き方を検討してください。\n${out.text}"
        return "【${out.via} の回答】\n${out.text}"
    }
}
