package com.diegonmarcos.cloudsearch.data

import android.content.Context
import com.diegonmarcos.cloudsearch.core.Chat
import java.util.UUID

/**
 * One send of the AI chat: the user's message appended, the token read from the fleet Account,
 * the request posted, the answer (or the reason there is none) appended, the session stored.
 * Blocking — call it off the main thread. The token lives in a local for the length of the call.
 */
object ChatFlow {
    data class Outcome(val session: Chat.Session, val error: String?, val citations: List<String>)

    fun newSession(model: String, now: Long): Chat.Session = Chat.Session(UUID.randomUUID().toString(), "", model, now, emptyList())

    fun send(ctx: Context, s: Services, session: Chat.Session, text: String, model: String, web: Boolean, now: Long): Outcome {
        val ai = s.cfg.ai
        val asked = session.copy(
            title = session.title.ifBlank { Chat.title(text, ai.titleChars) },
            model = model, updated = now,
            messages = session.messages + Chat.Msg("user", text),
        )
        s.sessions.put(asked)
        val token = Account.token(ctx, ai.accountProvider)
        if (token.value == null) return Outcome(asked, token.why, emptyList())
        val nativeWeb = s.models.models().firstOrNull { it.id == model }?.nativeWeb ?: false
        val res = runCatching {
            s.http.post(ai.chatUrl, Chat.headers(ai, token.value), Chat.body(ai, model, asked.messages, web, nativeWeb), ai.timeoutMs)
        }
        val r = res.getOrNull() ?: return Outcome(asked, res.exceptionOrNull()?.message ?: "no answer", emptyList())
        val reply = Chat.reply(r.body)
        val text2 = reply.text
        if (text2 == null) return Outcome(asked, reply.error ?: "HTTP ${r.code}", emptyList())
        val answered = asked.copy(messages = asked.messages + Chat.Msg("assistant", text2), updated = System.currentTimeMillis())
        s.sessions.put(answered)
        return Outcome(answered, null, reply.citations)
    }
}
