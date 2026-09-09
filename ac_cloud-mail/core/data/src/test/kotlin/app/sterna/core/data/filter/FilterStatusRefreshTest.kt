package app.sterna.core.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The decisions the filters and responder screens make ON TOP of [filterScriptWarning]: what a
 */
class FilterStatusRefreshTest {

    // ---- refreshedFilterWarning: "I could not look" is not "nothing to report" -------------

    @Test
    fun `a failed read keeps the warning that was on screen`() {
        assertEquals(
            "a null status is a read that FAILED. Treated as an all-clear it wipes the line at " +
                "the moment it becomes true — see the ON to OFF case below",
            FilterScriptWarning.RULES_NOT_RUNNING,
            refreshedFilterWarning(
                previous = FilterScriptWarning.RULES_NOT_RUNNING,
                status = null,
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `a failed read keeps the prediction too, and keeps silence silent`() {
        assertEquals(
            FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
            refreshedFilterWarning(
                previous = FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
                status = null,
                responderEnabled = false,
            ),
        )
        assertNull(
            "a failed read must not INVENT a warning either",
            refreshedFilterWarning(previous = null, status = null, responderEnabled = false),
        )
    }

    @Test
    fun `a read that succeeds and finds nothing clears the warning`() {
        // The other half of the same rule. A red line that survives a good read is the same lie
        // the other way round: after a save the Sterna script is active again and the account is
        // in the nominal state.
        assertNull(
            "a status that says the rules are running must retire a stale RULES_NOT_RUNNING",
            refreshedFilterWarning(
                previous = FilterScriptWarning.RULES_NOT_RUNNING,
                status = FilterScriptStatus(
                    scriptExists = true,
                    scriptActive = true,
                    enabledRuleCount = 2,
                    vacationScriptExists = false,
                ),
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `a read that succeeds replaces one warning with the other`() {
        assertEquals(
            "the rules just stopped: the prediction on screen must give way to the fact",
            FilterScriptWarning.RULES_NOT_RUNNING,
            refreshedFilterWarning(
                previous = FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
                status = FilterScriptStatus(
                    scriptExists = true,
                    scriptActive = false,
                    enabledRuleCount = 2,
                    vacationScriptExists = true,
                ),
                responderEnabled = true,
            ),
        )
    }

    /**
     * The case the whole nullable return exists for, end to end, as measured on the server:
     */
    @Test
    fun `switching the responder off and losing the read does not silence the screen`() {
        val whileAway = refreshedFilterWarning(
            previous = null,
            status = FilterScriptStatus(
                scriptExists = true,
                scriptActive = false,
                enabledRuleCount = 3,
                vacationScriptExists = true,
                foreignActive = true,
            ),
            responderEnabled = true,
        )
        assertEquals(
            "with the responder on, the rules are already stopped and that is a fact, not a prediction",
            FilterScriptWarning.RULES_NOT_RUNNING,
            whileAway,
        )

        val afterTurningItOff = refreshedFilterWarning(
            previous = whileAway,
            status = null,
            responderEnabled = false,
        )
        assertEquals(
            "the rules are dead and the app could not check: keeping the line is the only honest " +
                "answer, and the only one that leaves the Save button open",
            FilterScriptWarning.RULES_NOT_RUNNING,
            afterTurningItOff,
        )
    }

    @Test
    fun `the status is read fact by fact, under its own name`() {
        // Three booleans and a count go into the decision, so a swapped pair compiles and the
        // decision is then right about the wrong facts. Each case below moves ONE fact.
        val running = FilterScriptStatus(
            scriptExists = true,
            scriptActive = true,
            enabledRuleCount = 1,
            vacationScriptExists = false,
        )
        assertNull(
            "script present, active, one rule, no vacation script: the nominal state",
            refreshedFilterWarning(previous = null, status = running, responderEnabled = false),
        )
        assertEquals(
            "only scriptActive changed",
            FilterScriptWarning.RULES_NOT_RUNNING,
            refreshedFilterWarning(
                previous = null,
                status = running.copy(scriptActive = false),
                responderEnabled = false,
            ),
        )
        assertEquals(
            "only vacationScriptExists changed",
            FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
            refreshedFilterWarning(
                previous = null,
                status = running.copy(vacationScriptExists = true),
                responderEnabled = false,
            ),
        )
        val stopped = running.copy(scriptActive = false)
        assertNull(
            "from the stopped state, only scriptExists changed: no script, nothing to say",
            refreshedFilterWarning(
                previous = null,
                status = stopped.copy(scriptExists = false),
                responderEnabled = false,
            ),
        )
        assertNull(
            "from the stopped state, only enabledRuleCount changed: nothing that filters, no alarm",
            refreshedFilterWarning(
                previous = null,
                status = stopped.copy(enabledRuleCount = 0),
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `the responder state reaches the decision from the caller, not from the status`() {
        val status = FilterScriptStatus(
            scriptExists = true,
            scriptActive = true,
            enabledRuleCount = 1,
            vacationScriptExists = true,
        )
        assertEquals(
            FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
            refreshedFilterWarning(previous = null, status = status, responderEnabled = false),
        )
        assertNull(
            "the prediction is not made to somebody whose responder is already on",
            refreshedFilterWarning(previous = null, status = status, responderEnabled = true),
        )
        assertNull(
            "nor on a guess: the filters screen never loads the responder and passes null",
            refreshedFilterWarning(previous = null, status = status, responderEnabled = null),
        )
    }

    // ---- foreignScriptNotice: which red line goes above Save --------------------------------

    @Test
    fun `no foreign active script says nothing, whatever else exists`() {
        assertNull(
            "our own script being the active one is the nominal state: no line at all",
            foreignScriptNotice(
                scriptUnreadable = false,
                foreignActive = false,
                vacationScriptActive = false,
            ),
        )
        assertNull(
            "a vacation script that is NOT the active one costs nothing to save over",
            foreignScriptNotice(
                scriptUnreadable = false,
                foreignActive = false,
                vacationScriptActive = true,
            ),
        )
    }

    @Test
    fun `a foreign active script with no active vacation script keeps the generic line`() {
        // Nothing observed says what that script is, so nothing is claimed about what Save costs.
        assertEquals(
            ForeignScriptNotice.ANOTHER_SCRIPT,
            foreignScriptNotice(
                scriptUnreadable = false,
                foreignActive = true,
                vacationScriptActive = false,
            ),
        )
    }

    @Test
    fun `a foreign active script that IS the vacation script names the auto-reply`() {
        // B-2, the whole point: this release opens Save with nothing edited, and pressing it
        // during a holiday switches the auto-reply off. "Another filter script is active on the
        // server" is true and names nothing the user recognises.
        assertEquals(
            ForeignScriptNotice.STOPS_AUTO_REPLY,
            foreignScriptNotice(
                scriptUnreadable = false,
                foreignActive = true,
                vacationScriptActive = true,
            ),
        )
    }

    /**
     * THE E1 WITNESS. The line used to be chosen on the mere EXISTENCE of a `vacation` script,
     */
    @Test
    fun `an idle vacation script beside another active one accuses nobody`() {
        assertEquals(
            "the sentence names the auto-reply only when the auto-reply's script is the one running",
            ForeignScriptNotice.ANOTHER_SCRIPT,
            foreignScriptNotice(
                scriptUnreadable = false,
                foreignActive = true,
                vacationScriptActive = false,
            ),
        )
    }

    /**
     * THE E2 CASE. The refusal to write can come from our own `sterna` script being
     */
    @Test
    fun `an unreadable script of our own is named before anything else`() {
        assertEquals(
            "an unreadable sterna script with nothing foreign active: the fold makes foreignActive " +
                "true, and the old order had nothing to say about it",
            ForeignScriptNotice.UNREADABLE_SCRIPT,
            foreignScriptNotice(
                scriptUnreadable = true,
                foreignActive = true,
                vacationScriptActive = false,
            ),
        )
        assertEquals(
            "and it outranks the auto-reply line: what Save destroys outranks what Save switches off",
            ForeignScriptNotice.UNREADABLE_SCRIPT,
            foreignScriptNotice(
                scriptUnreadable = true,
                foreignActive = true,
                vacationScriptActive = true,
            ),
        )
        assertEquals(
            "…and it is said even with no foreign script active at all — the state where the OLD " +
                "line promised the auto-reply would stop, which it would not have",
            ForeignScriptNotice.UNREADABLE_SCRIPT,
            foreignScriptNotice(
                scriptUnreadable = true,
                foreignActive = false,
                vacationScriptActive = true,
            ),
        )
    }

    // ---- vacationFilterLine: the responder screen does not send for the remedy mid-holiday ----

    /**
     * THE E3 CASE. While the user is away, RULES_NOT_RUNNING is true BECAUSE the responder is
     */
    @Test
    fun `the remedy is not offered while the responder is on`() {
        assertEquals(
            "with the responder on, the fact and only the fact",
            VacationFilterLine.RULES_NOT_RUNNING_FACT,
            vacationFilterLine(FilterScriptWarning.RULES_NOT_RUNNING, responderEnabled = true),
        )
    }

    /** The witness, and the case M4 was written for: the return from holiday. */
    @Test
    fun `the remedy IS offered once the responder is off`() {
        assertEquals(
            "responder off and the rules still dead is exactly the state the remedy exists for",
            VacationFilterLine.RULES_NOT_RUNNING_WITH_REMEDY,
            vacationFilterLine(FilterScriptWarning.RULES_NOT_RUNNING, responderEnabled = false),
        )
    }

    @Test
    fun `the prediction is the same sentence whatever the switch says`() {
        // Both switch positions are REACHED on screen, contrary to what this test used to claim.
        // The warning is only ever PRODUCED with the responder off (filterScriptWarning refuses to
        for (enabled in listOf(true, false)) {
            assertEquals(
                VacationFilterLine.RESPONDER_WILL_SUSPEND_RULES,
                vacationFilterLine(
                    FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
                    responderEnabled = enabled,
                ),
            )
        }
    }

    @Test
    fun `nothing to warn about stays nothing, whatever the switch says`() {
        for (enabled in listOf(true, false)) {
            assertNull(
                "a screen with nothing to say must not grow a sentence from the switch alone",
                vacationFilterLine(null, responderEnabled = enabled),
            )
        }
    }

    // ---- showsNoRulesNote: an empty list is not always "no rules yet" ------------------------

    /**
     * THE CONTRADICTION. Over an unreadable `sterna` script the rule list is empty *because
     */
    @Test
    fun `an unreadable script does not also claim there are no rules`() {
        assertFalse(
            "the warning above already said what the empty list means; repeating it as \"no rules " +
                "yet\" contradicts it, and invites the very save that replaces the script",
            showsNoRulesNote(ruleCount = 0, scriptUnreadable = true, foreignActive = false),
        )
    }

    /** The witness: the ordinary empty account still gets told what to do. */
    @Test
    fun `an empty list on a readable script still says there are no rules`() {
        assertTrue(
            "a readable account with nothing in it is the case the sentence was written for — " +
                "silencing it leaves a screen with a lone Add button and no explanation",
            showsNoRulesNote(ruleCount = 0, scriptUnreadable = false, foreignActive = false),
        )
    }

    @Test
    fun `rules on screen say nothing about an empty list, unreadable or not`() {
        for (unreadable in listOf(true, false)) {
            assertFalse(
                "rules are listed: the sentence is about their absence. unreadable=$unreadable",
                showsNoRulesNote(ruleCount = 1, scriptUnreadable = unreadable, foreignActive = false),
            )
        }
    }

    /**
     * **#209, AS THE OWNER SAW IT.** The account is filtered by a script this app did not write.
     * The read found it, so `foreignActive` is true and the red line above says so — and the rule
     * list is empty, because none of that script is expressible as [FilterRule]s. Zero rules HERE
     * was printed as zero rules ON THE SERVER: "No rules yet. Add one to filter incoming mail on
     * the server." directly under "another filter script is active". The owner was told their
     * working filters did not exist, and invited to build the script that would replace them.
     */
    @Test
    fun `a foreign active script does not also claim there are no rules`() {
        assertFalse(
            "the line above says another script is filtering this account; \"No rules yet\" under " +
                "it contradicts it, and the invitation to add one is the invitation to stop it",
            showsNoRulesNote(ruleCount = 0, scriptUnreadable = false, foreignActive = true),
        )
    }

    /** Both causes at once — neither cancels the other, and the sentence stays away. */
    @Test
    fun `neither cause of an unexplained empty list brings the sentence back`() {
        for (unreadable in listOf(true, false)) {
            for (foreign in listOf(true, false)) {
                assertEquals(
                    "the sentence belongs to exactly one empty list: the one with nothing behind " +
                        "it (unreadable=$unreadable, foreign=$foreign)",
                    !unreadable && !foreign,
                    showsNoRulesNote(ruleCount = 0, scriptUnreadable = unreadable, foreignActive = foreign),
                )
            }
        }
    }

    /** The witness that keeps the fix from being "never say it": rules listed, nothing claimed. */
    @Test
    fun `a foreign script says nothing about a list that is not empty`() {
        assertFalse(
            "the sentence is about an ABSENCE of rules; with one on screen it is false either way",
            showsNoRulesNote(ruleCount = 1, scriptUnreadable = false, foreignActive = true),
        )
    }

    // ---- the read itself, out of the shipped source: a LAST RESORT, not the proof ----

    /**
     * Everything above executes the decision; this one cannot. `MailRepository` opens shared
     */
    @Test fun `the status read fills the foreign flag from the other scripts, and fails to null`() {
        val body = loadFilterScriptStatusBody()
        assertEquals(
            "the other-script question must be asked of the OTHER scripts and of nothing else, as " +
                "exactly `val foreign = scripts.any { it.isActive && it.name != SieveCodec" +
                ".SCRIPT_NAME }`: with `false` — or with `&& false` hung off the end of it — the " +
                "warning above Save can never name the auto-reply",
            listOf("val foreign = scripts.any { it.isActive && it.name != SieveCodec.SCRIPT_NAME }"),
            declarationsOf(body, "foreign"),
        )
        // Measured, not supposed: `val vacation = false` here left the ENTIRE suite green while
        // deleting both sentences this lot is about — the red line above Save and the prediction
        // on the responder screen. Every executed test is handed booleans; not one travels
        // through this line.
        assertEquals(
            "the vacation script must be looked for BY NAME in the list, as exactly `val vacation " +
                "= scripts.any { it.name == VACATION_SCRIPT_NAME }` — this is the EXISTENCE the " +
                "prediction \"turning the auto-reply on will suspend your rules\" rests on, and " +
                "a constant (or a `&& false` tacked on) makes that prediction permanently mute",
            listOf("val vacation = scripts.any { it.name == VACATION_SCRIPT_NAME }"),
            declarationsOf(body, "vacation"),
        )
        assertEquals(
            "…and its ACTIVITY must be a second question on the same list, as exactly `val " +
                "vacationActive = scripts.any { it.name == VACATION_SCRIPT_NAME && it.isActive }`: " +
                "existence is not activity, and the line above Save claims the auto-reply is " +
                "running NOW",
            listOf("val vacationActive = scripts.any { it.name == VACATION_SCRIPT_NAME && it.isActive }"),
            declarationsOf(body, "vacationActive"),
        )
        assertEquals(
            "existence travels into both states this can return — the one with a `sterna` script " +
                "and the one without — as the value itself, nothing appended to it",
            listOf("vacationScriptExists = vacation,", "vacationScriptExists = vacation,"),
            assignmentsOf(body, "vacationScriptExists"),
        )
        assertEquals(
            "…and so does activity: dropped from either, or narrowed on the way, the account that " +
                "has it lands on the generic line that names no consequence",
            listOf("vacationScriptActive = vacationActive,", "vacationScriptActive = vacationActive,"),
            assignmentsOf(body, "vacationScriptActive"),
        )
        assertEquals(
            "both states this can return carry the answer — the one with a `sterna` script and " +
                "the one without. Dropped from either, the screen goes quiet on that account",
            listOf("foreignActive = foreign,", "foreignActive = foreign,"),
            assignmentsOf(body, "foreignActive"),
        )
        assertTrue(
            "a FAILED read must answer null: read as the \"nothing known\" value it erases the " +
                "warning at the moment it becomes true",
            body.contains(".getOrElseUnlessCancelled { null }"),
        )
        assertTrue(
            "IMAP stays a known fact, not a failure — a null there would freeze whatever the " +
                "screen happened to be showing",
            body.contains("if (credentials.protocol == MailProtocol.IMAP) return FilterScriptStatus()"),
        )
    }

    /**
     * Every WHOLE line of [body] declaring `val [name]`, trimmed, comments dropped.
     */
    private fun declarationsOf(body: String, name: String): List<String> =
        codeLines(body).filter { it.substringBefore(" =") == "val $name" }

    /** Every WHOLE line of [body] that passes something as the named argument [name]. */
    private fun assignmentsOf(body: String, name: String): List<String> =
        codeLines(body).filter { it.substringBefore(" =") == name }

    /** [body] as trimmed code lines: comment lines dropped whole, trailing comments cut. */
    private fun codeLines(body: String): List<String> = body.lines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).takeIf { it.isNotBlank() }
    }

    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    /** The body of `MailRepository.loadFilterScriptStatus`, from its signature to the next block. */
    private fun loadFilterScriptStatusBody(): String {
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()
        val start = source.indexOf("suspend fun loadFilterScriptStatus(credentials: AccountCredentials)")
        check(start >= 0) { "MailRepository no longer declares loadFilterScriptStatus(credentials)" }
        val end = source.indexOf("\n    /**", start)
        check(end > start) { "no declaration follows loadFilterScriptStatus — the slice would be the whole file" }
        return source.substring(start, end)
    }

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
