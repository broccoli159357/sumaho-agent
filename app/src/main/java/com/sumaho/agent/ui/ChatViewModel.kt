package com.sumaho.agent.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sumaho.agent.AgentApp
import com.sumaho.agent.agent.AgentCallbacks
import com.sumaho.agent.agent.ConfirmRequest
import com.sumaho.agent.agent.Decision
import com.sumaho.agent.agent.append
import com.sumaho.agent.data.Conversation
import com.sumaho.agent.llm.ChatMessage
import com.sumaho.agent.llm.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    val app = (application as AgentApp).container

    private val _conv = MutableStateFlow(app.conversations.list().firstOrNull() ?: Conversation())
    val conv: StateFlow<Conversation> = _conv

    val streamText = MutableStateFlow("")
    val streamReasoning = MutableStateFlow("")
    val status = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
    val input = MutableStateFlow("")
    val notice = MutableStateFlow<String?>(null)

    data class PendingConfirm(val request: ConfirmRequest, val answer: CompletableDeferred<Decision>)
    val pendingConfirm = MutableStateFlow<PendingConfirm?>(null)

    private val _history = MutableStateFlow(app.conversations.list())
    val history: StateFlow<List<Conversation>> = _history

    private var job: Job? = null

    private val callbacks = object : AgentCallbacks {
        override fun onConversation(conv: Conversation) {
            _conv.value = conv
            persist(conv)
        }
        override fun onStreamReset() { streamText.value = ""; streamReasoning.value = "" }
        override fun onStreamText(delta: String) { streamText.value += delta }
        override fun onStreamReasoning(delta: String) { streamReasoning.value += delta }
        override fun onStatus(status: String?) { this@ChatViewModel.status.value = status }
        override suspend fun confirm(request: ConfirmRequest): Decision {
            val d = CompletableDeferred<Decision>()
            pendingConfirm.value = PendingConfirm(request, d)
            return try { d.await() } finally { pendingConfirm.value = null }
        }
    }

    private fun persist(c: Conversation) {
        viewModelScope.launch(Dispatchers.IO) {
            app.conversations.save(c)
            _history.value = app.conversations.list()
        }
    }

    /** consult=true なら「外部に聞く」：外部AIの見解を足してから推論 */
    fun send(consult: Boolean = false) {
        if (running.value) return
        val text = input.value.trim()
        var c = _conv.value
        if (text.isEmpty() && !consult) return
        if (text.isNotEmpty()) {
            c = c.append(ChatMessage(role = Role.USER, text = text))
            input.value = ""
        } else if (c.messages.none { it.role == Role.USER }) {
            notice.value = "先に質問を入力してください"
            return
        }
        _conv.value = c
        persist(c)
        running.value = true
        job = viewModelScope.launch {
            try {
                val override = c.endpointOverride
                _conv.value = if (consult) app.engine.consultThenRun(c, override, callbacks)
                else app.engine.run(c, override, callbacks)
                persist(_conv.value)
            } finally {
                running.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        pendingConfirm.value?.answer?.complete(Decision.DENY)
        status.value = null
        streamText.value = ""
        streamReasoning.value = ""
        running.value = false
    }

    fun answerConfirm(d: Decision) {
        pendingConfirm.value?.answer?.complete(d)
    }

    fun newConversation() {
        if (running.value) return
        _conv.value = Conversation()
    }

    fun open(id: String) {
        if (running.value) return
        app.conversations.list().firstOrNull { it.id == id }?.let { _conv.value = it }
    }

    fun deleteConversation(id: String) {
        app.conversations.delete(id)
        _history.value = app.conversations.list()
        if (_conv.value.id == id) _conv.value = Conversation()
    }

    /** null = 自動（自宅PC→スマホ内）。クラウドは明示指定のみ */
    fun setOverride(endpointId: String?) {
        val c = _conv.value.copy(endpointOverride = endpointId)
        _conv.value = c
        if (c.messages.isNotEmpty()) persist(c)
    }

    fun attach(uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                val cr = getApplication<Application>().contentResolver
                val display = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0) else null
                } ?: "file"
                val dest = app.attachments.uniqueFile(display)
                cr.openInputStream(uri)?.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                dest.name
            }
            notice.value = "添付しました: $name（エージェントが document_read で読めます）"
        }
    }

    fun receiveSharedText(text: String) {
        input.value = if (input.value.isBlank()) text else input.value + "\n" + text
    }

    fun endpointLabel(): String {
        val s = app.settings.current
        val o = _conv.value.endpointOverride
        return if (o == null) "自動（${s.endpoint(s.primaryEndpointId)?.name}→${s.endpoint(s.fallbackEndpointId)?.name}）"
        else s.endpoint(o)?.let { (if (it.cloud) "☁ " else "") + it.name } ?: o
    }

    fun isCloudSelected(): Boolean {
        val o = _conv.value.endpointOverride ?: return false
        return app.settings.current.endpoint(o)?.cloud == true
    }
}
