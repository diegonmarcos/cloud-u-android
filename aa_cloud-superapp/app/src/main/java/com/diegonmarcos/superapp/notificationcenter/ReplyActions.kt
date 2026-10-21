package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

/**
 * sbn.key -> the notification's inline-reply action (the one carrying a [RemoteInput]), filled by the listener. The Notify
 * and Boards ▸ Inbox boxes' Reply pill sends the typed text through it, so a reply goes out from the box without opening
 * the app. In memory like ThreadIntents: a PendingIntent cannot be persisted, and after a restart the pill falls back to
 * opening the thread.
 */
object ReplyActions {
    private val map = ConcurrentHashMap<String, Notification.Action>()
    private const val MAX = 200

    fun capture(key: String, n: Notification) {
        val a = n.actions?.firstOrNull { act -> act.remoteInputs?.isNotEmpty() == true && act.actionIntent != null } ?: return
        if (map.size >= MAX) map.keys.firstOrNull()?.let { map.remove(it) }
        map[key] = a
    }

    /** Is there an inline reply for this notification key right now? */
    fun has(key: String?): Boolean = key != null && map.containsKey(key)

    /** Sends [text] through the captured reply action. False when there is none or the app refused it. */
    fun send(ctx: Context, key: String?, text: String): Boolean {
        val a = key?.let { map[it] } ?: return false
        val ri = a.remoteInputs?.firstOrNull() ?: return false
        return runCatching {
            val fill = Intent()
            RemoteInput.addResultsToIntent(a.remoteInputs, fill, Bundle().apply { putCharSequence(ri.resultKey, text) })
            a.actionIntent.send(ctx, 0, fill)
            true
        }.getOrDefault(false)
    }
}
