package com.sumaho.agent

import android.app.Application
import android.content.Context
import com.sumaho.agent.agent.AgentEngine
import com.sumaho.agent.data.Attachments
import com.sumaho.agent.data.ConversationStore
import com.sumaho.agent.data.NotesStore
import com.sumaho.agent.data.Workspace
import com.sumaho.agent.llm.Router
import com.sumaho.agent.mcp.McpManager
import com.sumaho.agent.settings.SecretStore
import com.sumaho.agent.settings.SettingsRepository
import com.sumaho.agent.tools.AgentTool
import com.sumaho.agent.tools.ConsultExternalTool
import com.sumaho.agent.tools.ConsultService
import com.sumaho.agent.tools.DocumentListTool
import com.sumaho.agent.tools.DocumentReadTool
import com.sumaho.agent.tools.FetchUrlTool
import com.sumaho.agent.tools.MemoDeleteTool
import com.sumaho.agent.tools.MemoReadTool
import com.sumaho.agent.tools.MemoSaveTool
import com.sumaho.agent.tools.MemoSearchTool
import com.sumaho.agent.tools.MemoryForgetTool
import com.sumaho.agent.tools.MemorySaveTool
import com.sumaho.agent.tools.RunJsTool
import com.sumaho.agent.tools.WebSearchTool
import com.sumaho.agent.tools.WorkspaceListTool
import com.sumaho.agent.tools.WorkspaceReadTool
import com.sumaho.agent.tools.WorkspaceWriteTool
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AgentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(applicationContext)
        container.refreshMcp()
    }
}

/** アプリ全体で共有する部品 */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val secrets = SecretStore(context)
    val notes = NotesStore(context)
    val workspace = Workspace(context)
    val attachments = Attachments(context)
    val conversations = ConversationStore(context)
    val router = Router(settings, secrets)
    val consult = ConsultService(context, settings, router)
    val mcp = McpManager(secrets)
    val engineManager = com.sumaho.agent.engine.EngineManager(context, settings, secrets)
    val engine = AgentEngine(this)

    private val builtins: List<AgentTool> by lazy {
        listOf(
            WebSearchTool(settings, secrets),
            FetchUrlTool(),
            RunJsTool(context),
            MemoSaveTool(notes),
            MemoSearchTool(notes),
            MemoReadTool(notes),
            MemoDeleteTool(notes),
            MemorySaveTool(notes),
            MemoryForgetTool(notes),
            WorkspaceListTool(workspace),
            WorkspaceReadTool(workspace),
            WorkspaceWriteTool(workspace),
            DocumentListTool(attachments),
            DocumentReadTool(attachments),
            ConsultExternalTool(consult),
        )
    }

    fun builtinTools(): List<AgentTool> = builtins

    fun refreshMcp() {
        scope.launch { mcp.refresh(settings.current.mcpServers) }
    }
}
