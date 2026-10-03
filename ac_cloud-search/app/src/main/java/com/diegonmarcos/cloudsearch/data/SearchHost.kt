package com.diegonmarcos.cloudsearch.data

import com.diegonmarcos.cloudsearch.core.Chat
import com.diegonmarcos.superapp.searchpage.SearchPageHost
import com.diegonmarcos.superapp.searchpage.SpModel
import com.diegonmarcos.superapp.searchpage.SpMsg
import com.diegonmarcos.superapp.searchpage.SpOutcome
import com.diegonmarcos.superapp.searchpage.SpSession

/**
 * #823 Cloud Search as the host of libs:search-page's Search page: the chat runs [ChatFlow] (the
 * token read from the fleet Account per send, the session stored in [Services.sessions]), the
 * catalogue is [Services.models], the model and Web switch are [Services.prefs], and a URL opens
 * in Cloud Browser ([Browser]). Behaviour is the page's own from before the move.
 */
class SearchHost(private val s: Services) : SearchPageHost {
    override var model: String
        get() = s.prefs.model
        set(v) { s.prefs.model = v }
    override var web: Boolean
        get() = s.prefs.web
        set(v) { s.prefs.web = v }

    override fun newSession(model: String, now: Long): SpSession = ChatFlow.newSession(model, now).sp()

    override fun models(): List<SpModel> = s.models.models().map { SpModel(it.id, it.name, it.nativeWeb, it.free) }

    override fun send(session: SpSession, text: String, model: String, web: Boolean, now: Long): SpOutcome =
        ChatFlow.send(s.app, s, session.chat(), text, model, web, now).let { SpOutcome(it.session.sp(), it.error, it.citations) }

    override fun openUrl(url: String) = Browser.open(s.app, url)
}

/** A stored session as the page shows it, and back. */
fun Chat.Session.sp() = SpSession(id, title, model, updated, messages.map { SpMsg(it.role, it.content) })
fun SpSession.chat() = Chat.Session(id, title, model, updated, messages.map { Chat.Msg(it.role, it.content) })
