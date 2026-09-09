package app.sterna.ui.settings

import app.sterna.core.data.filter.FilterScriptStatus
import app.sterna.core.data.filter.ForeignScript
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [filtersStateWithStatus], executed. The filters screen carries three facts about the SERVER —
 */
class FiltersStatusUpdateTest {

    private val displayed = FiltersUiState(
        loading = false,
        rulesNotRunning = true,
        foreignActive = true,
        vacationScriptActive = true,
    )

    @Test
    fun `a read that failed leaves all three facts exactly as they were`() {
        // null is "I could not look", not "all clear". The case it protects: the responder has
        // just been switched off, the server left no script active, the rules are dead — and the
        // read that would have said so failed. Blanking here erases the warning at the moment it
        // becomes true, and closes the Save button that is the way out.
        assertEquals(displayed, filtersStateWithStatus(state = displayed, status = null))
    }

    @Test
    fun `a read that failed does not invent facts on a quiet screen either`() {
        val quiet = FiltersUiState(loading = false)
        assertEquals(quiet, filtersStateWithStatus(state = quiet, status = null))
    }

    @Test
    fun `a read that succeeds replaces all three facts`() {
        // The half-refresh this closes: after a save only `rulesNotRunning` was read again, so the
        // red "another script is active" line stayed under a screen that had just made Sterna's
        // script the active one — two halves of the screen describing two different moments.
        val next = filtersStateWithStatus(
            state = displayed,
            status = FilterScriptStatus(
                scriptExists = true,
                scriptActive = true,
                enabledRuleCount = 2,
                vacationScriptActive = false,
                foreignActive = false,
            ),
        )
        assertFalse("the rules are running again", next.rulesNotRunning)
        assertFalse("our script IS the active one now", next.foreignActive)
        assertFalse("the vacation script is not the one running", next.vacationScriptActive)
    }

    @Test
    fun `a read that succeeds raises all three facts`() {
        val next = filtersStateWithStatus(
            state = FiltersUiState(loading = false),
            status = FilterScriptStatus(
                scriptExists = true,
                scriptActive = false,
                enabledRuleCount = 2,
                vacationScriptActive = true,
                foreignActive = true,
            ),
        )
        assertTrue("an inactive script carrying rules is rules that are not running", next.rulesNotRunning)
        assertTrue(next.foreignActive)
        assertTrue("the fact that lets the warning name the auto-reply", next.vacationScriptActive)
    }

    @Test
    fun `the three facts come from the status, one by one`() {
        // Three booleans on the way in and three on the way out: a crossed pair compiles.
        val stopped = FilterScriptStatus(
            scriptExists = true,
            scriptActive = false,
            enabledRuleCount = 1,
            vacationScriptActive = false,
            foreignActive = false,
        )
        val fresh = FiltersUiState(loading = false)
        with(filtersStateWithStatus(fresh, stopped)) {
            assertTrue(rulesNotRunning)
            assertFalse("no other script is active in the state that follows a holiday", foreignActive)
            assertFalse(vacationScriptActive)
        }
        with(filtersStateWithStatus(fresh, stopped.copy(foreignActive = true))) {
            assertTrue(rulesNotRunning)
            assertTrue(foreignActive)
            assertFalse(vacationScriptActive)
        }
        with(filtersStateWithStatus(fresh, stopped.copy(vacationScriptActive = true))) {
            assertTrue(rulesNotRunning)
            assertFalse(foreignActive)
            assertTrue(vacationScriptActive)
        }
        with(filtersStateWithStatus(fresh, stopped.copy(vacationScriptExists = true))) {
            assertFalse(
                "EXISTENCE is not what this screen's line is chosen on — activity is. Read here, " +
                    "the account with an idle `vacation` script beside a third active one is told " +
                    "its auto-reply is about to be stopped",
                vacationScriptActive,
            )
        }
        with(filtersStateWithStatus(fresh, stopped.copy(scriptActive = true))) {
            assertFalse("an active script carrying rules is rules that ARE running", rulesNotRunning)
        }
    }

    @Test
    fun `the rest of the screen is not touched by a status read`() {
        val editing = displayed.copy(dirty = true, saving = true, savedTick = 3, accountLabel = "a@b.c")
        val next = filtersStateWithStatus(editing, FilterScriptStatus())
        assertTrue("a status read must not undo an edit", next.dirty)
        assertTrue(next.saving)
        assertEquals(3, next.savedTick)
        assertEquals("a@b.c", next.accountLabel)
    }

    @Test
    fun `a sterna script the rules read could not parse keeps the refusal up`() {
        // Volet D put "we could not read our own script" behind the same red line, because saving
        // over it destroys content nobody managed to read. The script LIST cannot see that — it
        // carries names and active flags — so a status read must not clear it.
        val next = filtersStateWithStatus(
            state = FiltersUiState(loading = false),
            status = FilterScriptStatus(scriptExists = true, scriptActive = true, enabledRuleCount = 1),
            foreignFromRulesRead = true,
        )
        assertTrue("the status read saw nothing foreign, but the rules read did", next.foreignActive)
    }

    @Test
    fun `and it survives a read that failed altogether`() {
        val next = filtersStateWithStatus(
            state = FiltersUiState(loading = false),
            status = null,
            foreignFromRulesRead = true,
        )
        assertTrue(next.foreignActive)
    }

    /**
     * E2. The same read carries WHICH of the two refusals it is, all the way to the screen.
     */
    @Test
    fun `the unreadable script reaches the screen as a fact of its own`() {
        val next = filtersStateWithStatus(
            state = FiltersUiState(loading = false),
            status = FilterScriptStatus(scriptExists = true, scriptActive = true, enabledRuleCount = 1),
            foreignFromRulesRead = true,
            unreadableFromRulesRead = true,
        )
        assertTrue("the refusal stays up, exactly as before", next.foreignActive)
        assertTrue("and its cause is now nameable", next.scriptUnreadable)
    }

    /** The witness: a foreign active script is NOT an unreadable one, and must not borrow its line. */
    @Test
    fun `another active script alone does not claim our script is unreadable`() {
        val next = filtersStateWithStatus(
            state = FiltersUiState(loading = false),
            status = FilterScriptStatus(
                scriptExists = true,
                scriptActive = false,
                enabledRuleCount = 1,
                foreignActive = true,
            ),
            foreignFromRulesRead = true,
            unreadableFromRulesRead = false,
        )
        assertTrue(next.foreignActive)
        assertFalse("nothing said our own script could not be read", next.scriptUnreadable)
    }

    @Test
    fun `a save replaces the unreadable script, so its line goes out with the read that follows`() {
        // The re-read after a save passes neither flag: the script on the server is the one just
        // written, and a red line that survives it is the half-refresh bug in a new place.
        val next = filtersStateWithStatus(
            state = displayed.copy(scriptUnreadable = true),
            status = FilterScriptStatus(scriptExists = true, scriptActive = true, enabledRuleCount = 1),
        )
        assertFalse("the script we just wrote is one we can read", next.scriptUnreadable)
    }

    @Test
    fun `but a read that failed does not retire it either`() {
        val next = filtersStateWithStatus(state = displayed.copy(scriptUnreadable = true), status = null)
        assertTrue(
            "null is \"I could not look\": clearing here would offer the write over a script " +
                "nobody has managed to read, on the strength of a failure",
            next.scriptUnreadable,
        )
    }

    // ---- the foreign script's TEXT, which the flag beside it cannot carry (#209) ----------------

    private val roundcube = ForeignScript("roundcube", "require [\"fileinto\"];\nif true { keep; }")

    /**
     * **THE FIX, FOLDED.** The rules read downloaded the script that is filtering the account;
     * the status read that follows knows only names and flags. If the fold kept the boolean and
     * dropped the body, the repository would fetch the script and the screen would still have
     * nothing but "another script is active" to draw — #209 with a wasted round-trip added.
     */
    @Test
    fun `the script the rules read downloaded survives the status read`() {
        val next = filtersStateWithStatus(
            state = FiltersUiState(loading = false),
            status = FilterScriptStatus(foreignActive = true),
            foreignFromRulesRead = true,
            foreignScriptFromRulesRead = roundcube,
        )
        assertEquals("the body is what the screen shows instead of \"no rules yet\"", roundcube, next.foreignScript)
        assertTrue(next.foreignActive)
    }

    /** A status read that failed must not throw the text away — same rule as the three flags. */
    @Test
    fun `a read that failed keeps the script already on screen`() {
        val showing = FiltersUiState(loading = false, foreignActive = true, foreignScript = roundcube)
        assertEquals(
            "null is \"I could not look\": blanking the body here empties the one part of this " +
                "screen that is not a claim but a quotation",
            showing,
            filtersStateWithStatus(state = showing, status = null),
        )
    }

    /**
     * The other half, and the reason this is a `when` and not an `?:`. After a successful save
     * ours IS the active script, so the status read says foreignActive = false. Holding the old
     * body past that leaves the screen quoting a script it no longer switches off, above a button
     * that would now ask a question with no subject.
     */
    @Test
    fun `a successful read that finds nothing foreign retires the script it was showing`() {
        val next = filtersStateWithStatus(
            state = FiltersUiState(loading = false, foreignActive = true, foreignScript = roundcube),
            status = FilterScriptStatus(scriptExists = true, scriptActive = true, foreignActive = false),
        )
        assertNull("the takeover succeeded: there is no foreign script left to quote", next.foreignScript)
        assertFalse(next.foreignActive)
    }

    /** …but a read that still finds one foreign keeps the text, so the warning keeps its subject. */
    @Test
    fun `a read that still finds a foreign script keeps the text`() {
        val next = filtersStateWithStatus(
            state = FiltersUiState(loading = false, foreignActive = true, foreignScript = roundcube),
            status = FilterScriptStatus(foreignActive = true),
        )
        assertEquals(roundcube, next.foreignScript)
        assertTrue(next.foreignActive)
    }
}
