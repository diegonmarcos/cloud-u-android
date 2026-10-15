package com.diegonmarcos.superapp.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * #823 the seam to the assistant's side panel. The panel itself (the transcript, the field, Summarize, New chat and
 * the consent card) is libs:ai's AiAssistantPanel: the APP draws it (libs:browser links no AI chat UI), the screen
 * shows it over the page. The confirm-before-mutate policy is unchanged: a mutating tool stops the turn
 * ([AgentLoop]); the card names the exact action and site; nothing runs until he taps Allow, and Deny tells the model
 * "denied". There is still no allow outside that card.
 */

/** The panel's state; the host keeps one so the conversation survives closing the panel. */
class AgentPanelState {
    val lines = mutableStateListOf<AgentLine>()
    var pending by mutableStateOf<AgentPending?>(null)
    var busy by mutableStateOf(false)
}

/** What the panel does: the host decides (it owns the agent loop), the panel only reports a tap. */
class AgentPanelActions(
    val onSend: (String) -> Unit,
    val onDecide: (AgentPending, Boolean) -> Unit,
    val onSummarize: () -> Unit,
    val onNewChat: () -> Unit,
    val onClose: () -> Unit,
)

object BrowserAgentPanelHost {
    /** Unset = the assistant row says the panel is not available in this app. */
    @Volatile var panel: (@Composable (state: AgentPanelState, actions: AgentPanelActions) -> Unit)? = null
}
