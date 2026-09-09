package app.sterna.ui.settings

import app.sterna.core.data.account.SyncWindow
import app.sterna.core.data.account.syncWindowChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the "Messages to sync" row OFFERS, and the weld between each offered number and the label it
 */
class SyncWindowPickerScaleTest {

    // -- the decision, executed --------------------------------------------------------------------

    @Test fun `the row offers the three age windows and the count scale 100 - 1000 - 10000`() {
        assertEquals(
            listOf(
                SyncWindow.DAYS_30, SyncWindow.DAYS_90, SyncWindow.YEAR_1,
                SyncWindow.COUNT_100, SyncWindow.COUNT_1000, SyncWindow.COUNT_10000,
            ),
            syncWindowChoices(),
        )
    }

    @Test fun `nothing retired is offered, however it got back into the enum`() {
        listOf("COUNT_50", "COUNT_200", "COUNT_500", "ALL").forEach { name ->
            assertTrue(
                "$name is on the settings picker again. It exists only so older account records " +
                    "keep decoding; offering it is what this volet withdrew.",
                syncWindowChoices().none { it.name == name },
            )
        }
    }

    // -- the plug: the screen takes its list from that function ---------------------------------------

    @Test fun `the picker's options argument is the shipped function, with no list of its own`() {
        assertTrue(
            "the sync-window row no longer takes its options from syncWindowChoices(); the line " +
                "it now carries is one of:\n" + syncRowLines().joinToString("\n"),
            "options = syncWindowChoices()," in syncRowLines(),
        )
        assertEquals(
            "the sync-window row mentions a SyncWindow member by hand — the list it offers is " +
                "decided in core:data, and a member named on this screen is a member that can be " +
                "put back on the picker without a single test seeing it",
            emptyList<String>(),
            syncRowLines().filter { !it.startsWith("//") && "SyncWindow." in it },
        )
    }

    @Test fun `the row shows the window the account CARRIES, never one chosen from the offer`() {
        // The survivor an audit found, and it is the worst shape in this volet:
        //     selected = syncWindowChoices().firstOrNull { it == syncWindow } ?: syncWindowChoices().last()
        //
        // A dialog with NO radio ticked on an unmigrated account is the ACCEPTED cost of that
        // promise: the summary line tells the truth, and nothing is invented to fill a circle.
        assertTrue(
            "the sync-window row no longer shows `selected = syncWindow,` — whatever it shows " +
                "instead, a window the account really carries must not be replaceable by one from " +
                "the offer. Its lines are:\n" + syncRowLines().joinToString("\n"),
            "selected = syncWindow," in syncRowLines(),
        )
    }

    @Test fun `every window can still be named on screen, retired or not`() {
        // An account is never migrated off a retired window; the row has to render the one it
        // carries. Whole lines, so a label swapped between two members is visible here.
        val labels = labelFunctionLines()
        listOf(
            "SyncWindow.DAYS_30 -> context.getString(R.string.settings_sync_last_30_days)",
            "SyncWindow.DAYS_90 -> context.getString(R.string.settings_sync_last_90_days)",
            "SyncWindow.YEAR_1 -> context.getString(R.string.settings_sync_last_year)",
            "SyncWindow.COUNT_100 -> context.getString(R.string.settings_sync_100_messages)",
            "SyncWindow.COUNT_1000 -> context.getString(R.string.settings_sync_1000_messages)",
            "SyncWindow.COUNT_10000 -> context.getString(R.string.settings_sync_10000_messages)",
            "SyncWindow.COUNT_50 -> context.getString(R.string.settings_sync_50_messages)",
            "SyncWindow.COUNT_200 -> context.getString(R.string.settings_sync_200_messages)",
            "SyncWindow.COUNT_500 -> context.getString(R.string.settings_sync_500_messages)",
            // NOT settings_sync_everything any more — see the test below for why.
            "SyncWindow.ALL -> context.getString(R.string.settings_sync_10000_messages)",
        ).forEach {
            assertTrue(
                "syncWindowLabel no longer contains the line:\n  $it\nits body is:\n" +
                    labels.joinToString("\n"),
                it in labels,
            )
        }
    }

    // -- the weld: the number in the label IS the number the window caches -----------------------------

    @Test fun `each new label states, in every language, the number its window really caches`() {
        // The mutation this exists for: a member whose `limit` no longer matches the label it is
        // shown under. Nothing else in the repo compares the two — the enum test would pass, the
        // resource lint would pass, and the row would promise 1 000 messages while caching 500.
        // Digits only: the thousands separator is the translator's (1,000 / 1.000 / 1 000 / 1000).
        mapOf(
            "settings_sync_100_messages" to SyncWindow.COUNT_100,
            "settings_sync_1000_messages" to SyncWindow.COUNT_1000,
            "settings_sync_10000_messages" to SyncWindow.COUNT_10000,
        ).forEach { (key, window) ->
            val perLocale = stringFiles().associate { it.parentFile.name to value(it, key) }
            assertEquals(
                "$key is missing from a locale — nine languages is the cost of one new label",
                emptyList<String>(),
                perLocale.filterValues { it == null }.keys.toList(),
            )
            assertEquals(
                "the number in the label of $key disagrees with SyncWindow.${window.name}.limit " +
                    "(${window.limit}) in at least one language",
                perLocale.mapValues { window.limit },
                perLocale.mapValues { (_, text) -> text!!.filter { it.isDigit() }.toInt() },
            )
        }
    }

    @Test fun `an account left on Everything reads as 10000, in every language`() {
        // WYSIWYG (CLAUDE.md's arbitration grid, rule 3: between lowering the label and raising
        // the code, lower the label). "Everything / Tout / Alles / Всё" now caches 10 000 messages
        // and no more. Left as it was, a user on that window would believe her whole mailbox is
        // offline while 40 000 messages leave the cache without the screen ever having said so.
        //
        // The member is untouched, the string is untouched and still shipped: ONE `when` arm now
        // points at the label of the largest offered window, so the two render identically.
        val all = labelKeyOf("ALL")
        val biggest = labelKeyOf("COUNT_10000")
        assertEquals(
            "SyncWindow.ALL is labelled with $all, which is not the label of the window it now " +
                "actually caches ($biggest) — the row would promise more than the cache holds",
            biggest, all,
        )
        val perLocale = stringFiles().associate { it.parentFile.name to value(it, all) }
        assertEquals(
            "the label SyncWindow.ALL renders is missing from a locale",
            emptyList<String>(), perLocale.filterValues { it == null }.keys.toList(),
        )
        assertEquals(
            "the label an account on \"Everything\" reads no longer states 10 000 in every language",
            perLocale.mapValues { 10_000 },
            perLocale.mapValues { (_, text) -> text!!.filter { it.isDigit() }.toInt() },
        )
    }

    @Test fun `the Everything strings are still shipped, they are just not shown any more`() {
        // Nothing may be deleted: `SyncWindow.ALL` stays in the enum for ever (its name is on
        // disk), and a string removed today is a string a later volet cannot put back without a
        // translation round. Nine locales, still there — SyncEverythingLabelTest pins the words.
        assertEquals(
            "settings_sync_everything was dropped from a locale",
            emptyList<String>(),
            stringFiles().filter { value(it, "settings_sync_everything") == null }.map { it.parentFile.name },
        )
    }

    @Test fun `the nine languages carry the three new labels and no locale was left behind`() {
        assertEquals("nine locales ship; this test read a different number of them", 9, stringFiles().size)
    }

    // -- reading the sources ---------------------------------------------------------------------------

    /** The lines of the `SettingChoiceRow` that carries the sync window, trimmed. */
    private fun syncRowLines(): List<String> {
        val screen = SETTINGS_SCREEN.readText()
        val start = screen.indexOf("settings_messages_to_sync_title")
        assertTrue("SettingsScreen no longer shows settings_messages_to_sync_title", start >= 0)
        return screen.substring(start).substringBefore("SettingsSection(")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The body of `syncWindowLabel`, trimmed. */
    private fun labelFunctionLines(): List<String> {
        val source = SETTINGS_SCREEN.readText()
        val start = source.indexOf("private fun syncWindowLabel(")
        assertTrue("SettingsScreen has no syncWindowLabel() — renamed?", start >= 0)
        var i = source.indexOf('{', start)
        val open = i
        var depth = 0
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(open, i).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The string resource `syncWindowLabel` returns for [member], read off its `when` arm. */
    private fun labelKeyOf(member: String): String {
        val arm = Regex("SyncWindow\\.$member -> context\\.getString\\(R\\.string\\.([a-z0-9_]+)\\)")
            .find(labelFunctionLines().joinToString("\n"))
        return arm?.groupValues?.get(1)
            ?: error("syncWindowLabel has no arm for SyncWindow.$member — every member must be nameable")
    }

    private fun value(file: File, key: String): String? =
        Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1)

    private fun stringFiles(): List<File> = (File(root, RES).listFiles() ?: emptyArray())
        .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    private companion object {
        const val RES = "app/src/main/res"
        const val SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources and resources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_SCREEN: File by lazy { File(root, SCREEN_PATH) }
    }
}
