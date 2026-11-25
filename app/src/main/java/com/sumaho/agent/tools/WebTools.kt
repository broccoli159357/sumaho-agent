package com.sumaho.agent.tools

import com.sumaho.agent.core.Http
import com.sumaho.agent.core.executeCancellable
import com.sumaho.agent.core.truncateChars
import com.sumaho.agent.llm.AppJson
import com.sumaho.agent.settings.SearchKind
import com.sumaho.agent.settings.SecretStore
import com.sumaho.agent.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

class WebSearchTool(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) : AgentTool {
    override val name = "web_search"
    override val description = "Webを検索して、タイトル・URL・抜粋の一覧を返す。詳しく読むには fetch_url を使う。"
    override val parameters = schema {
        string("query", "検索語", required = true)
        integer("count", "取得件数（既定5、最大10）")
    }
    override val risk = Risk.READ

    private data class Hit(val title: String, val url: String, val snippet: String)

    override suspend fun run(args: JsonObject): String {
        val q = args.reqStr("query")
        val n = (args.int("count") ?: 5).coerceIn(1, 10)
        val s = settings.current.search
        val hits = when (s.kind) {
            SearchKind.NONE -> throw ToolException("Web検索が未設定です（設定 → Web検索）")
            SearchKind.SEARXNG -> searxng(s.searxngUrl, q)
            SearchKind.BRAVE -> brave(q, n)
            SearchKind.TAVILY -> tavily(q, n)
        }.take(n)
        if (hits.isEmpty()) return "検索結果なし: $q"
        return hits.mapIndexed { i, h -> "[${i + 1}] ${h.title}\n${h.url}\n${h.snippet.truncateChars(300, "…")}" }
            .joinToString("\n\n")
    }

    private suspend fun getJson(req: Request): JsonObject {
        val resp = Http.client.executeCancellable(req)
        val body = withContext(Dispatchers.IO) { resp.use { it.body?.string().orEmpty() } }
        if (!resp.isSuccessful) throw ToolException("検索APIエラー HTTP ${resp.code}: ${body.take(300)}")
        return AppJson.parseToJsonElement(body).jsonObject
    }

    private suspend fun searxng(base: String, q: String): List<Hit> {
        if (base.isBlank()) throw ToolException("SearXNG のURLが未設定です")
        val url = (base.trimEnd('/') + "/search").toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("format", "json").build()
        val obj = getJson(Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build())
        return (obj["results"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
            Hit(it.s("title"), it.s("url"), it.s("content"))
        }
    }

    private suspend fun brave(q: String, n: Int): List<Hit> {
        val key = secrets.get("key:brave") ?: throw ToolException("Brave Search の APIキーが未設定です")
        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("count", n.toString()).build()
        val obj = getJson(Request.Builder().url(url).header("X-Subscription-Token", key)
            .header("Accept", "application/json").build())
        val results = (obj["web"] as? JsonObject)?.get("results") as? JsonArray
        return results.orEmpty().map { it.jsonObject }.map { Hit(it.s("title"), it.s("url"), it.s("description")) }
    }

    private suspend fun tavily(q: String, n: Int): List<Hit> {
        val key = secrets.get("key:tavily") ?: throw ToolException("Tavily の APIキーが未設定です")
        val body = buildJsonObject { put("query", q); put("max_results", n) }.toString()
        val obj = getJson(Request.Builder().url("https://api.tavily.com/search")
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody(Http.JSON)).build())
        return (obj["results"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
            Hit(it.s("title"), it.s("url"), it.s("content"))
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

class FetchUrlTool : AgentTool {
    override val name = "fetch_url"
    override val description = "URLのページ本文をテキストで取得する。長いページは start で続きから読める。"
    override val parameters = schema {
        string("url", "http(s) のURL", required = true)
        integer("start", "読み始める文字位置（既定0）")
        integer("max_chars", "最大文字数（既定6000）")
    }
    override val risk = Risk.READ

    override suspend fun run(args: JsonObject): String {
        val url = args.reqStr("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw ToolException("http(s) のURLのみ対応")
        val start = (args.int("start") ?: 0).coerceAtLeast(0)
        val max = (args.int("max_chars") ?: 6000).coerceIn(500, 20000)
        val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        val resp = Http.client.executeCancellable(req)
        val (ctype, body) = withContext(Dispatchers.IO) {
            resp.use { (it.header("Content-Type").orEmpty()) to (it.body?.string().orEmpty()) }
        }
        if (!resp.isSuccessful) throw ToolException("HTTP ${resp.code}")
        val text = if (ctype.contains("html") || body.trimStart().startsWith("<")) {
            val doc = Jsoup.parse(body, url)
            doc.select("script,style,noscript,nav,footer,header,aside,form,svg").remove()
            val title = doc.title()
            val main = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
            "# $title\n\n" + (main?.wholeText() ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString("\n")
        } else body
        val slice = text.drop(start).take(max)
        val rest = text.length - start - slice.length
        return slice + if (rest > 0) "\n\n…（残り${rest}文字。続きは start=${start + slice.length}）" else ""
    }
}
