package com.diegonmarcos.cloudkgstore

import android.content.Context
import com.diegonmarcos.superapp.ai.AiAgents
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiDesign
import com.diegonmarcos.superapp.ai.AiFileSessions
import com.diegonmarcos.superapp.ai.AiGatewayBackend
import com.diegonmarcos.superapp.ai.AiMemoryPrefs
import com.diegonmarcos.superapp.ai.AiStandardHost
import java.io.File

/**
 * The assistant behind the AI icon: one libs:ai chat over the fleet agent gateway (my-ai-api, mesh only; its address is
 * BuildConfig.AI_GATEWAY_URL, baked from the fleet catalogue) with the sessions in `<filesDir>/ai-sessions.json`. The system
 * prompt and MCPs are assets/kg/kg.json::ask; the prompt rides every request and is never stored. No token is held or read here
 * (decisions-use guard D5): the gateway holds the fleet keys.
 */
object KgAi {
    private var chat: AiChatState? = null
    private var sessions: AiFileSessions? = null

    @Synchronized fun chat(ctx: Context): AiChatState = chat ?: build(ctx.applicationContext).also { chat = it }

    private fun build(app: Context): AiChatState {
        val ask = KgServices.get(app).kg.ask
        val cfg = AiConfig.load(app)
        val store = sessions ?: AiFileSessions(File(app.filesDir, "ai-sessions.json")).also { sessions = it }
        return AiChatState(AiStandardHost(
            cfg, store, AiMemoryPrefs(cfg.defaultModel, false, ""),
            gateway = AiGatewayBackend(cfg, BuildConfig.AI_GATEWAY_URL), agents = AiAgents.load(app),
            system = ask.system, design = AiDesign.load(app),
        )).also { it.chooseMcps(ask.mcps.toSet()) }
    }
}
