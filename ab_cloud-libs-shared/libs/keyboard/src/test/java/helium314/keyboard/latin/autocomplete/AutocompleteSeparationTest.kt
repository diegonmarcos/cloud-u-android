// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocomplete

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1103 items 1, 2 and 4, read from the source: Autocomplete is its own engine and database. It has no vault dependency,
 * no network code, no link to or from the clipboard, and a tab strip of its own.
 */
class AutocompleteSeparationTest {
    private val main = File("src/main/java/helium314/keyboard")
    private fun text(vararg path: String) = File(main, path.joinToString("/")).readText()
    private fun kt(dir: String) = File(main, dir).listFiles { f -> f.extension == "kt" }!!.toList()
    private fun imports(f: File) = f.readLines().filter { it.startsWith("import ") }
    private fun code(f: File) = f.readText().replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("(?m)//.*$"), "")

    private val engineFiles get() = kt("latin/autocomplete") + File(main, "keyboard/autocomplete/AutocompleteView.kt")

    @Test fun theEngineHasNoVaultDependency() {
        for (f in engineFiles) {
            assertFalse("${f.name} names the vault", code(f).contains("cloudvault", ignoreCase = true) || code(f).contains("launchCloudVault") || code(f).contains("KeyCode.VAULT"))
            assertFalse("${f.name} reads vault types", imports(f).any { it.contains("bitwarden", true) || it.contains(".vault", true) })
        }
        assertFalse(File(main, "latin/autofill/AutofillSchemas.kt").exists()) // the vault field tables went with the old page
        assertFalse(File(main, "latin/autofill/AutocompleteItems.kt").exists())
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("autocomplete") && manifest.contains("cloudvault"))
    }

    @Test fun theEngineNeverTouchesTheNetwork() {
        val banned = listOf("java.net.", "okhttp3", "HttpURLConnection", "URL(", "HttpClient", "FleetHttp", "FleetEndpoints", "WebView")
        for (f in engineFiles) for (b in banned) assertFalse("${f.name} uses $b", code(f).contains(b))
    }

    @Test fun theEngineHasNoLinkToTheClipboardAndTheClipboardNoneToTheEngine() {
        for (f in engineFiles) {
            val c = code(f)
            for (b in listOf("ClipboardHistory", "ClipboardDao", "ClipboardHistoryManager", "keyboard.clipboard", "ClipboardContentProvider"))
                assertFalse("${f.name} reaches into the clipboard: $b", c.contains(b))
            if (f.name != "AutocompleteView.kt") assertFalse("${f.name} sets the primary clip", c.contains("setPrimaryClip"))
        }
        val clipboardSide = kt("keyboard/clipboard") + File(main, "latin/ClipboardHistoryManager.kt") + File(main, "latin/database/ClipboardDao.kt") +
            File(main, "latin/ClipboardHistoryEntry.kt")
        val engineTypes = listOf("AutocompleteStore", "AutocompleteEngine", "AutocompleteView", "AutocompletePage", "AutocompleteUnlock",
            "AutocompleteSync", "AutocompleteContract", "AutocompletePanel", "AutofillSot")
        for (f in clipboardSide) for (b in engineTypes) assertFalse("${f.name} points at Autocomplete: $b", f.readText().contains(b))
        assertFalse("the clipboard panel has no Autocomplete tab or mode left",
            File(main, "keyboard/clipboard/ClipboardHistoryView.kt").readText().contains("utocomplete"))
        // the one thing the panels share is the geometry and the row background, from a neutral package
        val shared = imports(File(main, "keyboard/autocomplete/AutocompleteView.kt")).filter { it.contains("keyboard.panel.") }
        assertEquals(listOf("import helium314.keyboard.keyboard.panel.PanelLayoutParams", "import helium314.keyboard.keyboard.panel.drawableBackground"), shared.sorted())
    }

    @Test fun theKeyIsItsOwnPanelWithItsOwnTabsNotAPageOfTheClipboard() {
        val state = text("keyboard/internal/KeyboardState.kt")
        assertTrue(state.contains("KeyCode.AUTOCOMPLETE -> setAutocompleteKeyboard()"))
        assertFalse(state.contains("AutocompletePanel"))
        assertFalse(File(main, "keyboard/clipboard/AutocompletePanel.kt").exists())
        val view = text("keyboard/autocomplete/AutocompleteView.kt")
        assertTrue(view.contains("AutocompletePage.entries.forEach"))
        val layout = File("src/main/res/layout/main_keyboard_frame.xml").readText()
        assertTrue(layout.contains("@+id/autocomplete_view") && layout.contains("keyboard.autocomplete.AutocompleteView"))
    }

    @Test fun theStoreIsLocalAndEncryptedAtRest() {
        val store = text("latin/autocomplete/AutocompleteStore.kt")
        assertTrue(store.contains("AndroidKeyStore") && store.contains("AES/GCM/NoPadding"))
        assertTrue(store.contains("getSharedPreferences(PREFS"))
    }

    @Test fun theRowsComeFromCloudAccountsProviderAndTheKeyboardOnlyAsksOnce() {
        val sync = text("latin/autocomplete/AutocompleteSync.kt")
        assertTrue(sync.contains("contentResolver.call("))
        assertTrue(sync.contains("AutocompleteContract.AUTHORITY"))
        assertEquals("only the provider call, nowhere else", 1, Regex("contentResolver").findAll(sync).count())
    }
}
