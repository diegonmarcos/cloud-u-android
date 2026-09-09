package app.sterna.core.data.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterRuleTest {

    @Test fun freshRuleIsEmpty() {
        assertTrue(FilterRule().isEmpty)
        assertTrue(FilterRule(name = "  ", value = "  ").isEmpty)
        // Picking a field or a match keeps the defaults' meaning: still nothing entered.
        assertTrue(FilterRule(field = RuleField.SUBJECT, match = RuleMatch.IS).isEmpty)
        assertTrue(FilterRule(enabled = false).isEmpty)
    }

    @Test fun anyFilledFieldOrActionMakesItNonEmpty() {
        assertFalse(FilterRule(name = "Boss").isEmpty)
        assertFalse(FilterRule(value = "boss@example.com").isEmpty)
        assertFalse(FilterRule(moveTo = "Work").isEmpty)
        assertFalse(FilterRule(markRead = true).isEmpty)
        assertFalse(FilterRule(flag = true).isEmpty)
    }

    @Test fun emptinessSurvivesTheJsonRoundTrip() {
        val script = SieveCodec.generate(listOf(FilterRule(), FilterRule(name = "Boss", value = "b@c.d")))
        val parsed = SieveCodec.parseRules(script)
        assertTrue(parsed[0].isEmpty)
        assertFalse(parsed[1].isEmpty)
    }
}
