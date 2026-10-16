package org.fossify.phone.sms

import android.content.Context

/**
 * What the Telephony provider has no column for: which threads are pinned or archived, the SIM the
 * owner prefers, and the SIM each thread last sent on. Plain SharedPreferences, app private.
 */
class SmsPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud_dialer_sms", Context.MODE_PRIVATE)

    var pinned: Set<Long>
        get() = longs(KEY_PINNED)
        set(value) = putLongs(KEY_PINNED, value)

    var archived: Set<Long>
        get() = longs(KEY_ARCHIVED)
        set(value) = putLongs(KEY_ARCHIVED, value)

    /** The "always use this SIM" choice; null = ask / follow the system default. */
    var preferredSubscription: Int?
        get() = prefs.getInt(KEY_SIM, NONE).takeIf { it != NONE }
        set(value) { prefs.edit().putInt(KEY_SIM, value ?: NONE).apply() }

    fun lastSubscription(threadId: Long): Int? = prefs.getInt(KEY_THREAD_SIM + threadId, NONE).takeIf { it != NONE }

    fun setLastSubscription(threadId: Long, subscriptionId: Int) {
        prefs.edit().putInt(KEY_THREAD_SIM + threadId, subscriptionId).apply()
    }

    fun togglePinned(threadId: Long) { pinned = pinned.toggle(threadId) }

    fun setArchived(threadId: Long, archive: Boolean) {
        archived = if (archive) archived + threadId else archived - threadId
    }

    private fun longs(key: String): Set<Long> =
        (prefs.getStringSet(key, emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()

    private fun putLongs(key: String, value: Set<Long>) {
        prefs.edit().putStringSet(key, value.map { it.toString() }.toSet()).apply()
    }

    private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

    private companion object {
        const val KEY_PINNED = "pinned"
        const val KEY_ARCHIVED = "archived"
        const val KEY_SIM = "preferred_sim"
        const val KEY_THREAD_SIM = "thread_sim_"
        const val NONE = Int.MIN_VALUE
    }
}
