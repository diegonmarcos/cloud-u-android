package app.sterna.ui.settings

import app.sterna.core.data.account.SyncWindow
import app.sterna.core.data.account.syncWindowChanged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT for the plug, plus the decision EXECUTED beside it. `AccountsViewModel` is an
 */
class SyncWindowDropWiringTest {

    private fun setSyncWindowBody(): String {
        val source = ACCOUNTS_VM.readText()
        val start = source.indexOf("fun setSyncWindow(")
        assertTrue("AccountsViewModel has no setSyncWindow() — renamed?", start >= 0)
        var depth = 0
        var i = source.indexOf('{', start)
        val open = i
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(open, i)
    }

    private fun lines(): List<String> = setSyncWindowBody().lines().map { it.trim() }

    private fun assertLine(line: String) {
        assertTrue(
            "AccountsViewModel.setSyncWindow no longer contains the line:\n  $line\nits body is:\n" +
                lines().joinToString("\n"),
            line in lines(),
        )
    }

    // -- the decision, executed --------------------------------------------------------------------

    @Test fun `the decision says yes on a real change and no on the same value`() {
        assertTrue(syncWindowChanged(SyncWindow.COUNT_50, SyncWindow.ALL))
        assertTrue(syncWindowChanged(SyncWindow.ALL, SyncWindow.COUNT_50))
        assertFalse(syncWindowChanged(SyncWindow.ALL, SyncWindow.ALL))
    }

    // -- the plug --------------------------------------------------------------------------------

    @Test fun `the change is decided against the value already stored, before it is overwritten`() {
        // Order matters and cannot be seen from the outside: reading the stored window AFTER
        // setSyncWindow would compare the new value with itself and never drop anything.
        val body = lines()
        val decision = body.indexOf("val changed = syncWindowChanged(store.syncWindow(id), window)")
        val write = body.indexOf("store.setSyncWindow(id, window)")
        assertTrue(
            "setSyncWindow must ask syncWindowChanged(store.syncWindow(id), window) rather than " +
                "re-deciding for itself; its body is:\n" + body.joinToString("\n"),
            decision >= 0,
        )
        assertTrue("setSyncWindow no longer writes the window", write >= 0)
        assertTrue(
            "the comparison must be made BEFORE the new window is written, or it compares the new " +
                "value with itself and the cursor never falls",
            decision < write,
        )
    }

    @Test fun `the cursor drop is per account and happens only on a real change`() {
        assertLine("if (changed) mail.dropSyncCursors(id)")
        assertFalse(
            "setSyncWindow reaches for the global resetSyncState(): every folder of every OTHER " +
                "account would re-query in full for a setting changed on one of them",
            "resetSyncState" in setSyncWindowBody(),
        )
    }

    @Test fun `the whole body is these four lines, so nothing can be slipped above them`() {
        // Whole body, not the presence of lines: an inserted `if (id != store.currentId()) return`
        // leaves every pinned line where it is, reads like an optimisation, and makes the setting
        // inert for every account that is not the current one. AndroidViewModel — nothing here can
        // execute it, so an addition has to be caught as text or not at all.
        assertEquals(
            "AccountsViewModel.setSyncWindow is no longer, line for line, what this test was " +
                "written against. Read the new body before updating this: an INSERTED line is " +
                "exactly what this pin exists to catch.",
            listOf(
                "{",
                "val changed = syncWindowChanged(store.syncWindow(id), window)",
                "store.setSyncWindow(id, window)",
                "if (changed) mail.dropSyncCursors(id)",
                "refresh()",
                "}",
            ),
            lines().filter { it.isNotEmpty() },
        )
    }

    @Test fun `nothing is fetched from the settings screen`() {
        // Decided: the cursor falls, and the next pull / push / folder change / cold start applies
        // the window. A refresh started from here would need a cross-screen trigger.
        val body = setSyncWindowBody()
        listOf("refreshFolder", "forceRefresh", "mail.refresh", "viewModelScope").forEach {
            assertTrue(
                "setSyncWindow now starts work of its own ('$it') — the row writes a setting, it " +
                    "does not sync",
                it !in body,
            )
        }
    }

    @Test fun `the row still writes at the tap, without waking the Save button`() {
        // The screen says, above Save, that this setting is saved as you change it — and Save has no
        // parameter for it. Routing the row through markEdited() would light a button that writes
        // nothing of the kind.
        val screen = SETTINGS_SCREEN.readText()
        val row = screen.substring(screen.indexOf("settings_messages_to_sync_title"))
            .substringBefore("SettingsSection(")
        assertTrue(
            "the sync-window row no longer calls viewModel.setSyncWindow(accountId, it)",
            "viewModel.setSyncWindow(accountId, it)" in row,
        )
        assertEquals(
            "the sync-window row now calls markEdited(): the Save button would promise a write it " +
                "does not make",
            0,
            Regex("markEdited\\(\\)").findAll(row).count(),
        )
    }

    private companion object {
        const val ACCOUNTS_VM_PATH = "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, ACCOUNTS_VM_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
        }

        val ACCOUNTS_VM: File by lazy { File(root, ACCOUNTS_VM_PATH) }
        val SETTINGS_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt")
        }
    }
}
