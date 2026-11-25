package com.sumaho.agent.tools

import com.sumaho.agent.core.truncateChars
import com.sumaho.agent.data.Attachments
import com.sumaho.agent.data.NotesStore
import com.sumaho.agent.data.Workspace
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmt(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).format(Date(t))

// ---------------- メモ ----------------

class MemoSaveTool(private val notes: NotesStore) : AgentTool {
    override val name = "memo_save"
    override val description = "メモを保存する。id を指定すると既存メモを上書き更新する。"
    override val parameters = schema {
        string("title", "タイトル", required = true)
        string("body", "本文", required = true)
        string("tags", "カンマ区切りのタグ")
        string("id", "更新する場合のメモID")
    }
    override val risk = Risk.WRITE
    override fun preview(args: JsonObject) =
        "メモを${if (args.str("id") != null) "更新" else "保存"}: ${args.str("title")}\n\n${args.str("body")?.take(500)}"

    override suspend fun run(args: JsonObject): String {
        val tags = args.str("tags")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val m = notes.saveMemo(args.str("id"), args.reqStr("title"), args.reqStr("body"), tags)
        return "保存しました (id=${m.id})"
    }
}

class MemoSearchTool(private val notes: NotesStore) : AgentTool {
    override val name = "memo_search"
    override val description = "メモを検索する（空の query で最近のメモ一覧）。本文は memo_read で読む。"
    override val parameters = schema { string("query", "検索語（スペース区切りでAND）") }
    override val risk = Risk.READ

    override suspend fun run(args: JsonObject): String {
        val q = args.str("query").orEmpty()
        val list = if (q.isBlank()) notes.memos().sortedByDescending { it.updatedAt } else notes.searchMemos(q)
        if (list.isEmpty()) return "該当するメモはありません"
        return list.take(20).joinToString("\n") {
            "- id=${it.id} [${fmt(it.updatedAt)}] ${it.title}" +
                (if (it.tags.isNotEmpty()) " #" + it.tags.joinToString(" #") else "") +
                " : " + it.body.replace("\n", " ").take(80)
        }
    }
}

class MemoReadTool(private val notes: NotesStore) : AgentTool {
    override val name = "memo_read"
    override val description = "メモの本文を読む"
    override val parameters = schema { string("id", "メモID", required = true) }
    override val risk = Risk.READ

    override suspend fun run(args: JsonObject): String {
        val id = args.reqStr("id")
        val m = notes.memos().firstOrNull { it.id == id } ?: throw ToolException("メモが見つかりません: $id")
        return "# ${m.title}\n(更新 ${fmt(m.updatedAt)})\n\n${m.body}"
    }
}

class MemoDeleteTool(private val notes: NotesStore) : AgentTool {
    override val name = "memo_delete"
    override val description = "メモを削除する"
    override val parameters = schema { string("id", "メモID", required = true) }
    override val risk = Risk.WRITE
    override fun preview(args: JsonObject): String {
        val m = notes.memos().firstOrNull { it.id == args.str("id") }
        return "メモを削除: ${m?.title ?: args.str("id")}"
    }

    override suspend fun run(args: JsonObject): String =
        if (notes.deleteMemo(args.reqStr("id"))) "削除しました" else throw ToolException("メモが見つかりません")
}

// ---------------- 長期記憶 ----------------

class MemorySaveTool(private val notes: NotesStore) : AgentTool {
    override val name = "memory_save"
    override val description =
        "ユーザーについて今後も覚えておくべき事実（好み・状況・約束など）を1件保存する。保存した記憶は以後の会話で常に参照される。"
    override val parameters = schema { string("text", "覚えておく内容（簡潔に1文）", required = true) }
    override val risk = Risk.WRITE
    override fun preview(args: JsonObject) = "長期記憶に保存: ${args.str("text")}"

    override suspend fun run(args: JsonObject): String {
        val item = notes.remember(args.reqStr("text"))
        return "記憶しました (id=${item.id})"
    }
}

class MemoryForgetTool(private val notes: NotesStore) : AgentTool {
    override val name = "memory_forget"
    override val description = "長期記憶を1件削除する（古くなった・間違っていた記憶）"
    override val parameters = schema { string("id", "記憶ID", required = true) }
    override val risk = Risk.WRITE
    override fun preview(args: JsonObject): String {
        val m = notes.memory().firstOrNull { it.id == args.str("id") }
        return "長期記憶を削除: ${m?.text ?: args.str("id")}"
    }

    override suspend fun run(args: JsonObject): String =
        if (notes.forget(args.reqStr("id"))) "削除しました" else throw ToolException("記憶が見つかりません")
}

// ---------------- ワークスペース ----------------

class WorkspaceListTool(private val ws: Workspace) : AgentTool {
    override val name = "workspace_list"
    override val description = "作業フォルダ（アプリ内）のファイル一覧"
    override val parameters = schema { string("path", "フォルダ（既定は最上位）") }
    override val risk = Risk.READ

    override suspend fun run(args: JsonObject): String {
        val dir = ws.resolve(args.str("path").orEmpty())
        if (!dir.isDirectory) throw ToolException("フォルダがありません")
        val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) return "（空）"
        return files.joinToString("\n") {
            if (it.isDirectory) "${it.name}/" else "${it.name}  (${it.length()} bytes, ${fmt(it.lastModified())})"
        }
    }
}

class WorkspaceReadTool(private val ws: Workspace) : AgentTool {
    override val name = "workspace_read"
    override val description = "作業フォルダのテキストファイルを読む"
    override val parameters = schema {
        string("path", "ファイルパス", required = true)
        integer("start", "開始文字位置（既定0）")
    }
    override val risk = Risk.READ

    override suspend fun run(args: JsonObject): String {
        val f = ws.resolve(args.reqStr("path"))
        if (!f.isFile) throw ToolException("ファイルがありません")
        val start = (args.int("start") ?: 0).coerceAtLeast(0)
        val text = f.readText()
        val slice = text.drop(start).take(8000)
        val rest = text.length - start - slice.length
        return slice + if (rest > 0) "\n…（残り${rest}文字。続きは start=${start + slice.length}）" else ""
    }
}

class WorkspaceWriteTool(private val ws: Workspace) : AgentTool {
    override val name = "workspace_write"
    override val description = "作業フォルダにテキストファイルを書く（既存は上書き。append=true で追記）"
    override val parameters = schema {
        string("path", "ファイルパス（例: notes/plan.md）", required = true)
        string("content", "内容", required = true)
        boolean("append", "追記するか")
    }
    override val risk = Risk.WRITE
    override fun preview(args: JsonObject) =
        "${if (args.bool("append") == true) "追記" else "書き込み"}: ${args.str("path")}\n\n${args.str("content")?.take(800)}"

    override suspend fun run(args: JsonObject): String {
        val f = ws.resolve(args.reqStr("path"))
        f.parentFile?.mkdirs()
        val content = args.reqStr("content")
        if (args.bool("append") == true) f.appendText(content) else f.writeText(content)
        return "書き込みました: ${args.str("path")} (${f.length()} bytes)"
    }
}

// ---------------- 添付文書 ----------------

class DocumentListTool(private val att: Attachments) : AgentTool {
    override val name = "document_list"
    override val description = "ユーザーが添付・共有した文書（PDF/テキスト）の一覧"
    override val parameters = schema { }
    override val risk = Risk.READ

    override suspend fun run(args: JsonObject): String {
        val files = att.list()
        if (files.isEmpty()) return "添付文書はありません"
        return files.joinToString("\n") { "- ${it.name} (${it.length() / 1024} KB, ${fmt(it.lastModified())})" }
    }
}

class DocumentReadTool(private val att: Attachments) : AgentTool {
    override val name = "document_read"
    override val description = "添付文書の本文をテキストで読む。長い文書は start で続きから読める。"
    override val parameters = schema {
        string("name", "ファイル名（document_list の名前）", required = true)
        integer("start", "開始文字位置（既定0）")
        integer("max_chars", "最大文字数（既定6000）")
    }
    override val risk = Risk.READ

    override suspend fun run(args: JsonObject): String {
        val f = att.find(args.reqStr("name")) ?: throw ToolException("文書が見つかりません。document_list で確認してください")
        val text = try {
            att.extractText(f)
        } catch (e: Exception) {
            throw ToolException("テキスト抽出に失敗: ${e.message}")
        }
        val start = (args.int("start") ?: 0).coerceAtLeast(0)
        val max = (args.int("max_chars") ?: 6000).coerceIn(500, 20000)
        val slice = text.drop(start).take(max)
        val rest = text.length - start - slice.length
        return "# ${f.name}（全${text.length}文字）\n\n" + slice.truncateChars(max) +
            if (rest > 0) "\n…（残り${rest}文字。続きは start=${start + slice.length}）" else ""
    }
}
