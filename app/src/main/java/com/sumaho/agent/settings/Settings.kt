package com.sumaho.agent.settings

import android.content.Context
import com.sumaho.agent.llm.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class ProviderKind { OPENAI_COMPAT, ANTHROPIC, GEMINI }

@Serializable
data class Endpoint(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    /** クラウド（外部送信）扱いか */
    val cloud: Boolean,
    val enabled: Boolean = true,
    /** OpenAI互換のローカル推論向け：chat_template_kwargs.reasoning_effort（空なら送らない） */
    val localReasoningEffort: String = "",
    /** Claude / OpenAI 向け effort（空なら送らない） */
    val effort: String = "",
    val maxTokens: Int = 8192,
) {
    val secretKey: String get() = "key:$id"
}

@Serializable
enum class SearchKind { NONE, SEARXNG, BRAVE, TAVILY }

@Serializable
data class SearchSettings(
    val kind: SearchKind = SearchKind.NONE,
    /** SearXNG のURL（例: http://100.x.x.x:8888） */
    val searxngUrl: String = "",
)

@Serializable
data class ConsultSettings(
    val endpointId: String = "claude",
    /** true: 送信内容を毎回プレビューして確認 / false: 上限内なら自動 */
    val confirmEachTime: Boolean = true,
    val dailyLimit: Int = 20,
    val maxTokens: Int = 2000,
    /** ツール失敗が続いたら相談を促すヒントを入れる */
    val hintOnRepeatedErrors: Boolean = true,
)

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** true なら確認なしでツールを実行 */
    val autoApprove: Boolean = false,
    /** 認証ヘッダー名（トークンは SecretStore に "mcp:{id}" で保存） */
    val authHeader: String = "Authorization",
) {
    val secretKey: String get() = "mcp:$id"
}

@Serializable
data class EngineSettings(
    /** models フォルダ内の .gguf ファイル名（空なら最初の1つ） */
    val modelFile: String = "",
    val ctxSize: Int = 16384,
    val port: Int = 8765,
    /** 自宅PCに繋がらず「スマホ内」になったとき自動でエンジンを起動する */
    val autoStartOnDemand: Boolean = true,
    /** 長い入力の読み込みを NPU（Hexagon）で行う。起動時の確認に通らなければ自動で CPU に戻る */
    val npuPrefill: Boolean = true,
    /** 上級者向け：bmoe-server への追加引数 */
    val extraArgs: String = "",
)

@Serializable
data class AppSettings(
    val engine: EngineSettings = EngineSettings(),
    val endpoints: List<Endpoint> = defaultEndpoints(),
    /** 自動ルーティング：まずこちら */
    val primaryEndpointId: String = "home",
    /** primary に繋がらないときの代替 */
    val fallbackEndpointId: String = "phone",
    val consult: ConsultSettings = ConsultSettings(),
    val search: SearchSettings = SearchSettings(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    /** 組み込みツールの有効/無効（未登録は有効扱い） */
    val toolEnabled: Map<String, Boolean> = emptyMap(),
    val maxAgentSteps: Int = 12,
) {
    fun endpoint(id: String?): Endpoint? = endpoints.firstOrNull { it.id == id }
}

fun defaultEndpoints(): List<Endpoint> = listOf(
    Endpoint(
        id = "phone", name = "スマホ内", kind = ProviderKind.OPENAI_COMPAT,
        baseUrl = "http://127.0.0.1:8765/v1", model = "inkling-2-mini",
        cloud = false, localReasoningEffort = "low", maxTokens = 4096,
    ),
    Endpoint(
        id = "home", name = "自宅PC", kind = ProviderKind.OPENAI_COMPAT,
        baseUrl = "http://100.64.0.1:8080/v1", model = "inkling-2-mini",
        cloud = false, localReasoningEffort = "low", maxTokens = 8192,
    ),
    Endpoint(
        id = "openai", name = "OpenAI", kind = ProviderKind.OPENAI_COMPAT,
        baseUrl = "https://api.openai.com/v1", model = "",
        cloud = true, enabled = false,
    ),
    Endpoint(
        id = "claude", name = "Claude", kind = ProviderKind.ANTHROPIC,
        baseUrl = "https://api.anthropic.com", model = "claude-opus-5-5",
        cloud = true, enabled = false, effort = "medium", maxTokens = 16000,
    ),
    Endpoint(
        id = "gemini", name = "Gemini", kind = ProviderKind.GEMINI,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta", model = "",
        cloud = true, enabled = false,
    ),
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state

    val current: AppSettings get() = _state.value

    private fun load(): AppSettings {
        val raw = prefs.getString("app", null) ?: return AppSettings()
        return runCatching { AppJson.decodeFromString(AppSettings.serializer(), raw) }
            .getOrDefault(AppSettings())
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        prefs.edit().putString("app", AppJson.encodeToString(AppSettings.serializer(), next)).apply()
        _state.value = next
    }
}
