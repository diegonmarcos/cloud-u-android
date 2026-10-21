// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * #1077: Auto Correction and Grammar Check are ONE feature with one mode. Auto Correct fixes silently
 * (dictionary auto-correction plus the grammar fixes), Highlight only marks what it would change and
 * leaves the text alone, Off does neither. This is the single source of truth: the legacy
 * `auto_correction` boolean is only READ when no mode was ever stored, to derive the user's mode.
 */
enum class GrammarMode(val id: String) {
    AUTO_CORRECT("auto_correct"), HIGHLIGHT("highlight"), OFF("off");

    /** The tap order of the toolbar's Grammar mode icon. */
    fun next(): GrammarMode = when (this) { HIGHLIGHT -> AUTO_CORRECT; AUTO_CORRECT -> OFF; OFF -> HIGHLIGHT }

    val autoCorrects get() = this == AUTO_CORRECT
    val checksGrammar get() = this != OFF

    companion object {
        fun fromId(id: String?): GrammarMode? = entries.firstOrNull { it.id == id }

        /** The mode for stored values; [legacyAutoCorrect] is the old boolean's value (default true), [legacyEngine] the old grammar engine. */
        fun resolve(stored: String?, legacyAutoCorrect: Boolean, legacyEngine: String?): GrammarMode =
            fromId(stored) ?: when {
                legacyAutoCorrect -> AUTO_CORRECT
                legacyEngine != null && legacyEngine != "off" -> HIGHLIGHT
                else -> OFF
            }

        @JvmStatic
        fun read(prefs: SharedPreferences): GrammarMode = resolve(
            prefs.getString(Settings.PREF_GRAMMAR_CHECK_MODE, null),
            prefs.getBoolean(Settings.PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION),
            prefs.getString(Settings.PREF_GRAMMAR_MODE, Defaults.PREF_GRAMMAR_MODE),
        )

        @JvmStatic
        fun write(prefs: SharedPreferences, mode: GrammarMode) = prefs.edit { putString(Settings.PREF_GRAMMAR_CHECK_MODE, mode.id) }
    }
}

/** Who runs an engine: inside this app (on the phone) or somewhere else. */
enum class EngineOrigin { NATIVE, EXTERNAL }

/**
 * #1077: every engine the merged Grammar Check uses, declared once. The settings page draws its list from
 * this and the tests pin it, so the page and the code cannot disagree about what runs.
 * [wired] = false marks an engine that is declared but not connected yet.
 */
data class GrammarEngine(val id: String, val title: String, val what: String, val origin: EngineOrigin, val wired: Boolean = true)

object GrammarEngines {
    val ALL = listOf(
        GrammarEngine("dictionary", "Dictionary", "Word lists and the native decoder behind suggestions and Auto Correct", EngineOrigin.NATIVE),
        GrammarEngine("spellchecker", "Spell checker", "The keyboard's own Android spell-checker service, same dictionaries", EngineOrigin.NATIVE),
        GrammarEngine("ngram", "N-gram context", "Bigram data in the dictionaries ranks the next word and the correction", EngineOrigin.NATIVE),
        GrammarEngine("rules", "Built-in rules", "Capitalise I, capitalise the first word, remove a repeated word", EngineOrigin.NATIVE),
        GrammarEngine("personal", "Personal dictionary", "Words you add or the keyboard learns on this phone", EngineOrigin.NATIVE),
        GrammarEngine("languagetool", "LanguageTool server", "LanguageTool-compatible rules over the network (Grammar engine: Remote)", EngineOrigin.EXTERNAL),
        GrammarEngine("ai", "AI Model Routing", "The configured AI provider with a grammar-only prompt (Grammar engine: AI)", EngineOrigin.EXTERNAL),
        GrammarEngine("ngram-api", "N-gram confusion-pair service", "Optional server for confusing word pairs; the URL is stored but nothing calls it yet", EngineOrigin.EXTERNAL, wired = false),
        GrammarEngine("ondevice-ml", "On-device ML", "No on-device grammar model ships in the keyboard; the only on-device ML here is ML Kit translation, which is not a grammar engine", EngineOrigin.NATIVE, wired = false),
    )
}
