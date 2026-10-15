package com.diegonmarcos.clouddrive.ai

import android.content.Context
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.superapp.ai.AiAgents
import com.diegonmarcos.superapp.ai.AiChatPage
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiFileSessions
import com.diegonmarcos.superapp.ai.AiGatewayBackend
import com.diegonmarcos.superapp.ai.AiMemoryPrefs
import com.diegonmarcos.superapp.ai.AiStandardHost
import com.diegonmarcos.superapp.ai.materialAiTheme
import java.io.File

/**
 * AI: the fleet's AI chat (libs:ai) as a tab. The page, the Agent and Model pickers, Sessions and the streaming
 * are the lib's; this app supplies only the road out: the fleet agent gateway (my-ai-api, mesh only; its address is
 * baked as BuildConfig.AI_GATEWAY_URL), where the agent picked in the page answers. No token is held or read here:
 * the gateway holds the fleet key (decisions-use guard D5). The conversation outlives switching tabs.
 */
@Composable
fun AiScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current.applicationContext
    val chat = remember { DriveAi.chat(ctx) }
    AiChatPage(chat, materialAiTheme(), modifier.imePadding().testTag(DriveTags.AI), sessionsList = true)
}

/** The one chat of the process. */
object DriveAi {
    @Volatile private var chat: AiChatState? = null

    fun chat(ctx: Context): AiChatState = chat ?: synchronized(this) { chat ?: build(ctx).also { chat = it } }

    private fun build(ctx: Context): AiChatState {
        val cfg = AiConfig.load(ctx)
        return AiChatState(AiStandardHost(
            cfg, AiFileSessions(File(ctx.filesDir, "ai-sessions.json")), AiMemoryPrefs(cfg.defaultModel),
            gateway = AiGatewayBackend(cfg, BuildConfig.AI_GATEWAY_URL), agents = AiAgents.load(ctx),
        ))
    }
}
