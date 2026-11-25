package com.sumaho.agent.tools

import com.sumaho.agent.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * ツールの危険度。
 * READ: 自動実行 / WRITE: 実行前に確認 / EXTERNAL: 外部へ送信するので確認（相談は設定次第）
 * DEVICE: 端末操作（将来用の枠。既定で無効・毎回確認）
 */
enum class Risk { READ, WRITE, EXTERNAL, DEVICE }

class ToolException(message: String) : Exception(message)

interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject
    val risk: Risk

    /** 確認ダイアログに出す説明（null なら引数をそのまま表示） */
    fun preview(args: JsonObject): String? = null

    suspend fun run(args: JsonObject): String

    fun spec() = ToolSpec(name, description, parameters)
}

// ---- 引数ヘルパ ----
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.reqStr(key: String): String =
    str(key)?.takeIf { it.isNotBlank() } ?: throw ToolException("引数 '$key' が必要です")
fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

// ---- スキーマ DSL ----
class SchemaBuilder {
    private val props = mutableMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, desc: String, required: Boolean = false, enum: List<String>? = null) {
        props[name] = buildJsonObject {
            put("type", "string")
            put("description", desc)
            if (enum != null) put("enum", buildJsonArray { enum.forEach { add(it) } })
        }
        if (required) this.required += name
    }

    fun integer(name: String, desc: String, required: Boolean = false) {
        props[name] = buildJsonObject { put("type", "integer"); put("description", desc) }
        if (required) this.required += name
    }

    fun boolean(name: String, desc: String, required: Boolean = false) {
        props[name] = buildJsonObject { put("type", "boolean"); put("description", desc) }
        if (required) this.required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        put("required", buildJsonArray { required.forEach { add(it) } })
    }
}

fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()
