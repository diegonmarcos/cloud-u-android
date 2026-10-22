// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.utils.adoptToolbarLayoutRevision

/**
 * #1077: when a shipped default changes, a phone with NOTHING stored picks up the new default by itself.
 * Only a phone whose stored value is the OLD default needs help: that value is dropped so the new default
 * applies. A user who moved any setting of a group away from its old default keeps the whole group.
 * Runs once per [REVISION]; bump it and add a [Group] for the next change.
 */
object DefaultsMigration {
    /** 1: #1077 grammar fixes and space-bar language switch; 2: #1103 the first toolbar row is re-ordered (toolbar layout revision 4). */
    const val REVISION = 2

    /** [keys] move to their new default together; [oldDefault] is the value they were shipped with; [since] is the revision that introduced the group. */
    class Group(val keys: List<String>, val oldDefault: Boolean, val since: Int = 1)

    /** The revision whose step adopts the shipped toolbar rows ([TOOLBAR_LAYOUT_REVISION]), once, for a phone still on an older arrangement. */
    const val TOOLBAR_STEP_SINCE = 2

    val groups = listOf(
        Group(listOf(Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS, Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS,
            Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I), oldDefault = true),
        Group(listOf(Settings.PREF_SPACE_TO_CHANGE_LANG), oldDefault = true),
    )

    /** The stored keys to drop, given everything stored, for the groups newer than [fromRevision] (a phone already past a group is never moved by it again). Pure. */
    fun keysToReset(stored: Map<String, *>, fromRevision: Int = 0): List<String> = groups.filter { it.since > fromRevision }.flatMap { g ->
        if (g.keys.all { stored[it] == null || stored[it] == g.oldDefault }) g.keys.filter { stored.containsKey(it) } else emptyList()
    }

    /** True when the step that adopts the shipped toolbar rows has not run on a phone at [fromRevision]. Pure. */
    fun adoptsToolbarLayout(fromRevision: Int): Boolean = fromRevision < TOOLBAR_STEP_SINCE

    @JvmStatic
    fun apply(prefs: SharedPreferences) {
        val from = prefs.getInt(Settings.PREF_DEFAULTS_REVISION, 0)
        if (from >= REVISION) return
        val reset = keysToReset(prefs.all, from)
        prefs.edit {
            reset.forEach { remove(it) }
            putInt(Settings.PREF_DEFAULTS_REVISION, REVISION)
        }
        // the first and second toolbar rows move to the shipped arrangement once (it discards a hand-made one, by design)
        if (adoptsToolbarLayout(from)) adoptToolbarLayoutRevision(prefs)
    }
}
