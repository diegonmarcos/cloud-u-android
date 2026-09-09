package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE + RESOURCE LINT, NOT A BEHAVIOUR TEST — said first, because what it guards is a RENDERING
 */
class AccountToastFitTest {

    /**
     * 56 characters, and where the number comes from — a bench reading, three steps, all of them
     */
    private val maxToastChars = 56

    /**
     * The rule that keeps the other two honest: what is being measured is FOUND.
     */
    @Test fun `the sentences a finished add can show are found, and there is at least one`() {
        val body = decisionBody()
        assertTrue(
            "the body of accountAddedToast must still name at least one R.string: the re-add " +
                "sentence is chosen THERE and nowhere else (the routes are forbidden to name it, " +
                "see OAuthCodeWiringTest), so a body that names none has moved the decision " +
                "somewhere this rule cannot see, and every length below would be measured over a " +
                "set missing the one sentence this file exists for. Body was:\n$body",
            resourceIds(body).isNotEmpty(),
        )
        assertTrue(
            "no call to accountAddedToast( was found under app/src/main/kotlin — either the toast " +
                "is no longer shown anywhere (in which case delete this file, do not leave it " +
                "green) or the call was renamed and this rule has stopped reading the routes' own " +
                "sentences.",
            callSites().isNotEmpty(),
        )
        assertTrue(
            "no string id at all was extracted; the two rules below would then pass over an empty " +
                "set and report success while measuring nothing.",
            toastIds().isNotEmpty(),
        )
    }

    /**
     * FAIL CLOSED on the resources, second half. An id the string map does not resolve is measured
     */
    @Test fun `every sentence an add ends on resolves in the nine languages`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val unresolved = toastIds().flatMap { id ->
            strings.filterValues { id !in it }.keys.map { "R.string.$id: not read from $it" }
        }
        assertEquals(
            "every sentence an add can end on must be READABLE by this rule in all nine locales, " +
                "or the length rule is measuring nothing while reporting success. An id that " +
                "resolves nowhere is not a translation problem — TranslationParityTest is happy " +
                "with a key that is consistently absent from THIS rule's view — it is this rule " +
                "going blind. Unresolved:\n" + unresolved.joinToString("\n"),
            emptyList<String>(),
            unresolved,
        )
    }

    /**
     * THE DEFECT: a two-line box, and a sentence longer than two lines.
     */
    @Test fun `no sentence an add ends on is too long for a toast`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val offenders = toastIds().sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.mapNotNull { (locale, texts) ->
                val text = texts[id] ?: return@mapNotNull null
                if (text.length <= maxToastChars) null
                else "$locale/$id is ${text.length} chars — \"$text\""
            }
        }
        assertEquals(
            "a Toast renders TWO LINES and cuts the rest with no ellipsis and no scroll: measured " +
                "on emu at 411 dp, 72 characters came out whole and 80 came out as \"… Seine " +
                "Verbindung wurde a…\". What is lost is the clause saying the connection was " +
                "REFRESHED — so the reader is left unable to tell whether the sign-in did " +
                "anything, and adds the same mailbox again. A sentence shown in a toast must hold " +
                "in $maxToastChars characters in all nine languages (see the derivation on " +
                "maxToastChars: a 411 dp bench reading brought back to the 360 dp bench, with a " +
                "margin for scripts that set wider than Latin). Too long:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * THE OTHER HALF, and the one the plafond above cannot see: a sentence can FIT and still be
     */
    @Test fun `the re-add sentence says a re-add, in every language, and not a create`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val refreshed = resourceIds(decisionBody()).toSet()
        val created = callSites().flatMap { createdResOf(it) }.toSet() - refreshed
        assertTrue(
            "this rule needs both sides to exist: the re-add sentence chosen in the body of " +
                "$DECISION (found: $refreshed) and the sentences the routes say on a real create " +
                "(found: $created). With either side empty the comparison below is vacuous and " +
                "would report success over nothing.",
            refreshed.isNotEmpty() && created.isNotEmpty(),
        )

        val aliases = (refreshed + created).sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.mapNotNull { (locale, texts) ->
                val text = texts[id]?.trim() ?: return@mapNotNull null
                if (!text.startsWith("@")) null else "$locale/$id is a reference — \"$text\""
            }
        }
        assertEquals(
            "a sentence written as a resource reference is a sentence this file cannot read: " +
                "AAPT2 resolves \"@string/connect_account_added\" at build time and the reader " +
                "sees \"Account added\" after a re-add, while every rule here counts the 29 " +
                "characters of the alias and finds them short, distinct and well-formed. The " +
                "sentence must be spelled out in each language. Aliases:\n" +
                aliases.joinToString("\n"),
            emptyList<String>(),
            aliases,
        )

        val impostors = refreshed.sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.flatMap { (locale, texts) ->
                val text = texts[id] ?: return@flatMap emptyList()
                created.mapNotNull { other ->
                    val create = texts[other] ?: return@mapNotNull null
                    if (normalised(text) != normalised(create)) null
                    else "$locale/$id is \"$text\", which is $other (\"$create\")"
                }
            }
        }
        assertEquals(
            "⛔ the re-add sentence must not be a create sentence. Nothing was added: the account " +
                "was already installed and its connection refreshed in place. Told \"Account " +
                "added\", the reader goes looking for a second account in the list, does not find " +
                "one, and adds the mailbox again — which is the defect this whole branch removes, " +
                "and it comes back under the plafond without a single length rule going red. " +
                "Compared normalised, so a trailing full stop does not buy a pass. Found:\n" +
                impostors.joinToString("\n"),
            emptyList<String>(),
            impostors,
        )

        val halved = refreshed.sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.mapNotNull { (locale, texts) ->
                val text = texts[id] ?: return@mapNotNull null
                val propositions = text.split('.').map { it.trim() }.filter { it.isNotEmpty() }
                if (propositions.size >= 2) null
                else "$locale/$id has ${propositions.size} — \"$text\""
            }
        }
        assertEquals(
            "the re-add sentence must carry BOTH of its propositions: that the account was " +
                "already set up, and that its connection was refreshed. Shortening it to one of " +
                "the two is how it fits the two lines while ceasing to say what happened — the " +
                "reader is left either not knowing the address was already installed, or not " +
                "knowing the sign-in did anything at all. Two fragments around a full stop, in " +
                "every language. Found:\n" + halved.joinToString("\n"),
            emptyList<String>(),
            halved,
        )
    }

    /** Trimmed, lowercased, trailing punctuation dropped: what two sentences must differ by MORE
     *  than. "Konto hinzugefügt." and "Konto hinzugefügt" are the same sentence to a reader. */
    private fun normalised(text: String): String =
        text.trim().lowercase().trimEnd('.', '!', '?', '…', ' ')

    // -- extracting the sentences ---------------------------------------------------------------

    /**
     * Every string id a finished add can put on screen:
     */
    private fun toastIds(): Set<String> =
        (resourceIds(decisionBody()) + callSites().flatMap { createdResOf(it) }).toSet()

    /**
     * The body of `accountAddedToast`: its declaration line plus every indented line under it (a
     */
    private fun decisionBody(): String {
        val lines = code(VIEW_MODEL).lines()
        val at = lines.indexOfFirst { DECLARATION.containsMatchIn(it) }
        check(at >= 0) {
            "no 'fun $DECISION(' in ${VIEW_MODEL.name} — this lint reads that function's body to " +
                "learn which sentence a re-add shows, and has nothing to read if it moved or was " +
                "renamed"
        }
        val body = lines.drop(at + 1).takeWhile { it.startsWith(" ") || it.startsWith("\t") }
        return (listOf(lines[at]) + body).joinToString("\n")
    }

    /** The argument text of every call to `accountAddedToast(` under `app/src/main/kotlin`, the
     *  declaration itself excluded — it matches the same name and carries no resource. */
    private fun callSites(): List<String> = File(root, "app/src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .flatMap { file ->
            val text = code(file)
            CALL.findAll(text).map { file.name to balanced(text, it.range.last) }
        }
        .map { (name, args) -> "$name: $args" }
        .toList()

    /**
     * The `createdRes` argument of one call, as a list: empty for `null` (a route that stays silent
     */
    private fun createdResOf(callSite: String): List<String> {
        val args = arguments(callSite.substringAfter(": "))
        assertEquals(
            "a call to $DECISION must pass exactly two arguments; this rule reads the second one " +
                "to learn what that route says on a create. Found in $callSite",
            2,
            args.size,
        )
        val createdRes = args[1]
        if (createdRes == "null") return emptyList()
        val id = RESOURCE.matchEntire(createdRes)?.groupValues?.get(1)
        assertTrue(
            "the second argument of $DECISION must be exactly 'R.string.<id>' or 'null' — found " +
                "'$createdRes' in $callSite. A sentence reached any other way is text this rule " +
                "cannot measure, and it would then sit in a two-line box at any length with every " +
                "rule here still green.",
            id != null,
        )
        return listOf(id!!)
    }

    private fun resourceIds(text: String): List<String> =
        RESOURCE.findAll(text).map { it.groupValues[1] }.toList()

    // -- reading the sources and the resources --------------------------------------------------

    /** Top-level arguments of a call's argument text: commas inside nested brackets do not split. */
    private fun arguments(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        text.forEachIndexed { i, c ->
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    out += text.substring(start, i)
                    start = i + 1
                }
            }
        }
        out += text.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun balanced(text: String, from: Int): String {
        val start = text.indexOf('(', from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    /** The file with its comment lines and trailing comments removed, so no rule here can be
     *  satisfied — or defeated — by prose. Copied from `SenderRuleDialogFitTest`. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

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

    /** The nine shipped locales, as `SenderRuleDialogFitTest.locales()` counts them: a tenth
     *  language added without a translation of these sentences must not slip through as
     *  "not checked". */
    private fun locales(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "strings.xml").isFile }
        .sortedBy { it.name }
        .also { check(it.size == 9) { "expected 9 locales, found ${it.size}" } }

    private fun stringsOf(dir: File): Map<String, String> =
        STRING.findAll(File(dir, "strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val DECISION = "accountAddedToast"

        /** The declaration, and the call, told apart — the declaration carries no resource and
         *  would fail the two-argument reading on its own parameter list. */
        val DECLARATION = Regex("""\bfun\s+$DECISION\(""")
        val CALL = Regex("""(?<!fun )\b$DECISION\(""")

        /** One `R.string.<id>`. */
        val RESOURCE = Regex("""R\.string\.(\w+)""")

        /**
         * A `<string>` with its text — attributes ALLOWED, as `TranslationParityTest` reads them.
         */
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources and resources as text and needs a working directory inside " +
                        "the checkout",
                )
        }

        val res: File by lazy { File(root, "app/src/main/res") }

        val VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt")
        }
    }
}
