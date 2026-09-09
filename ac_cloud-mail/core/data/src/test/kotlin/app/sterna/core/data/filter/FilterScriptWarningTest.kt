package app.sterna.core.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules stop filtering when the responder is switched on, and STAY stopped when it is
 */
class FilterScriptWarningTest {

    @Test
    fun `no script at all says nothing`() {
        assertNull(
            "a fresh account that never had a filter must not be warned about filters",
            filterScriptWarning(
                scriptExists = false,
                scriptActive = false,
                enabledRuleCount = 0,
                vacationScriptExists = true,
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `an inactive script carrying rules says the rules are not running`() {
        // The state the return from holiday leaves the account in: rules on the server, no
        // script active, and nothing on either screen saying so.
        assertEquals(
            FilterScriptWarning.RULES_NOT_RUNNING,
            filterScriptWarning(
                scriptExists = true,
                scriptActive = false,
                enabledRuleCount = 2,
                vacationScriptExists = true,
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `an inactive script says the rules are not running even with no vacation script`() {
        // The statement of fact claims nothing about the server's design, so it does not need the
        // proof the prediction needs.
        assertEquals(
            FilterScriptWarning.RULES_NOT_RUNNING,
            filterScriptWarning(
                scriptExists = true,
                scriptActive = false,
                enabledRuleCount = 1,
                vacationScriptExists = false,
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `an inactive script says the rules are not running whatever the responder is doing`() {
        // Including when the caller does not know: the filters screen never reads the responder,
        // and the fact does not depend on it.
        for (responder in listOf(true, false, null)) {
            assertEquals(
                "the statement of fact must not depend on the responder (responderEnabled=$responder)",
                FilterScriptWarning.RULES_NOT_RUNNING,
                filterScriptWarning(
                    scriptExists = true,
                    scriptActive = false,
                    enabledRuleCount = 1,
                    vacationScriptExists = true,
                    responderEnabled = responder,
                ),
            )
        }
    }

    @Test
    fun `an active script beside a vacation script predicts the suspension`() {
        assertEquals(
            FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
            filterScriptWarning(
                scriptExists = true,
                scriptActive = true,
                enabledRuleCount = 1,
                vacationScriptExists = true,
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `an active script with no vacation script predicts nothing`() {
        // THE FIRST WITNESS. Without it, "always shown" reads exactly like "shown when it should
        // be": the coupling was measured on one server and must not be announced on every server.
        assertNull(
            "with no vacation script there is no observed reason to claim the responder suspends anything",
            filterScriptWarning(
                scriptExists = true,
                scriptActive = true,
                enabledRuleCount = 3,
                vacationScriptExists = false,
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `nothing is predicted to somebody whose responder is already on`() {
        // THE SECOND WITNESS. Rules running, responder running: on a server that couples the two
        // this is unreachable, but on one that does not it is the permanent state — and there
        // "turning the auto-reply on will suspend your rules" is false in the present tense about
        // rules the user can see working.
        assertNull(
            "the prediction must not be made to somebody who has already done the thing predicted",
            filterScriptWarning(
                scriptExists = true,
                scriptActive = true,
                enabledRuleCount = 1,
                vacationScriptExists = true,
                responderEnabled = true,
            ),
        )
    }

    @Test
    fun `nothing is predicted when the responder state is unknown`() {
        // What the filters screen passes: it never loads the responder. Unknown is not "off" —
        // predicting on a guess is how a warning earns its reputation.
        assertNull(
            "an unknown responder state must not be read as off",
            filterScriptWarning(
                scriptExists = true,
                scriptActive = true,
                enabledRuleCount = 1,
                vacationScriptExists = true,
                responderEnabled = null,
            ),
        )
    }

    @Test
    fun `an inactive script carrying nothing that filters says nothing`() {
        // A script with no rule that filters has nothing to suspend: warning here is the false
        // alarm that teaches the user to skip the line.
        assertNull(
            "an inactive script with no effective rule must not raise an alarm",
            filterScriptWarning(
                scriptExists = true,
                scriptActive = false,
                enabledRuleCount = 0,
                vacationScriptExists = true,
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `the nominal state says nothing`() {
        // The general witness: script active, no vacation script, responder off. Silence.
        assertNull(
            "the nominal state must stay silent",
            filterScriptWarning(
                scriptExists = true,
                scriptActive = true,
                enabledRuleCount = 2,
                vacationScriptExists = false,
                responderEnabled = false,
            ),
        )
    }

    // -- what the filters screen reads out of the decision -----------------------------------

    @Test
    fun `only the statement of fact counts as rules not running`() {
        assertTrue(rulesAreNotRunning(FilterScriptWarning.RULES_NOT_RUNNING))
        assertFalse(
            "the prediction is about a suspension that has NOT happened: reading it as \"not " +
                "running\" would shout at a user whose rules are working",
            rulesAreNotRunning(FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES),
        )
        assertFalse("no warning means nothing to say", rulesAreNotRunning(null))
    }

    /**
     * The name is not decoration: the repo looks for a script called exactly this, and getting the
     */
    @Test
    fun `the vacation script is looked for under the name the server uses`() {
        assertEquals("vacation", VACATION_SCRIPT_NAME)
    }

    /**
     * [enabledRuleCount] counts the rules the server will actually run. It is a second spelling
     */
    @Test
    fun `the counted rules are the ones the generated script filters on`() {
        val rules = listOf(
            FilterRule(name = "kept", enabled = true, value = "boss@example.com", markRead = true),
            FilterRule(name = "off", enabled = false, value = "news@example.com", markRead = true),
            FilterRule(name = "blank", enabled = true, value = "", markRead = true),
            FilterRule(name = "kept too", enabled = true, value = "alerts@example.com", flag = true),
        )
        val emitted = Regex("^if ", RegexOption.MULTILINE).findAll(SieveCodec.generate(rules)).count()
        assertEquals("counted rules must match the tests the generated Sieve carries", emitted, enabledRuleCount(rules))
        assertEquals(2, enabledRuleCount(rules))
    }
}
