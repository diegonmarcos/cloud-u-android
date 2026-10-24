package com.diegonmarcos.cloudsuperintel

import android.content.Context
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiFleetChat

/**
 * The one chat of the process, libs:ai's standard hosted chat over the fleet agent gateway (my-ai-api, mesh only;
 * its address is BuildConfig.AI_GATEWAY_URL, baked from the fleet catalogue). Sessions persist in
 * `<filesDir>/ai-sessions.json`. No token is held or read here (decisions-use guard D5): the gateway holds the
 * fleet keys, and picks Claude (claude-superset-api on oci-apps), Hermes, Goose, OpenClaw or an OpenRouter model
 * by the agent the page picked.
 */
object SiAi {
    fun chat(ctx: Context): AiChatState = AiFleetChat.chat(ctx, BuildConfig.AI_GATEWAY_URL)
}
