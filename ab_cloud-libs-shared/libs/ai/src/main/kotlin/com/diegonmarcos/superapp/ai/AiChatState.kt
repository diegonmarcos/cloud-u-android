package com.diegonmarcos.superapp.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture

/** The chat's live state: the open session, the answer streaming in, what is being sent, the last error, the catalogue, the question waiting for the user. */
class AiChatState(private val host: AiHost) {
    var session by mutableStateOf(host.newSession(host.model, System.currentTimeMillis()))
    var sending by mutableStateOf(false)
    /** The answer so far, while it streams (empty when nothing has arrived or the host does not stream). */
    var streaming by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var citations by mutableStateOf<List<String>>(emptyList())
    var models by mutableStateOf<List<AiModel>>(emptyList())
    var model by mutableStateOf(host.model)
    var web by mutableStateOf(host.web)
    var agent by mutableStateOf(host.agent())
    /** The input's text: hoisted so an app can prefill it (a shared text, a task's example) and nothing is sent until the user does. */
    var draft by mutableStateOf("")
    /** A mutating tool waiting for Allow / Deny; the page draws the consent card for it. */
    var consent by mutableStateOf<AiConsentRequest?>(null)
        private set

    private var decision: CompletableFuture<Boolean>? = null

    fun chooseAgent(id: String) { agent = id; host.pickAgent(id) }
    fun pick(id: String) { model = id; host.model = id }
    fun toggleWeb() { web = !web; host.web = web }
    fun fresh() { session = host.newSession(model, System.currentTimeMillis()); error = null; citations = emptyList(); streaming = "" }
    fun open(s: AiSession) { session = s; error = null; citations = emptyList(); streaming = ""; if (s.model.isNotBlank()) model = s.model }

    /** Renames a stored session; the open one follows. A blank title is ignored. */
    fun rename(id: String, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        host.renameSession(id, t)
        if (session.id == id) session = session.copy(title = t)
    }

    /** Deletes a stored session; deleting the open one starts a fresh chat. */
    fun remove(id: String) {
        host.deleteSession(id)
        if (session.id == id) fresh()
    }

    /** The user's answer to the waiting consent card. */
    fun decide(allow: Boolean) {
        val d = decision
        decision = null
        consent = null
        d?.complete(allow)
    }

    private val sink = object : AiSink {
        override fun delta(text: String) { streaming += text }
        override fun consent(request: AiConsentRequest): Boolean {
            val d = CompletableFuture<Boolean>()
            decision = d
            consent = request
            return try { d.get() } catch (e: Exception) { false }
        }
    }

    /**
     * His message shown at once; the answer streams in; the outcome (or why there is none) once the host returns.
     * Suspends until then; the blocking part runs off the main thread. A send while another is on its way, or an
     * empty one, does nothing.
     */
    suspend fun send(ask: String) {
        if (ask.isEmpty() || sending) return
        sending = true
        error = null
        streaming = ""
        citations = emptyList()
        val before = session
        session = before.copy(messages = before.messages + AiMsg("user", ask))
        val out = try {
            withContext(Dispatchers.IO) { host.send(before, ask, model, web, System.currentTimeMillis(), sink) }
        } catch (e: CancellationException) {
            sending = false
            decide(false)
            throw e
        } catch (e: Exception) {
            AiOutcome(session, e.message ?: e.javaClass.simpleName, emptyList())
        }
        session = out.session
        error = out.error
        citations = out.citations
        streaming = ""
        consent = null
        sending = false
    }

    fun host(): AiHost = host
}

/** The test tags the page is driven by (Cloud Search's SearchShellTest, the browser's checks). */
object AiTags {
    const val PAGE = "ai_page"
    const val CHAT_INPUT = "ai_chat_input"
    const val CHAT_SEND = "ai_chat_send"
    const val NEW_CHAT = "ai_new_chat"
    const val MODEL = "ai_model"
    const val WEB = "ai_web_toggle"
    const val AGENT = "ai_agent"
    const val SESSIONS = "ai_sessions"
    const val DRAWER = "ai_drawer"
    const val DRAWER_NEW = "ai_drawer_new"
    fun rename(id: String) = "ai_session_rename_$id"
    fun delete(id: String) = "ai_session_delete_$id"
    const val RENAME_FIELD = "ai_rename_field"
    const val RENAME_SAVE = "ai_rename_save"
    const val CONSENT = "ai_consent"
    const val CONSENT_ALLOW = "ai_consent_allow"
    const val CONSENT_DENY = "ai_consent_deny"
    fun agent(id: String) = "ai_agent_$id"
    fun engine(id: String) = "ai_engine_$id"
    fun session(id: String) = "ai_session_$id"
    fun suggestion(text: String) = "ai_suggestion_$text"
}
