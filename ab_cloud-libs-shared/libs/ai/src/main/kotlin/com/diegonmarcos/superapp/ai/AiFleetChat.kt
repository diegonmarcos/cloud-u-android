package com.diegonmarcos.superapp.ai

import android.content.Context
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * The fleet's standard hosted chat, so an app that only wants "the AI chat, over the fleet gateway, with
 * history" writes none of the wiring (Cloud Drive's DriveAi was the first copy of it; the shared top bar's AI
 * panel is the second): ONE [AiChatState] per process, its sessions persisted in `<filesDir>/ai-sessions.json`,
 * the model and agent choices in memory, answers through the fleet gateway at [gatewayUrl]
 * (BuildConfig.AI_GATEWAY_URL, baked by ai-gateway.gradle). No token is held or read here (decisions-use guard D5):
 * the gateway holds the fleet key. The conversation outlives the panel or tab that shows it.
 */
object AiFleetChat {
    @Volatile private var chat: AiChatState? = null

    fun chat(ctx: Context, gatewayUrl: String): AiChatState =
        chat ?: synchronized(this) { chat ?: build(ctx.applicationContext, gatewayUrl).also { chat = it } }

    private fun build(ctx: Context, gatewayUrl: String): AiChatState {
        val cfg = AiConfig.load(ctx)
        return AiChatState(AiStandardHost(
            cfg, AiFileSessions(File(ctx.filesDir, "ai-sessions.json")), AiMemoryPrefs(cfg.defaultModel),
            gateway = AiGatewayBackend(cfg, gatewayUrl), agents = AiAgents.load(ctx),
        ))
    }
}

/**
 * The shared chat page for a container that is not a whole screen (the top bar's AI panel): the page under the app's
 * Material colours with the Sessions list on, lifted above the keyboard, over the process's one [AiFleetChat.chat].
 */
@Composable
fun AiFleetChatPanel(gatewayUrl: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val chat = remember(gatewayUrl) { AiFleetChat.chat(ctx, gatewayUrl) }
    AiChatPage(chat, materialAiTheme(), modifier.imePadding(), sessionsList = true)
}
