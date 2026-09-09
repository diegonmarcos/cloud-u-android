package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What reading an account's filters answers — [loadedFilterRules], executed.
 */
class FilterRulesReadTest {

    private val rule = FilterRule(
        name = "Boss", field = RuleField.FROM, match = RuleMatch.IS, value = "boss@example.com",
        moveTo = "Work",
    )

    @Test fun `a script this app wrote gives its rules back, with no warning`() {
        val state = loadedFilterRules(SieveCodec.generate(listOf(rule)), foreignScript = null)
        assertEquals(listOf(rule), state.rules)
        assertFalse("a script we can read is not foreign", state.foreignActiveScript)
    }

    @Test fun `no sterna script at all is zero rules, and no warning`() {
        val state = loadedFilterRules(sternaScript = null, foreignScript = null)
        assertEquals(emptyList<FilterRule>(), state.rules)
        assertFalse(state.foreignActiveScript)
    }

    /**
     * THE BUG. A `sterna` script with content nobody can decode used to come back as
     */
    @Test fun `an unreadable sterna script warns instead of reporting no rules`() {
        val state = loadedFilterRules("if header :is \"subject\" \"x\" { discard; }", foreignScript = null)
        assertEquals("nothing readable, so no rules to show", emptyList<FilterRule>(), state.rules)
        assertTrue(
            "a script we cannot read must refuse the write, not look like an empty one",
            state.foreignActiveScript,
        )
        assertTrue(
            "…and it must say WHICH of the two refusals it is: folded into foreignActiveScript " +
                "alone, the filters screen can only show a sentence about somebody else's script",
            state.scriptUnreadable,
        )
    }

    /**
     * The two causes are told apart on the way out even though they add up on the way in. Without
     */
    @Test fun `only the unreadable script raises the unreadable flag`() {
        assertFalse(
            "another active script says nothing about whether OURS could be read",
            loadedFilterRules(SieveCodec.generate(listOf(rule)), foreignScript = ForeignScript("roundcube")).scriptUnreadable,
        )
        assertFalse(
            "no script at all is not an unreadable one",
            loadedFilterRules(null, foreignScript = ForeignScript("roundcube")).scriptUnreadable,
        )
        assertFalse(
            "a blank script holds nothing to lose",
            loadedFilterRules("  ", foreignScript = null).scriptUnreadable,
        )
        assertTrue(
            "broken metadata is unreadable, whatever else is running",
            loadedFilterRules("# STERNA-RULES-V1: {oops\nif true { keep; }", foreignScript = ForeignScript("roundcube"))
                .scriptUnreadable,
        )
    }

    /** Marker present but its JSON broken: same refusal. Half a script is not zero rules. */
    @Test fun `a sterna script with broken metadata warns too`() {
        val state = loadedFilterRules("# STERNA-RULES-V1: {oops\nif true { keep; }", foreignScript = null)
        assertEquals(emptyList<FilterRule>(), state.rules)
        assertTrue(state.foreignActiveScript)
    }

    /**
     * The witness. A blank script is not unreadable — it holds nothing, so rewriting it loses
     */
    @Test fun `a blank sterna script is zero rules, and no warning`() {
        for (blank in listOf("", "   ", "\n\n")) {
            val state = loadedFilterRules(blank, foreignScript = null)
            assertEquals(blank.length.toString(), emptyList<FilterRule>(), state.rules)
            assertFalse("a blank script has nothing to lose: $blank", state.foreignActiveScript)
        }
    }

    /** Unchanged: another active script warns, whatever our own script says. */
    @Test fun `another active script warns even when ours reads fine`() {
        val state = loadedFilterRules(SieveCodec.generate(listOf(rule)), foreignScript = ForeignScript("roundcube"))
        assertEquals(listOf(rule), state.rules)
        assertTrue(state.foreignActiveScript)
    }

    /** The two causes add up rather than cancelling: either one alone is enough. */
    @Test fun `the two refusals add up`() {
        assertTrue(loadedFilterRules("if true { keep; }", foreignScript = ForeignScript("roundcube")).foreignActiveScript)
        assertFalse(loadedFilterRules(null, foreignScript = null).foreignActiveScript)
    }

    // ---- the foreign ACTIVE script, and its content (#209) --------------------------------------

    private val roundcubeBody = """
        require ["fileinto"];
        if header :contains "list-id" "kernel.org" { fileinto "Lists"; }
    """.trimIndent()

    /**
     * **#209, AS THE OWNER SAW IT.** No `sterna` script, and a script called `roundcube` filtering
     * every message the account receives. The old read answered this with a bare boolean, so the
     * screen said "another filter script is active" and "No rules yet. Add one to filter incoming
     * mail on the server." in the same breath — and the first rule the owner added would have
     * activated a one-rule script and stopped `roundcube`.
     *
     * The content must come back with the flag. It is what the screen shows INSTEAD of the empty
     * list, and what the confirmation names.
     */
    @Test fun `a foreign active script comes back with its text, not just a flag`() {
        val state = loadedFilterRules(
            sternaScript = null,
            foreignScript = ForeignScript("roundcube", roundcubeBody),
        )
        assertTrue("something else is filtering this account", state.foreignActiveScript)
        assertFalse("…and it is not OUR script that failed to parse", state.scriptUnreadable)
        assertEquals(
            "the script's own text must survive the read: it is the only thing this screen can " +
                "honestly show, and without it the empty list is all that is left",
            roundcubeBody,
            state.foreignScript?.body,
        )
        assertEquals(
            "and its name, because the confirmation asks about a script the owner must recognise",
            "roundcube",
            state.foreignScript?.name,
        )
    }

    /**
     * The two facts the brief demands be kept SEPARATE: what this app can model, and what it
     * cannot. `rules` is empty because none of `roundcube` is expressible as a [FilterRule] — not
     * because the account has no filters. Nothing from the foreign script leaks into `rules`,
     * because a save writes `rules` and anything that leaked would be a construct silently
     * rewritten into one this app happens to have a field for.
     */
    @Test fun `nothing from the foreign script is invented as a rule`() {
        val state = loadedFilterRules(null, ForeignScript("roundcube", roundcubeBody))
        assertEquals(
            "this app models a SUBSET of Sieve; guessing rules out of a script it did not write " +
                "would drop whatever it failed to understand, which is the same loss, quieter",
            emptyList<FilterRule>(),
            state.rules,
        )
        assertNotNull("…and the part it could not model is still carried, whole", state.foreignScript)
    }

    /**
     * A body that would not download stays **null**, never "". The distinction is the whole
     * safety property: an empty script is the one case where replacing it costs nothing, so an
     * unknown script that reads as an empty one is a save that looks free and is not.
     */
    @Test fun `a foreign script whose body would not download is not an empty one`() {
        val state = loadedFilterRules(null, ForeignScript("roundcube", body = null))
        assertTrue("we still know something is filtering this account", state.foreignActiveScript)
        assertNull("and we must not pretend to know what", state.foreignScript?.body)
        assertEquals("roundcube", state.foreignScript?.name)
    }

    /** Our own readable rules and a foreign active script are both true, and both reported. */
    @Test fun `our rules and a foreign active script are carried together`() {
        val state = loadedFilterRules(
            SieveCodec.generate(listOf(rule)),
            ForeignScript("roundcube", roundcubeBody),
        )
        assertEquals("ours parsed fine and must still be listed", listOf(rule), state.rules)
        assertEquals(
            "…and the script actually RUNNING is still the other one, quoted in full",
            roundcubeBody,
            state.foreignScript?.body,
        )
    }

    /** The witness: no foreign script means no text to show, and nothing to warn about. */
    @Test fun `an account with only our own script carries no foreign text`() {
        val state = loadedFilterRules(SieveCodec.generate(listOf(rule)), foreignScript = null)
        assertNull(state.foreignScript)
        assertFalse(state.foreignActiveScript)
    }

    /**
     * The flag and the content can no longer disagree, because there is only one of them. This is
     * the shape of #209 stated as an invariant: `foreignActiveScript` was settable from the script
     * LIST alone, so the app could assert a foreign script was active while holding nothing it
     * said. Now the boolean is derived from the value, and cannot be raised without it — except by
     * the unreadable-`sterna` cause, which is about our OWN script and has its own flag.
     */
    @Test fun `a foreign script can never be announced without its own record`() {
        for (foreign in listOf(null, ForeignScript("roundcube", roundcubeBody), ForeignScript("x", null))) {
            val state = loadedFilterRules(sternaScript = null, foreignScript = foreign)
            assertEquals(
                "with no unreadable script of ours in play, the flag IS `foreignScript != null`: " +
                    "any other source for it is a claim with no evidence behind it ($foreign)",
                foreign != null,
                state.foreignActiveScript,
            )
            assertEquals("…and it is that same record that is carried", foreign, state.foreignScript)
        }
    }

    // ---- a script we read only PART of: our marker, and hand-written Sieve around it ----

    /** The marker line `generate` writes for [rule] alone, JSON and all. */
    private val bossMarker =
        """# STERNA-RULES-V1: [{"name":"Boss","enabled":true,"field":"FROM","match":"IS",""" +
            """"value":"boss@example.com","moveTo":"Work","markRead":false,"flag":false}]"""

    /** The Sieve `generate` emits for [rule] alone, line by line. */
    private val bossSieve = arrayOf(
        """require ["fileinto"];""",
        "",
        """if address :is "from" "boss@example.com" {""",
        """    fileinto "Work";""",
        "}",
    )

    private fun script(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    /**
     * THE BUG THIS BRANCH FIXES. A perfectly valid marker line, and around it Sieve this app
     */
    @Test fun `a rule written by hand after our own marks the script unreadable`() {
        val state = loadedFilterRules(
            script(
                bossMarker,
                *bossSieve,
                """if header :contains "subject" "sale" {""",
                "    discard;",
                "}",
            ),
            foreignScript = null,
        )
        assertEquals("the rules we DID read are real and must stay on screen", listOf(rule), state.rules)
        assertTrue("Sieve we did not write must refuse the per-sender write", state.foreignActiveScript)
        assertTrue(
            "…and Save on the filters screen must ask before replacing it",
            state.scriptUnreadable,
        )
    }

    /**
     * **THE LIKELIEST SHAPE OF THE BUG, and the one every other test here misses.** Sterna
     */
    @Test fun `an empty rule list with hand-written Sieve around it is still unreadable`() {
        val state = loadedFilterRules(
            script(
                "# STERNA-RULES-V1: []",
                "",
                """require ["fileinto"];""",
                """if header :contains "subject" "roundcube" {""",
                """    fileinto "Archive";""",
                "}",
            ),
            foreignScript = null,
        )
        assertEquals("we read no rules of our own out of it", emptyList<FilterRule>(), state.rules)
        assertTrue(
            "zero rules of ours is not zero content: the per-sender write must stop",
            state.foreignActiveScript,
        )
        assertTrue("…and Save must ask before replacing it", state.scriptUnreadable)
    }

    /** The marker is not on line 1: everything above it is Sieve nobody here compiled. */
    @Test fun `hand-written Sieve before the marker marks the script unreadable`() {
        val state = loadedFilterRules(
            script(
                """if header :contains "subject" "invoice" {""",
                """    fileinto "Bills";""",
                "}",
                bossMarker,
                *bossSieve,
            ),
            foreignScript = null,
        )
        assertEquals(listOf(rule), state.rules)
        assertTrue(state.foreignActiveScript)
        assertTrue(state.scriptUnreadable)
    }

    /** A `require` this codec would never have emitted: someone edited the script. */
    @Test fun `an extra require marks the script unreadable`() {
        val state = loadedFilterRules(
            script(bossMarker, """require ["vacation"];""", *bossSieve),
            foreignScript = null,
        )
        assertEquals(listOf(rule), state.rules)
        assertTrue(state.foreignActiveScript)
        assertTrue(state.scriptUnreadable)
    }

    /**
     * Only the FIRST marker line is ours — the one [SieveCodec.parseRulesOrNull] read. A second one
     * further down is content in excess, so it counts as foreign like any other line.
     */
    @Test fun `a second marker line lower down marks the script unreadable`() {
        val state = loadedFilterRules(
            script(bossMarker, *bossSieve, """# STERNA-RULES-V1: [{"name":"Ghost"}]"""),
            foreignScript = null,
        )
        assertEquals(listOf(rule), state.rules)
        assertTrue(state.foreignActiveScript)
        assertTrue(state.scriptUnreadable)
    }

    /** The JSON says `Work`, the Sieve says `Archive`: the script no longer does what it lists. */
    @Test fun `Sieve edited away from the metadata marks the script unreadable`() {
        val state = loadedFilterRules(
            script(
                bossMarker,
                """require ["fileinto"];""",
                "",
                """if address :is "from" "boss@example.com" {""",
                """    fileinto "Archive";""",
                "}",
            ),
            foreignScript = null,
        )
        assertEquals(listOf(rule), state.rules)
        assertTrue(state.foreignActiveScript)
        assertTrue(state.scriptUnreadable)
    }

    /** Both causes at once: neither cancels the other, and the red line must still name ours. */
    @Test fun `a foreign script warns as unreadable even when another script is active`() {
        val state = loadedFilterRules(
            script(bossMarker, *bossSieve, "keep;"),
            foreignScript = ForeignScript("roundcube"),
        )
        assertTrue(state.foreignActiveScript)
        assertTrue("another active script must not hide that OURS was half-read", state.scriptUnreadable)
    }

    // ---- the witnesses: what must NOT be called foreign ----

    /** What this app writes, read straight back. Anything else here would be a false alarm. */
    @Test fun `a script this app just generated is never foreign`() {
        val rules = listOf(
            rule,
            FilterRule(name = "off", enabled = false, value = "x@y.com", moveTo = "Work"),
            FilterRule(name = "flagged", field = RuleField.SUBJECT, value = "urgent", flag = true),
        )
        for (list in listOf(emptyList(), listOf(rule), rules)) {
            val state = loadedFilterRules(SieveCodec.generate(list), foreignScript = null)
            assertEquals(list, state.rules)
            assertFalse("we wrote this one: $list", state.foreignActiveScript)
            assertFalse("we wrote this one: $list", state.scriptUnreadable)
        }
    }

    /**
     * THE WITNESS THAT FIXES THE COMPARED VALUE. A script written by an OLDER version of this
     */
    @Test fun `a marker written by another version of this app is not foreign`() {
        val extraKey =
            """# STERNA-RULES-V1: [{"name":"Boss","enabled":true,"field":"FROM","match":"IS",""" +
                """"value":"boss@example.com","moveTo":"Work","markRead":false,"flag":false,""" +
                """"colour":"red"}]"""
        val missingKeys =
            """# STERNA-RULES-V1: [{"name":"Boss","field":"FROM","match":"IS",""" +
                """"value":"boss@example.com","moveTo":"Work"}]"""
        for (marker in listOf(extraKey, missingKeys)) {
            val state = loadedFilterRules(script(marker, *bossSieve), foreignScript = null)
            assertEquals(listOf(rule), state.rules)
            assertFalse("only the JSON differs, and the JSON is not Sieve: $marker", state.foreignActiveScript)
            assertFalse(marker, state.scriptUnreadable)
        }
    }

    /** A server that hands the blob back in CRLF is not a server that rewrote the script. */
    @Test fun `the same script in CRLF is not foreign`() {
        val crlf = SieveCodec.generate(listOf(rule)).replace("\n", "\r\n")
        val state = loadedFilterRules(crlf, foreignScript = null)
        assertEquals(listOf(rule), state.rules)
        assertFalse(state.foreignActiveScript)
        assertFalse(state.scriptUnreadable)
    }

    /** Trailing blanks on two lines and no final newline: still our script, character for word. */
    @Test fun `trailing spaces and a missing final newline are not foreign`() {
        val sloppy = SieveCodec.generate(listOf(rule))
            .replace(";\n", ";  \n")
            .trimEnd('\n')
        val state = loadedFilterRules(sloppy, foreignScript = null)
        assertEquals(listOf(rule), state.rules)
        assertFalse(state.foreignActiveScript)
        assertFalse(state.scriptUnreadable)
    }

    /**
     * The non-regression witness. A blank script holds nothing to lose, so it is zero rules and
     */
    @Test fun `a blank script is never foreign`() {
        for (blank in listOf("", "   ", "\n\n", "\r\n\r\n")) {
            val state = loadedFilterRules(blank, foreignScript = null)
            assertEquals(emptyList<FilterRule>(), state.rules)
            assertFalse("a blank script has nothing to lose: ${blank.length}", state.foreignActiveScript)
            assertFalse("a blank script has nothing to lose: ${blank.length}", state.scriptUnreadable)
        }
    }

    /** The decision on its own, with literal arguments — no state, no fold, just the comparison. */
    @Test fun `holdsForeignSieve compares the Sieve and ignores the marker line`() {
        assertTrue(
            "one hand-written line in excess is enough",
            holdsForeignSieve(script(bossMarker, *bossSieve, "keep;"), listOf(rule)),
        )
        assertFalse(
            "the same Sieve under a marker from another version of this app is ours",
            holdsForeignSieve(
                script(
                    """# STERNA-RULES-V1: [{"name":"Boss","field":"FROM","match":"IS",""" +
                        """"value":"boss@example.com","moveTo":"Work","extra":1}]""",
                    *bossSieve,
                ),
                listOf(rule),
            ),
        )
        // Measured on 2026-08-12, and left standing: deleting the `script.isBlank()` guard from
        // holdsForeignSieve leaves the whole suite green. It is EQUIVALENT BY CONSTRUCTION, not a
        assertFalse("blank is never foreign", holdsForeignSieve("   \n\t\n", emptyList()))
        assertFalse("what we write is never foreign", holdsForeignSieve(SieveCodec.generate(listOf(rule)), listOf(rule)))
    }

    // ---- the wiring, read out of the shipped source: a LAST RESORT, not the proof ----

    /**
     * Everything above executes the decision; this one cannot. `MailRepository` opens shared
     */
    @Test fun `the repository hands the decision the script it downloaded`() {
        val body = loadFilterRulesBody()
        assertTrue(
            "loadFilterRules must decode a downloaded blob into a script it can hand over",
            body.contains("val script = managed?.let { bodyOf(it) }") &&
                body.contains(".toString(Charsets.UTF_8)"),
        )
        assertTrue(
            "the downloaded script itself must be the argument — not null, not an empty string",
            body.contains("sternaScript = script,"),
        )
        // #209. `scripts.any { it.isActive && … }` answered the other-script question from the
        // LIST, which carries names and flags and never content — so the screen knew a foreign
        // script was filtering the account, held its blobId, and drew "No rules yet. Add one…".
        // The selection must produce the SCRIPT, and the script must be downloaded.
        assertTrue(
            "the active foreign script must be SELECTED, not counted: `scripts.firstOrNull { " +
                "it.isActive && it.name != SieveCodec.SCRIPT_NAME }`, so there is something to " +
                "download. `scripts.any { … }` here is the bug, restored",
            body.contains("scripts.firstOrNull { it.isActive && it.name != SieveCodec.SCRIPT_NAME }"),
        )
        assertFalse(
            "…and the boolean form must be gone with it: either call answers `foreignActive`, " +
                "and the one that cannot carry a body is how the fetch went missing",
            body.contains("scripts.any { it.isActive"),
        )
        assertTrue(
            "the foreign script's BODY must be fetched through the same downloader as ours — a " +
                "name-only ForeignScript is the empty screen again, with more code behind it",
            body.contains("body = runCatching { bodyOf(other) }"),
        )
        assertTrue(
            "a body that would not download must stay null, never \"\": an unknown script that " +
                "reads as an empty one is a save that looks free",
            body.contains("getOrElseUnlessCancelled { null }"),
        )
        assertTrue(
            "both facts must reach the decision in ONE value: name and body together, so a " +
                "caller cannot claim a foreign script is active without carrying what it says",
            body.contains("loadedFilterRules(sternaScript = script, foreignScript = foreign)"),
        )
        // Measured: narrowing this selection to the ACTIVE script left the whole suite green, and
        // it reopens the very bug above — an unreadable script that is merely INACTIVE is then not
        assertEquals(
            "the account's own script must be selected BY NAME ALONE, as exactly `val managed = " +
                "scripts.firstOrNull { it.name == SieveCodec.SCRIPT_NAME }` and nothing more on " +
                "that line: a script that is not the active one is still the script this save " +
                "would overwrite",
            listOf("val managed = scripts.firstOrNull { it.name == SieveCodec.SCRIPT_NAME }"),
            declarationsOf(body, "managed"),
        )
        assertFalse(
            "the state must come from loadedFilterRules, not be assembled beside it",
            body.contains("FilterRulesState.Loaded("),
        )
    }

    /**
     * Every WHOLE line of [body] declaring `val [name]`, trimmed, comments dropped.
     */
    private fun declarationsOf(body: String, name: String): List<String> =
        SourceText.codeLines(body).filter { it.substringBefore(" =") == "val $name" }

    /** The body of `MailRepository.loadFilterRules`, from its signature to the next declaration. */
    private fun loadFilterRulesBody(): String = SourceText.functionSource(
        SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"),
        "suspend fun loadFilterRules(credentials: AccountCredentials)",
    )
}
