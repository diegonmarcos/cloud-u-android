// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * #1077: when a shipped default changes, a phone with NOTHING stored picks up the new default by itself.
 * Only a phone whose stored value is the OLD default needs help: that value is dropped so the new default
 * applies. A user who moved any setting of a group away from its old default keeps the whole group.
 * Runs once per [REVISION]; bump it and add a [Group] for the next change.
 */
object DefaultsMigration {
    const val REVISION = 1

    /** [keys] move to their new default together; [oldDefault] is the value they were shipped with. */
    class Group(val keys: List<String>, val oldDefault: Boolean)

    val groups = listOf(
        Group(listOf(Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS, Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS,
            Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I), oldDefault = true),
        Group(listOf(Settings.PREF_SPACE_TO_CHANGE_LANG), oldDefault = true),
    )

    /** The stored keys to drop, given everything stored. Pure. */
    fun keysToReset(stored: Map<String, *>): List<String> = groups.flatMap { g ->
        if (g.keys.all { stored[it] == null || stored[it] == g.oldDefault }) g.keys.filter { stored.containsKey(it) } else emptyList()
    }

    @JvmStatic
    fun apply(prefs: SharedPreferences) {
        if (prefs.getInt(Settings.PREF_DEFAULTS_REVISION, 0) >= REVISION) return
        val reset = keysToReset(prefs.all)
        prefs.edit {
            reset.forEach { remove(it) }
            putInt(Settings.PREF_DEFAULTS_REVISION, REVISION)
        }
    }
}
