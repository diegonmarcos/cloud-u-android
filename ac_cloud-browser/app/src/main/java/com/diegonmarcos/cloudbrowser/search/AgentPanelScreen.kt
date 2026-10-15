package com.diegonmarcos.cloudbrowser.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diegonmarcos.superapp.ai.AiAssistantPanel
import com.diegonmarcos.superapp.ai.AiConsentRequest
import com.diegonmarcos.superapp.ai.AiPanelLine
import com.diegonmarcos.superapp.ai.AiPanelTags
import com.diegonmarcos.superapp.browser.AgentPanelState
import com.diegonmarcos.superapp.browser.BrowserAgentPanelHost
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #823 the assistant's side panel: libs:ai's AiAssistantPanel over the page, in the browser's kit palette. The
 * conversation, the agent loop and the tools stay the browser's ([AgentRunner], [AgentPanelState]); the panel
 * only draws them and reports a tap. Its consent card is the ONLY place a waiting action is decided.
 */
object AgentPanelScreen {
    private val tags = AiPanelTags(
        panel = "browser:agent", newChat = "browser:agent:new", close = "browser:agent:close", summarize = "browser:agent:summarize",
        input = "browser:agent:input", send = "browser:agent:send", consent = "browser:agent:confirm",
        allow = "browser:agent:allow", deny = "browser:agent:deny",
    )

    fun install() {
        BrowserAgentPanelHost.panel = { state, actions ->
            val p = LocalKitPalette.current
            AiAssistantPanel(
                lines = state.lines.map { AiPanelLine(it.role, it.text) },
                busy = state.busy,
                pending = state.pending?.let { AiConsentRequest(it.callId, "", it.sentence) },
                onSend = actions.onSend,
                onDecide = { allow -> state.pending?.let { actions.onDecide(it, allow) } },
                onNewChat = actions.onNewChat,
                onClose = actions.onClose,
                theme = kitAiTheme(p),
                onSummarize = actions.onSummarize,
                tags = tags,
            )
        }
    }
}
