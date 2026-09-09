package app.sterna.core.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SieveCodecTest {

    @Test fun roundTripsRulesThroughJsonComment() {
        val rules = listOf(
            FilterRule(
                name = "Boss", field = RuleField.FROM, match = RuleMatch.CONTAINS,
                value = "boss@example.com", moveTo = "Work", flag = true,
            ),
            FilterRule(
                name = "News", field = RuleField.SUBJECT, match = RuleMatch.IS,
                value = "Weekly digest", markRead = true,
            ),
        )
        val parsed = SieveCodec.parseRules(SieveCodec.generate(rules))
        assertEquals(rules, parsed)
    }

    @Test fun emitsRequiresAndTests() {
        val script = SieveCodec.generate(
            listOf(FilterRule(field = RuleField.FROM, value = "a@b.com", moveTo = "Work", markRead = true)),
        )
        assertTrue(script.contains("require [\"fileinto\", \"imap4flags\"];"))
        assertTrue(script.contains("address :contains \"from\" \"a@b.com\""))
        assertTrue(script.contains("fileinto \"Work\";"))
        assertTrue(script.contains("addflag \"\\\\Seen\";"))
    }

    @Test fun subjectUsesHeaderTest() {
        val script = SieveCodec.generate(
            listOf(FilterRule(field = RuleField.SUBJECT, match = RuleMatch.IS, value = "Hi", flag = true)),
        )
        assertTrue(script.contains("header :is \"subject\" \"Hi\""))
        assertTrue(script.contains("addflag \"\\\\Flagged\";"))
        // No fileinto requirement when nothing is moved.
        assertFalse(script.contains("fileinto"))
        assertTrue(script.contains("require [\"imap4flags\"];"))
    }

    @Test fun disabledAndBlankRulesAreNotEmittedButKeptInJson() {
        val rules = listOf(
            FilterRule(name = "off", enabled = false, value = "x@y.com", moveTo = "Work"),
            FilterRule(name = "blank", enabled = true, value = "   "),
        )
        val script = SieveCodec.generate(rules)
        assertFalse(script.contains("if ")) // neither rule produces a test
        // ...but both survive the round-trip via the JSON comment.
        assertEquals(rules, SieveCodec.parseRules(script))
    }

    @Test fun escapesQuotesAndBackslashesInValues() {
        val script = SieveCodec.generate(
            listOf(FilterRule(field = RuleField.SUBJECT, value = "a\"b\\c", flag = true)),
        )
        assertTrue(script.contains("\"a\\\"b\\\\c\""))
    }

    @Test fun noMarkerReturnsEmpty() {
        assertTrue(SieveCodec.parseRules("require [\"fileinto\"];\nif true { keep; }").isEmpty())
    }

    /**
     * A script with content and no marker is UNREADABLE, not empty.
     */
    @Test fun `a script with content and no marker cannot be read`() {
        assertNull(SieveCodec.parseRulesOrNull("require [\"fileinto\"];\nif true { keep; }"))
    }

    /** Marker present, payload not JSON: truncated upload, older/newer format, anything. */
    @Test fun `a marker whose payload is not JSON cannot be read`() {
        assertNull(SieveCodec.parseRulesOrNull("# STERNA-RULES-V1: {oops\nif true { keep; }"))
    }

    /** JSON that parses but is not a rule list is no more readable than one that does not. */
    @Test fun `a marker carrying the wrong JSON shape cannot be read`() {
        assertNull(SieveCodec.parseRulesOrNull("# STERNA-RULES-V1: {\"rules\":[]}"))
    }

    /**
     * The witness of the pair. A blank script says nothing, filters nothing and loses nothing
     */
    @Test fun `an empty or blank script is zero rules, not unreadable`() {
        assertEquals(emptyList<FilterRule>(), SieveCodec.parseRulesOrNull(""))
        assertEquals(emptyList<FilterRule>(), SieveCodec.parseRulesOrNull("  \n\t\n "))
    }

    /** A script this codec wrote reads back as the very rules it was given — still. */
    @Test fun `a generated script is readable and gives its rules back`() {
        val rules = listOf(
            FilterRule(name = "Boss", field = RuleField.FROM, value = "boss@example.com", flag = true),
            FilterRule(name = "off", enabled = false, value = "x@y.com", moveTo = "Work"),
        )
        assertEquals(rules, SieveCodec.parseRulesOrNull(SieveCodec.generate(rules)))
    }

    /** Zero rules is a script this codec writes, and it must not read back as unreadable. */
    @Test fun `a generated script with no rules at all is still readable`() {
        assertEquals(emptyList<FilterRule>(), SieveCodec.parseRulesOrNull(SieveCodec.generate(emptyList())))
    }

    /**
     * The compared value: the marker line out whole, the rest normalised and otherwise untouched.
     * Pinned with literal input and literal output — indentation and interior blank lines survive.
     */
    @Test fun `sieveBody drops the marker line and normalises blanks`() {
        assertEquals(
            "require [\"fileinto\"];\n\nif true {\n    keep;\n}",
            SieveCodec.sieveBody(
                "\n  # STERNA-RULES-V1: [] \r\nrequire [\"fileinto\"];  \r\n\r\nif true {\t\n    keep;\n}\n\n",
            ),
        )
    }

    /** Only the FIRST marker line is ours; a second one further down is content like any other. */
    @Test fun `sieveBody keeps a second marker line`() {
        assertEquals(
            "keep;\n# STERNA-RULES-V1: []",
            SieveCodec.sieveBody("# STERNA-RULES-V1: []\nkeep;\n# STERNA-RULES-V1: []\n"),
        )
    }

    /** No marker at all: nothing is dropped, everything is compared. */
    @Test fun `sieveBody without a marker keeps every line`() {
        assertEquals("if true { keep; }", SieveCodec.sieveBody("if true { keep; }\n"))
    }

    /** The lenient wrapper still flattens both nos to an empty list, for readers that only count. */
    @Test fun `parseRules flattens unreadable to empty`() {
        assertTrue(SieveCodec.parseRules("# STERNA-RULES-V1: {oops").isEmpty())
        assertTrue(SieveCodec.parseRules("if true { keep; }").isEmpty())
    }
}
