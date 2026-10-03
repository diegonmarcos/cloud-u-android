package com.diegonmarcos.superapp.searchpage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** #823 one chat message: role user | assistant. */
data class SpMsg(val role: String, val content: String)

/** One chat session as the host stores it. */
data class SpSession(val id: String, val title: String, val model: String, val updated: Long, val messages: List<SpMsg>)

/** One model of the host's catalogue: [nativeWeb] = it searches the web by itself; [free] = no cost. */
data class SpModel(val id: String, val name: String, val nativeWeb: Boolean, val free: Boolean)

/** One declared search engine: its box on the page. [icon]/[accent] are names the theme resolves. */
data class SpEngine(val id: String, val label: String, val icon: String, val accent: String)

/** What one send ended with: the session as stored, the reason there is no answer, the answer's sources. */
data class SpOutcome(val session: SpSession, val error: String?, val citations: List<String>)

/**
 * What the page needs from the app it runs in. The page knows no network, no store and no token:
 * the host sends (reading its token per call), keeps the sessions, lists the models and opens a
 * URL (Cloud Search: in Cloud Browser; the browser: as a tab). [send] and [models] block — the
 * page calls them off the main thread.
 */
interface SearchPageHost {
    /** The model and Web switch he last picked. */
    var model: String
    var web: Boolean
    fun newSession(model: String, now: Long): SpSession
    fun models(): List<SpModel>
    fun send(session: SpSession, text: String, model: String, web: Boolean, now: Long): SpOutcome
    fun openUrl(url: String)
}

/** The chat's live state: the open session, what is being sent, the last error, the catalogue. */
class SearchChatState(private val host: SearchPageHost) {
    var session by mutableStateOf(host.newSession(host.model, System.currentTimeMillis()))
    var sending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var citations by mutableStateOf<List<String>>(emptyList())
    var models by mutableStateOf<List<SpModel>>(emptyList())
    var model by mutableStateOf(host.model)
    var web by mutableStateOf(host.web)

    fun pick(id: String) { model = id; host.model = id }
    fun toggleWeb() { web = !web; host.web = web }
    fun fresh() { session = host.newSession(model, System.currentTimeMillis()); error = null; citations = emptyList() }
    fun open(s: SpSession) { session = s; error = null; citations = emptyList(); if (s.model.isNotBlank()) model = s.model }

    /** His message shown at once; the answer (or why there is none) once [SearchPageHost.send] returns. Blocking part off-main. */
    fun begin(ask: String): SpSession? {
        if (ask.isEmpty() || sending) return null
        sending = true
        error = null
        val before = session
        session = before.copy(messages = before.messages + SpMsg("user", ask))
        return before
    }

    fun finish(out: SpOutcome) {
        session = out.session
        error = out.error
        citations = out.citations
        sending = false
    }

    fun host(): SearchPageHost = host
}

/** The test tags the page is driven by (Cloud Search's SearchShellTest, the browser's checks). */
object SearchPageTags {
    const val PAGE = "search_page_assistant"
    const val CHAT_INPUT = "search_chat_input"
    const val CHAT_SEND = "search_chat_send"
    const val NEW_CHAT = "search_new_chat"
    const val MODEL = "search_model"
    const val WEB = "search_web_toggle"
    fun engine(id: String) = "search_engine_$id"
}
