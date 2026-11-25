package com.sumaho.agent.data

import android.content.Context
import com.sumaho.agent.llm.AppJson
import com.sumaho.agent.llm.ChatMessage
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

// ---------------- メモ / 長期記憶 ----------------

@Serializable
data class Memo(
    val id: String,
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class MemoryItem(
    val id: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

class NotesStore(context: Context) {
    private val memoFile = File(context.filesDir, "memos.json")
    private val memoryFile = File(context.filesDir, "memory.json")
    private val lock = Any()

    fun memos(): List<Memo> = synchronized(lock) { readList(memoFile, Memo.serializer()) }

    fun saveMemo(id: String?, title: String, body: String, tags: List<String>): Memo = synchronized(lock) {
        val list = memos().toMutableList()
        val memo = Memo(id ?: newId(), title, body, tags)
        val idx = list.indexOfFirst { it.id == memo.id }
        if (idx >= 0) list[idx] = memo else list += memo
        writeList(memoFile, Memo.serializer(), list)
        memo
    }

    fun deleteMemo(id: String): Boolean = synchronized(lock) {
        val list = memos()
        val next = list.filterNot { it.id == id }
        writeList(memoFile, Memo.serializer(), next)
        next.size != list.size
    }

    fun searchMemos(query: String): List<Memo> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return memos().filter { m ->
            val hay = (m.title + " " + m.body + " " + m.tags.joinToString(" ")).lowercase()
            terms.all { hay.contains(it) }
        }.sortedByDescending { it.updatedAt }
    }

    fun memory(): List<MemoryItem> = synchronized(lock) { readList(memoryFile, MemoryItem.serializer()) }

    fun remember(text: String): MemoryItem = synchronized(lock) {
        val item = MemoryItem(newId(), text.trim())
        writeList(memoryFile, MemoryItem.serializer(), memory() + item)
        item
    }

    fun forget(id: String): Boolean = synchronized(lock) {
        val list = memory()
        val next = list.filterNot { it.id == id }
        writeList(memoryFile, MemoryItem.serializer(), next)
        next.size != list.size
    }

    private fun <T> readList(f: File, s: kotlinx.serialization.KSerializer<T>): List<T> =
        if (!f.exists()) emptyList()
        else runCatching { AppJson.decodeFromString(ListSerializer(s), f.readText()) }.getOrDefault(emptyList())

    private fun <T> writeList(f: File, s: kotlinx.serialization.KSerializer<T>, list: List<T>) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(ListSerializer(s), list))
        tmp.renameTo(f)
    }
}

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

// ---------------- ワークスペース（アプリ内フォルダ） ----------------

class Workspace(context: Context) {
    val root: File = File(context.filesDir, "workspace").apply { mkdirs() }

    /** ワークスペース外へのアクセス（../ など）を禁止 */
    fun resolve(path: String): File {
        val f = File(root, path.trimStart('/', '\\')).canonicalFile
        if (!f.path.startsWith(root.canonicalPath)) throw SecurityException("ワークスペース外のパスです: $path")
        return f
    }
}

// ---------------- 添付文書 ----------------

class Attachments(context: Context) {
    val dir: File = File(context.filesDir, "attachments").apply { mkdirs() }

    fun list(): List<File> = dir.listFiles()?.filter { !it.name.endsWith(".extracted.txt") }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun find(name: String): File? = list().firstOrNull { it.name == name }
        ?: list().firstOrNull { it.name.contains(name, ignoreCase = true) }

    fun uniqueFile(displayName: String): File {
        val clean = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        var f = File(dir, clean)
        var i = 1
        while (f.exists()) {
            val dot = clean.lastIndexOf('.')
            val n = if (dot > 0) clean.substring(0, dot) + "($i)" + clean.substring(dot) else "$clean($i)"
            f = File(dir, n); i++
        }
        return f
    }

    /** テキストを抽出（PDF は PDFBox、他は UTF-8 テキストとして読む）。結果はキャッシュする */
    fun extractText(f: File): String {
        val cache = File(dir, f.name + ".extracted.txt")
        if (cache.exists() && cache.lastModified() >= f.lastModified()) return cache.readText()
        val text = if (f.name.endsWith(".pdf", ignoreCase = true)) {
            PDDocument.load(f).use { doc -> PDFTextStripper().getText(doc) }
        } else {
            f.readText(Charsets.UTF_8)
        }
        cache.writeText(text)
        return text
    }
}

// ---------------- 会話 ----------------

@Serializable
data class Conversation(
    val id: String = newId(),
    val title: String = "新しい会話",
    val messages: List<ChatMessage> = emptyList(),
    /** null = 自動ルーティング。クラウドはここで明示指定したときだけ使う */
    val endpointOverride: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

class ConversationStore(context: Context) {
    private val dir = File(context.filesDir, "conversations").apply { mkdirs() }

    fun list(): List<Conversation> = dir.listFiles { f -> f.name.endsWith(".json") }
        ?.mapNotNull { f -> runCatching { AppJson.decodeFromString(Conversation.serializer(), f.readText()) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt } ?: emptyList()

    fun save(c: Conversation) {
        File(dir, "${c.id}.json").writeText(AppJson.encodeToString(Conversation.serializer(), c))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
