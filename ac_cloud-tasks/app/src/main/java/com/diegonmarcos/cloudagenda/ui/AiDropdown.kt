package com.diegonmarcos.cloudagenda.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudagenda.BuildConfig
import com.diegonmarcos.cloudagenda.agent.SearchTasksTool
import com.diegonmarcos.cloudagenda.data.TasksRepository
import com.diegonmarcos.superapp.ai.AiAgents
import com.diegonmarcos.superapp.ai.AiChatPage
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiFileSessions
import com.diegonmarcos.superapp.ai.AiGatewayBackend
import com.diegonmarcos.superapp.ai.AiPrefs
import com.diegonmarcos.superapp.ai.AiStandardHost
import com.diegonmarcos.superapp.ai.AiStrings
import com.diegonmarcos.superapp.ai.materialAiTheme
import java.io.File

/** The model / Web / agent choices, kept across restarts (libs:ai's own prefs are in memory only). */
class SharedAiPrefs(private val sp: SharedPreferences, defaultModel: String) : AiPrefs {
    private val fallback = defaultModel
    override var model: String
        get() = sp.getString("model", fallback) ?: fallback
        set(v) { sp.edit().putString("model", v).apply() }
    override var web: Boolean
        get() = sp.getBoolean("web", false)
        set(v) { sp.edit().putBoolean("web", v).apply() }
    override var agent: String
        get() = sp.getString("agent", "") ?: ""
        set(v) { sp.edit().putString("agent", v).apply() }
}

/**
 * The app as a host of libs:ai. The page, the Agent and Model pickers, the Sessions list and the streaming are the
 * lib's. This app supplies the road out (the fleet agent gateway, mesh only: no token on the phone, BuildConfig
 * AI_GATEWAY_URL), the store -- sessions in ONE JSON file under filesDir, so the history is permanent and survives
 * restarts -- and a read-only tool, so the single box can search the tasks as well as chat.
 */
object TasksAi {
    @Volatile private var chat: AiChatState? = null

    fun chat(ctx: Context, repo: TasksRepository): AiChatState = chat ?: synchronized(this) { chat ?: build(ctx.applicationContext, repo).also { chat = it } }

    private fun build(ctx: Context, repo: TasksRepository): AiChatState {
        val cfg = AiConfig.load(ctx)
        val prefs = SharedAiPrefs(ctx.getSharedPreferences("tasks_ai", Context.MODE_PRIVATE), cfg.defaultModel)
        return AiChatState(AiStandardHost(
            cfg, AiFileSessions(File(ctx.filesDir, SESSIONS_FILE)), prefs,
            gateway = AiGatewayBackend(cfg, BuildConfig.AI_GATEWAY_URL), agents = AiAgents.load(ctx),
            tools = listOf(SearchTasksTool(repo)),
        ))
    }

    const val SESSIONS_FILE = "ai-sessions.json"
}

/**
 * The AI button's drop-down panel: ONE box for search and chat -- ask a question, or ask it to find a task (it may
 * look through the tasks; it never changes them). The conversation is a libs:ai session kept for good; the page's
 * Sessions menu reopens any earlier one.
 */
@Composable
fun AiDropdown(chat: AiChatState, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth().fillMaxHeight(0.72f).border(1.dp, c.outlineVariant).testTag(ChromeTags.AI_PANEL),
        color = c.surface, shadowElevation = 8.dp,
    ) {
        AiChatPage(
            chat, materialAiTheme(AiStrings(hello = "Search or ask", helloSub = "One box: ask me anything, or have me find a task.")),
            Modifier.imePadding(), sessionsList = true,
        )
    }
}
