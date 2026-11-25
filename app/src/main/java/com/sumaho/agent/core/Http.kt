package com.sumaho.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

object Http {
    val JSON = "application/json; charset=utf-8".toMediaType()

    /** ローカル推論は遅いので読み取りタイムアウトは長め */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val shortClient: OkHttpClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) SumahoAgent/0.1"
}

/** コルーチンのキャンセルで HTTP 呼び出しも中断する */
suspend fun OkHttpClient.executeCancellable(request: Request): Response {
    val call: Call = newCall(request)
    return withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            try {
                cont.resumeWith(Result.success(call.execute()))
            } catch (t: Throwable) {
                if (!cont.isCancelled) cont.resumeWith(Result.failure(t))
            }
        }
    }
}

/** kotlinx JsonElement → 素の Kotlin 値（Map/List/String/Number/Boolean/null） */
fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> mapValues { it.value.toPlain() }
    is JsonArray -> map { it.toPlain() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull
        doubleOrNull != null -> doubleOrNull
        else -> content
    }
}

fun String.truncateChars(max: Int, marker: String = "\n…（以下省略）"): String =
    if (length <= max) this else take(max) + marker
