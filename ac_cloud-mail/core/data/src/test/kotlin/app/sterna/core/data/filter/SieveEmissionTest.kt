package app.sterna.core.data.filter

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What this app EMITS as Sieve, held still — because every script already sitting on a server was
 */
class SieveEmissionTest {

    // ---- the golden witness: the codec, executed ----

    /** No rule at all: the marker, and nothing to run. */
    @Test fun `an empty rule list emits the marker alone`() {
        assertEquals(
            script("# STERNA-RULES-V1: []", ""),
            SieveCodec.generate(emptyList()),
        )
    }

    /** No action anywhere, so no `require` at all; `:is` on a header test; a disabled rule kept
     *  in the metadata and NOT emitted. */
    @Test fun `a rule with no action emits no require`() {
        val rules = listOf(
            FilterRule(name = "plain", field = RuleField.SUBJECT, match = RuleMatch.IS, value = "Hi"),
            FilterRule(
                name = "off", enabled = false, field = RuleField.FROM, match = RuleMatch.CONTAINS,
                value = "x@y.com", moveTo = "Work",
            ),
        )
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"plain","enabled":true,"field":"SUBJECT","match":"IS",""" +
                    """"value":"Hi","moveTo":null,"markRead":false,"flag":false},""" +
                    """{"name":"off","enabled":false,"field":"FROM","match":"CONTAINS",""" +
                    """"value":"x@y.com","moveTo":"Work","markRead":false,"flag":false}]""",
                "",
                """if header :is "subject" "Hi" {""",
                "}",
            ),
            SieveCodec.generate(rules),
        )
    }

    /** `fileinto` alone, on the two address fields that are not `cc`, both match tags. */
    @Test fun `moving mail emits the fileinto require only`() {
        val rules = listOf(
            FilterRule(
                name = "boss", field = RuleField.FROM, match = RuleMatch.CONTAINS,
                value = "boss@example.com", moveTo = "Work",
            ),
            FilterRule(
                name = "list", field = RuleField.TO, match = RuleMatch.IS,
                value = "list@example.com", moveTo = "Lists/In",
            ),
        )
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"boss","enabled":true,"field":"FROM","match":"CONTAINS",""" +
                    """"value":"boss@example.com","moveTo":"Work","markRead":false,"flag":false},""" +
                    """{"name":"list","enabled":true,"field":"TO","match":"IS",""" +
                    """"value":"list@example.com","moveTo":"Lists/In","markRead":false,"flag":false}]""",
                """require ["fileinto"];""",
                "",
                """if address :contains "from" "boss@example.com" {""",
                """    fileinto "Work";""",
                "}",
                """if address :is "to" "list@example.com" {""",
                """    fileinto "Lists/In";""",
                "}",
            ),
            SieveCodec.generate(rules),
        )
    }

    /** Flags alone, on `cc`: `imap4flags` only, and Seen before Flagged. */
    @Test fun `flagging mail emits the imap4flags require only`() {
        val rules = listOf(
            FilterRule(
                name = "cc", field = RuleField.CC, match = RuleMatch.CONTAINS,
                value = "team@example.com", markRead = true, flag = true,
            ),
        )
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"cc","enabled":true,"field":"CC","match":"CONTAINS",""" +
                    """"value":"team@example.com","moveTo":null,"markRead":true,"flag":true}]""",
                """require ["imap4flags"];""",
                "",
                """if address :contains "cc" "team@example.com" {""",
                """    addflag "\\Seen";""",
                """    addflag "\\Flagged";""",
                "}",
            ),
            SieveCodec.generate(rules),
        )
    }

    /**
     * Both requires at once, the flags emitted BEFORE the `fileinto` so the filed copy carries
     */
    @Test fun `moving and flagging emits both requires, escaped`() {
        val rules = listOf(
            FilterRule(
                name = "odd", field = RuleField.SUBJECT, match = RuleMatch.CONTAINS,
                value = """a"b\c""", moveTo = """Odd"Folder\X""", markRead = true,
            ),
            FilterRule(name = "blank", value = "   ", flag = true),
        )
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"odd","enabled":true,"field":"SUBJECT","match":"CONTAINS",""" +
                    """"value":"a\"b\\c","moveTo":"Odd\"Folder\\X","markRead":true,"flag":false},""" +
                    """{"name":"blank","enabled":true,"field":"FROM","match":"CONTAINS",""" +
                    """"value":"   ","moveTo":null,"markRead":false,"flag":true}]""",
                """require ["fileinto", "imap4flags"];""",
                "",
                """if header :contains "subject" "a\"b\\c" {""",
                """    addflag "\\Seen";""",
                """    fileinto "Odd\"Folder\\X";""",
                "}",
            ),
            SieveCodec.generate(rules),
        )
    }

    /** A flag-only rule that is not marked read: the second `addflag` branch, alone. */
    @Test fun `flagging without marking read emits the Flagged addflag alone`() {
        val rules = listOf(
            FilterRule(name = "star", field = RuleField.FROM, match = RuleMatch.IS, value = "a@b.c", flag = true),
        )
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"star","enabled":true,"field":"FROM","match":"IS",""" +
                    """"value":"a@b.c","moveTo":null,"markRead":false,"flag":true}]""",
                """require ["imap4flags"];""",
                "",
                """if address :is "from" "a@b.c" {""",
                """    addflag "\\Flagged";""",
                "}",
            ),
            SieveCodec.generate(rules),
        )
    }

    private fun script(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    // ---- the source lint: a LAST RESORT on top of the golden, for the branches it does not run ----

    /**
     * Whole lines, never substrings: a `contains` is blind to every mutation that makes a line
     */
    @Test fun `the Sieve this codec emits is pinned to the marker version`() {
        val source = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/filter/SieveCodec.kt")
        assertEquals(
            V2_RULE,
            listOf(
                """fun generate(rules: List<FilterRule>): String {""",
                """val sb = StringBuilder()""",
                """sb.append(MARKER).append(' ').append(json.encodeToString(serializer, rules)).append('\n')""",
                """val active = rules.filter { it.enabled && it.value.isNotBlank() }""",
                """val requires = buildList {""",
                """if (active.any { it.moveTo != null }) add("fileinto")""",
                """if (active.any { it.markRead || it.flag }) add("imap4flags")""",
                """}""",
                """if (requires.isNotEmpty()) {""",
                """sb.append("require [").append(requires.joinToString(", ") { "\"${'$'}it\"" }).append("];\n")""",
                """}""",
                """sb.append('\n')""",
                """for (rule in active) sb.append(ruleToSieve(rule)).append('\n')""",
                """return sb.toString()""",
                """}""",
            ),
            SourceText.codeLines(
                SourceText.functionSource(source, "fun generate(rules: List<FilterRule>): String {"),
            ),
        )
        assertEquals(
            V2_RULE,
            listOf(
                """private fun ruleToSieve(rule: FilterRule): String {""",
                """val matchTag = if (rule.match == RuleMatch.IS) ":is" else ":contains" """.trimEnd(),
                """val value = escape(rule.value)""",
                """val test = when (rule.field) {""",
                """RuleField.SUBJECT -> "header ${'$'}matchTag \"subject\" \"${'$'}value\"" """.trimEnd(),
                """RuleField.FROM -> "address ${'$'}matchTag \"from\" \"${'$'}value\"" """.trimEnd(),
                """RuleField.TO -> "address ${'$'}matchTag \"to\" \"${'$'}value\"" """.trimEnd(),
                """RuleField.CC -> "address ${'$'}matchTag \"cc\" \"${'$'}value\"" """.trimEnd(),
                """}""",
                """val body = StringBuilder()""",
                """if (rule.markRead) body.append("    addflag \"\\\\Seen\";\n")""",
                """if (rule.flag) body.append("    addflag \"\\\\Flagged\";\n")""",
                """if (rule.moveTo != null) body.append("    fileinto \"${'$'}{escape(rule.moveTo)}\";\n")""",
                """return "if ${'$'}test {\n${'$'}body}" """.trimEnd(),
                """}""",
            ),
            SourceText.codeLines(SourceText.functionSource(source, "private fun ruleToSieve(rule: FilterRule): String {")),
        )
        // `escape` is emitted Sieve too, and it was the hole: it is the LAST member of the
        // object, so the slice that ends at the next KDoc found nothing and it stayed unpinned.
        assertEquals(
            V2_RULE,
            listOf(
                """private fun escape(value: String): String =""",
                """value.replace("\\", "\\\\").replace("\"", "\\\"")""",
            ),
            SourceText.codeLines(SourceText.functionSource(source, "private fun escape(value: String): String =")),
        )
    }

    /**
     * The golden for [SieveCodec] escaping, and the answer to "what about a value with a newline
     */
    @Test fun `a control character in a value is emitted as-is, not escaped`() {
        val rules = listOf(
            FilterRule(name = "odd", field = RuleField.SUBJECT, match = RuleMatch.IS, value = "a\nb\tc"),
        )
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"odd","enabled":true,"field":"SUBJECT","match":"IS",""" +
                    """"value":"a\nb\tc","moveTo":null,"markRead":false,"flag":false}]""",
                "",
                """if header :is "subject" "a""",
                "b\tc\" {",
                "}",
            ),
            SieveCodec.generate(rules),
        )
    }

    private companion object {
        const val V2_RULE =
            "The Sieve this codec emits has changed. Reading a script now compares the Sieve on " +
                "the server to the Sieve we would write, so every script written by an earlier " +
                "version will be reported as containing hand-written Sieve — on the whole " +
                "installed base, silently, until each user confirms an overwrite.\n" +
                "So: bump MARKER to `# STERNA-RULES-V2:` in SieveCodec, then update the golden " +
                "witnesses above (they show the emitted Sieve in full) and these pinned lines.\n" +
                "If the change emits no different Sieve — a rename, a comment — just update the " +
                "pinned lines."
    }
}
