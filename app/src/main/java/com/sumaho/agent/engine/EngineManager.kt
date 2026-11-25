package com.sumaho.agent.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.system.Os
import com.sumaho.agent.AgentApp
import com.sumaho.agent.core.Http
import com.sumaho.agent.core.executeCancellable
import com.sumaho.agent.llm.AppJson
import com.sumaho.agent.settings.SecretStore
import com.sumaho.agent.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 内蔵推論エンジン（BigMoeOnEdge の bmoe-server を同梱）。
 * - nativeLibraryDir の libbmoe-server.so を別プロセスとして起動し、127.0.0.1 の OpenAI 互換APIを使う
 * - 起動ごとにランダムなAPIキーを発行（同じ端末の他アプリからの利用を防ぐ）し、「スマホ内」推論先に自動設定
 * - モデルは RAM より大きくても、エキスパートをストレージから読みながら動く（--auto が端末を測って決める）
 */
class EngineManager(
    private val ctx: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    enum class Phase { STOPPED, STARTING, PLANNING, LOADING, READY, ERROR }

    data class State(
        val phase: Phase = Phase.STOPPED,
        val message: String = "",
        val port: Int = 0,
        val modelName: String? = null,
        val elapsedS: Double? = null,
        val log: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var process: Process? = null
    private var watcher: Job? = null
    private val logTail = StringBuilder()

    val modelsDir: File get() = (ctx.getExternalFilesDir("models") ?: File(ctx.filesDir, "models")).apply { mkdirs() }

    private val serverBinary: File get() = File(ctx.applicationInfo.nativeLibraryDir, "libbmoe-server.so")

    fun isBundled(): Boolean = serverBinary.exists()

    fun availableModels(): List<File> =
        modelsDir.listFiles { f -> f.name.endsWith(".gguf", true) }?.sortedBy { it.name } ?: emptyList()

    fun resolveModel(): File? {
        val name = settings.current.engine.modelFile
        val list = availableModels()
        return list.firstOrNull { it.name == name } ?: list.firstOrNull()
    }

    val isRunning: Boolean get() = process?.isAlive == true

    /** 起動して、モデルの読み込み完了（ready）まで待つ。失敗時は例外 */
    suspend fun ensureReady(onStatus: (String) -> Unit = {}): Int {
        if (!isRunning) start()
        while (true) {
            val s = _state.value
            when (s.phase) {
                Phase.READY -> return s.port
                Phase.ERROR, Phase.STOPPED -> throw IllegalStateException("内蔵エンジン: ${s.message}")
                else -> onStatus("スマホ内エンジン: ${s.message}")
            }
            delay(1000)
        }
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        if (!isBundled()) {
            _state.value = State(Phase.ERROR, "エンジンがこのビルドに含まれていません（arm64 端末のみ）")
            return
        }
        val model = resolveModel()
        if (model == null) {
            _state.value = State(Phase.ERROR, "モデルがありません: ${modelsDir.absolutePath} に .gguf を置いてください")
            return
        }
        val eng = settings.current.engine
        val dataDir = File(ctx.filesDir, "bmoe").apply { mkdirs() }
        val portFile = File(ctx.cacheDir, "bmoe-port").apply { delete() }
        val key = randomKey()
        val keyFile = File(ctx.noBackupFilesDir, "engine-api-key").apply {
            delete(); createNewFile(); Os.chmod(absolutePath, 0x180); writeText(key)
        }
        secrets.put("key:phone", key)

        val argv = mutableListOf(
            serverBinary.absolutePath,
            "--host", "127.0.0.1",
            "--port", eng.port.toString(),
            "--port-fallback",
            "--port-file", portFile.absolutePath,
            "--data-dir", dataDir.absolutePath,
            "--api-key-file", keyFile.absolutePath,
            "--chat-only",
            "--model", model.absolutePath,
            "--ctx-size", eng.ctxSize.toString(),
            "--auto",
        )
        if (eng.npuPrefill) {
            // 読み込み（prefill）だけ NPU で行う。まとめて流すほど専門家重みの読み込み 1 回で多くのトークンを
            // 処理できる（Fold8・Mix-16G で 340 トークン 7.8 tok/s）。512 単位は約 1000 トークンで空きメモリが
            // 1.3GB まで減ったので 256 単位・読み込み 4 本にする（978 トークン 5.5 tok/s、空き最低 1.7GB）。
            // 作業領域は 1 層分で読み込み中だけ確保する。確認に落ちたら CPU に戻る
            argv += listOf(
                "--prefill-device", "HTP0",
                "--prefill-best-effort",
                "--prefill-slots", "1",
                "--prefill-slots-on-demand",
                "--prefill-loaders", "4",
                "--ubatch", "256",
            )
        }
        argv += eng.extraArgs.split(Regex("\\s+")).filter { it.isNotBlank() }

        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val pb = ProcessBuilder(argv).directory(dataDir)
        pb.environment()["LD_LIBRARY_PATH"] = "$nativeDir:/system/lib64:/vendor/lib64"
        pb.environment()["ADSP_LIBRARY_PATH"] = nativeDir
        if (eng.npuPrefill) {
            // HMX の注意機構カーネルは Inkling（GQA 3）で計算を誤るので HVX 版を使う
            pb.environment()["GGML_HEXAGON_FA_SELECT"] = "1"
            pb.environment()["GGML_HEXAGON_HOSTBUF"] = "0"
        }
        synchronized(logTail) { logTail.setLength(0) }
        logFile = File(ctx.cacheDir, "engine.log").apply { writeText("") }
        _state.value = State(Phase.STARTING, "起動中…", modelName = model.name)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            _state.value = State(Phase.ERROR, "起動失敗: ${e.message}")
            return
        }
        process = p
        EngineService.start(ctx)

        thread(name = "engine-stderr") { pump(p.errorStream) }
        thread(name = "engine-stdout") {
            pump(p.inputStream)
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (process === p) {
                process = null
                val prev = _state.value
                _state.value = prev.copy(
                    phase = if (prev.phase == Phase.STOPPED) Phase.STOPPED else Phase.ERROR,
                    message = if (prev.phase == Phase.STOPPED) "停止" else "エンジンが終了しました (code $code)",
                    log = tail(),
                )
                EngineService.stop(ctx)
            }
        }
        watcher?.cancel()
        watcher = scope.launch { follow(p, portFile, key, model.name) }
    }

    private fun pump(stream: java.io.InputStream) {
        runCatching {
            stream.bufferedReader().forEachLine { line ->
                synchronized(logTail) {
                    logTail.append(line).append('\n')
                    if (logTail.length > 8000) logTail.delete(0, logTail.length - 6000)
                }
                // 画面の末尾表示は 8000 字で切れるので、全文は cache/engine.log に残す（起動のたびに作り直す）
                runCatching { logFile?.appendText(line + "\n") }
            }
        }
    }

    @Volatile private var logFile: File? = null

    private fun tail(): String = synchronized(logTail) { logTail.toString().takeLast(3000) }

    private suspend fun follow(p: Process, portFile: File, key: String, modelName: String) {
        var port = 0
        while (scope.isActive && p.isAlive) {
            if (port == 0) {
                port = portFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
                if (port > 0) {
                    // 「スマホ内」推論先を実際のポートに合わせる
                    settings.update { s ->
                        s.copy(endpoints = s.endpoints.map {
                            if (it.id == "phone") it.copy(baseUrl = "http://127.0.0.1:$port/v1") else it
                        })
                    }
                }
            } else {
                val info = runCatching { fetchInfo(port, key) }.getOrNull()
                if (info != null) {
                    val st = (info["state"] as? JsonPrimitive)?.contentOrNull
                    val err = (info["error"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val elapsed = (info["load_elapsed_s"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    val (phase, msg) = when (st) {
                        "planning" -> Phase.PLANNING to "端末の性能を測定中…"
                        "loading" -> Phase.LOADING to "モデル読み込み中…"
                        "ready" -> Phase.READY to "準備完了"
                        "error" -> Phase.ERROR to "読み込み失敗: $err"
                        "empty" -> Phase.LOADING to "モデル待ち…"
                        else -> Phase.STARTING to "起動中…"
                    }
                    _state.value = State(phase, msg, port, modelName, elapsed, tail())
                }
            }
            delay(if (_state.value.phase == Phase.READY) 5000 else 1000)
        }
    }

    private suspend fun fetchInfo(port: Int, key: String): JsonObject {
        val req = Request.Builder().url("http://127.0.0.1:$port/api/info")
            .header("Authorization", "Bearer $key").get().build()
        val resp = Http.shortClient.executeCancellable(req)
        val body = withContext(Dispatchers.IO) { resp.use { it.body?.string().orEmpty() } }
        return AppJson.parseToJsonElement(body).jsonObject
    }

    fun stop() {
        val p = process ?: return
        process = null
        watcher?.cancel()
        _state.value = State(Phase.STOPPED, "停止")
        scope.launch {
            runCatching {
                p.destroy()
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly(); p.waitFor(3, TimeUnit.SECONDS)
                }
            }
            EngineService.stop(ctx)
        }
    }

    private fun randomKey(): String {
        val b = ByteArray(24); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}

/** エンジン実行中にアプリがバックグラウンドへ行っても止まらないようにする常駐サービス */
class EngineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "推論エンジン", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Sumaho Agent")
            .setContentText("スマホ内推論エンジンが動作中")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        return START_NOT_STICKY
    }

    /** アプリをタスク一覧から消したらエンジンも止めてメモリを返す */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as AgentApp).container.engineManager.stop()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val CHANNEL = "engine"
        private const val NOTIF_ID = 10
        private const val ACTION_STOP = "com.sumaho.agent.ENGINE_STOP"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, EngineService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, EngineService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
