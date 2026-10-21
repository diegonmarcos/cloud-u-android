// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.defaultToolbarPref
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1077 items 4, 5 and 8: the order of Configs, the three renamed labels, the first toolbar page. */
class ConfigsAndLabelsTest {
    private val res = File("src/main/res") // Gradle runs a module's tests from the module directory

    private fun string(file: File, name: String): String? =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(file.readText())?.groupValues?.get(1)

    @Test fun toolbarIsFirstInConfigsAndAutocompleteFollowsClipboard() {
        val order = ConfigsEntry.entries
        assertEquals(ConfigsEntry.TOOLBAR, order.first())
        assertEquals(ConfigsEntry.CLIPBOARD, order[1])
        assertEquals(ConfigsEntry.AUTOCOMPLETE, order[2])
        assertEquals(order.size, order.toSet().size)
    }

    @Test fun theThreeRenamesAreLabelsOnly() {
        val default = File(res, "values/strings.xml")
        assertEquals("Keyboard - first row icons", string(default, "toolbar_keys"))
        assertEquals("Keyboard - second row icons", string(default, "second_row_toolbar_keys"))
        assertEquals("Clipboard - first row icons", string(default, "clipboard_toolbar_keys"))
        // the setting keys are what is stored: they did not move
        assertEquals("toolbar_keys", helium314.keyboard.latin.settings.Settings.PREF_TOOLBAR_KEYS)
        assertEquals("pinned_toolbar_keys", helium314.keyboard.latin.settings.Settings.PREF_SECOND_ROW_TOOLBAR_KEYS)
        assertEquals("clipboard_toolbar_keys", helium314.keyboard.latin.settings.Settings.PREF_CLIPBOARD_TOOLBAR_KEYS)
    }

    @Test fun noTranslationKeepsTheOldLabels() {
        val old = listOf("toolbar_keys", "second_row_toolbar_keys", "clipboard_toolbar_keys", "settings_screen_grammar", "grammar_mode_title")
        res.listFiles { f -> f.name.startsWith("values-") }!!.forEach { dir ->
            val f = File(dir, "strings.xml")
            if (f.exists()) old.forEach { assertTrue("${dir.name} still overrides $it", string(f, it) == null) }
        }
    }

    @Test fun theGrammarPageAndItsEngineSettingAreNamedApart() {
        val default = File(res, "values/strings.xml")
        assertEquals("Grammar Check", string(default, "settings_screen_grammar"))
        assertEquals("Grammar mode", string(default, "grammar_check_mode_title"))
        assertEquals("Grammar engine", string(default, "grammar_mode_title"))
    }

    @Test fun theFirstToolbarPageHasOneGrammarModeIconAndTheAutocompleteIconInTheFreedSlot() {
        val enabled = defaultToolbarPref.split(Separators.ENTRY).filter { it.endsWith("true") }.map { it.substringBefore(Separators.KV) }
        assertEquals(listOf("FULL_LEFT", "GRAMMAR", "AUTOCOMPLETE", "ENHANCE"), enabled.take(4))
        assertEquals(1, enabled.count { it == "GRAMMAR" })
        assertTrue(ToolbarKey.entries.none { it.name == "AUTOCORRECT" }) // merged into Grammar mode
        assertFalse(defaultToolbarPref.contains("AUTOCORRECT"))
    }
}
