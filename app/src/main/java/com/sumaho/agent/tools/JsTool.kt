package com.sumaho.agent.tools

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.Executor

/** ListenableFuture をコルーチンで待つ */
suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resumeWith(Result.success(get()))
        } catch (e: java.util.concurrent.ExecutionException) {
            cont.resumeWith(Result.failure(e.cause ?: e))
        } catch (t: Throwable) {
            cont.resumeWith(Result.failure(t))
        }
    }, Executor { it.run() })
    cont.invokeOnCancellation { cancel(true) }
}

/**
 * 計算・データ処理用の JavaScript 実行。
 * androidx.javascriptengine の隔離環境で動くので、ネットワークやファイルには触れない。
 */
class RunJsTool(private val context: Context) : AgentTool {
    override val name = "run_js"
    override val description =
        "JavaScriptを隔離環境で実行して計算やデータ処理をする（ネット・ファイル不可）。" +
            "結果は return で返すか console.log で出力する。暗算せずこれで計算すること。"
    override val parameters = schema {
        string("code", "実行するJavaScriptコード（関数本体として実行される）", required = true)
    }
    override val risk = Risk.READ

    private val mutex = Mutex()
    private var sandbox: JavaScriptSandbox? = null

    private suspend fun sandbox(): JavaScriptSandbox {
        sandbox?.let { return it }
        if (!JavaScriptSandbox.isSupported()) throw ToolException("この端末のWebViewはJS実行環境に未対応です")
        return JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).await().also { sandbox = it }
    }

    override suspend fun run(args: JsonObject): String = mutex.withLock {
        val code = args.reqStr("code")
        val wrapped = """
            (function(){
              const __out = [];
              const console = { log: (...a) => __out.push(a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ')) };
              console.error = console.log; console.warn = console.log;
              try {
                const __r = (function(){ $code
                })();
                if (__r !== undefined) __out.push(typeof __r === 'string' ? __r : JSON.stringify(__r));
              } catch (e) { __out.push('Error: ' + (e && e.message ? e.message : e)); }
              return __out.join('\n') || '(出力なし。return か console.log で結果を返してください)';
            })()
        """.trimIndent()
        val isolate = try {
            sandbox().createIsolate()
        } catch (e: Exception) {
            sandbox = null
            throw ToolException("JS環境の起動に失敗: ${e.message}")
        }
        try {
            withTimeout(15_000) { isolate.evaluateJavaScriptAsync(wrapped).await() }.take(8000)
        } finally {
            isolate.close()
        }
    }
}
