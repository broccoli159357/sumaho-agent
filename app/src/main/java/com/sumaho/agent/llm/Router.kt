package com.sumaho.agent.llm

import com.sumaho.agent.core.Http
import com.sumaho.agent.core.executeCancellable
import com.sumaho.agent.settings.Endpoint
import com.sumaho.agent.settings.ProviderKind
import com.sumaho.agent.settings.SecretStore
import com.sumaho.agent.settings.SettingsRepository
import okhttp3.Request

/**
 * 推論先の選択。
 * - 会話ごとの手動指定（override）があればそれを使う（クラウドはこの方法でだけ使われる）
 * - 自動：primary（既定は自宅PC）に繋がればそちら、駄目なら fallback（既定はスマホ内）
 */
class Router(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    data class Resolved(val endpoint: Endpoint, val provider: LlmProvider, val note: String? = null)

    private val healthCache = mutableMapOf<String, Pair<Long, Boolean>>()

    fun providerFor(endpoint: Endpoint): LlmProvider {
        val key = secrets.get(endpoint.secretKey)
        return when (endpoint.kind) {
            ProviderKind.OPENAI_COMPAT -> OpenAICompatProvider(endpoint, key)
            ProviderKind.ANTHROPIC -> AnthropicProvider(endpoint, key)
            ProviderKind.GEMINI -> GeminiProvider(endpoint, key)
        }
    }

    suspend fun resolve(overrideId: String?): Resolved {
        val s = settings.current
        if (overrideId != null) {
            val ep = s.endpoint(overrideId) ?: throw LlmException("推論先 $overrideId が見つかりません")
            return Resolved(ep, providerFor(ep))
        }
        val primary = s.endpoint(s.primaryEndpointId)?.takeIf { it.enabled }
        val fallback = s.endpoint(s.fallbackEndpointId)?.takeIf { it.enabled }
        if (primary != null && (primary.cloud || isReachable(primary))) {
            return Resolved(primary, providerFor(primary))
        }
        if (fallback != null) {
            val note = primary?.let { "「${it.name}」に接続できないため「${fallback.name}」を使用" }
            return Resolved(fallback, providerFor(fallback), note)
        }
        throw LlmException("使える推論先がありません。設定を確認してください")
    }

    /** ローカル推論サーバーの死活確認（60秒キャッシュ） */
    suspend fun isReachable(ep: Endpoint): Boolean {
        val now = System.currentTimeMillis()
        healthCache[ep.id]?.let { (t, ok) -> if (now - t < 60_000) return ok }
        val ok = runCatching {
            val req = Request.Builder().url(ep.baseUrl.trimEnd('/') + "/models").get().build()
            Http.shortClient.executeCancellable(req).use { it.code < 500 }
        }.getOrDefault(false)
        healthCache[ep.id] = now to ok
        return ok
    }

    fun invalidateHealth() = healthCache.clear()
}
