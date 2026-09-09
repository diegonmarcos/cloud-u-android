package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE + RESOURCE LINT, NOT A BEHAVIOUR TEST — said first, because what it guards is a RENDERING
 */
class DraftKeptToastFitTest {

    /**
     * 56 characters — the SAME number as
     */
    private val maxToastChars = 56

    /**
     * FAIL CLOSED, first half: what is being measured is FOUND.
     */
    @Test fun `the sentence a save that could not replace shows is found`() {
        val blocks = noticeBlocks()
        assertTrue(
            "no 'if (draftSaveNeedsNotice(' block was found in ${VIEW_MODEL.name}. That guard is " +
                "where the sentence shown after a save that could not replace the reopened draft " +
                "is chosen, and it is the ONLY thing this file reads. Renamed, or moved to " +
                "another file, it takes the whole lint with it: every rule below would then " +
                "measure an empty set and report success while the sentence sits in a two-line " +
                "box at any length.",
            blocks.isNotEmpty(),
        )
        assertTrue(
            "the body of the 'if (draftSaveNeedsNotice(' guard names no R.string at all. Either " +
                "the notice is no longer shown (in which case delete this file, do not leave it " +
                "green) or it is reached through a variable or a when — text this file cannot " +
                "count. Block was:\n" + blocks.joinToString("\n---\n"),
            noticeIds().isNotEmpty(),
        )
    }

    /**
     * FAIL CLOSED, second half: the resources. An id the string map does not resolve is measured
     */
    @Test fun `the sentence resolves in the nine languages`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val unresolved = noticeIds().sorted().flatMap { id ->
            strings.filterValues { id !in it }.keys.sorted().map { "R.string.$id: not read from $it" }
        }
        assertEquals(
            "the sentence must be READABLE by this rule in all nine locales, or the rules below " +
                "are measuring nothing while reporting success. An id that resolves nowhere is " +
                "not a translation problem — TranslationParityTest is happy with a key " +
                "consistently absent from THIS rule's view — it is this rule going blind. " +
                "Unresolved:\n" + unresolved.joinToString("\n"),
            emptyList<String>(),
            unresolved,
        )
    }

    /**
     * THE DEFECT: a two-line box, and a sentence longer than two lines.
     */
    @Test fun `the sentence is not too long for a toast`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val offenders = noticeIds().sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.mapNotNull { (locale, texts) ->
                val text = unescaped(texts[id] ?: return@mapNotNull null)
                if (text.length <= maxToastChars) null
                else "$locale/$id is ${text.length} chars — \"$text\""
            }
        }
        assertEquals(
            "a Toast renders TWO LINES beside the app icon and ellipsises the rest: measured on " +
                "emu at 411 dp on 2026-08-25, this sentence came out as \"… weil diese Kop…\" in " +
                "German and \"… because this copy couldn't carry e…\" in English. A sentence " +
                "shown in a toast must hold in $maxToastChars characters in all nine languages " +
                "(derivation on maxToastChars, and in AccountToastFitTest: a 411 dp bench reading " +
                "brought back to the 360 dp bench, with a margin for scripts that set wider than " +
                "Latin). Too long:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * THE OTHER HALF, and the one the plafond above cannot see: a sentence can FIT and still be
     */
    @Test fun `the sentence keeps both of its propositions, in every language`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val halved = noticeIds().sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.mapNotNull { (locale, texts) ->
                val text = unescaped(texts[id] ?: return@mapNotNull null)
                val propositions = text.split('.').map { it.trim() }.filter { it.isNotEmpty() }
                if (propositions.size >= 2) null
                else "$locale/$id has ${propositions.size} — \"$text\""
            }
        }
        assertEquals(
            "the sentence must keep TWO non-empty fragments around a full stop — a proxy for " +
                "its two propositions, and only a proxy: this rule reads shape, never meaning, " +
                "so \"Enregistré comme nouveau brouillon. Original supprimé.\" satisfies it " +
                "while saying the opposite of what happened. What it does refuse is the COLLAPSE " +
                "to one clause, which is exactly how this sentence comes to fit two lines while " +
                "ceasing to say what happened: the reader is then left either not knowing her " +
                "edit was saved anywhere, or not knowing that there are now TWO drafts in the " +
                "folder and having no way to tell which one to keep. Found:\n" +
                halved.joinToString("\n"),
            emptyList<String>(),
            halved,
        )
    }

    /**
     * A sentence written as a resource reference is a sentence this file cannot read: AAPT2
     */
    @Test fun `the sentence is spelled out in every language, never an alias`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val aliases = noticeIds().sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.mapNotNull { (locale, texts) ->
                val text = texts[id]?.trim() ?: return@mapNotNull null
                if (!text.startsWith("@")) null else "$locale/$id is a reference — \"$text\""
            }
        }
        assertEquals(
            "everything this file measures is the XML text, never what Android renders, so an " +
                "'@string/…' alias is text this rule reads and the reader never sees. Aliases:\n" +
                aliases.joinToString("\n"),
            emptyList<String>(),
            aliases,
        )
    }

    // -- extracting the sentence ------------------------------------------------------------------

    /** Every `R.string.<id>` the notice guard can emit. */
    private fun noticeIds(): Set<String> =
        noticeBlocks().flatMap { RESOURCE.findAll(it).map { m -> m.groupValues[1] } }.toSet()

    /**
     * The body of every `if (draftSaveNeedsNotice(…)) { … }` in `ComposeViewModel.kt`, brace
     */
    private fun noticeBlocks(): List<String> {
        val text = code(VIEW_MODEL)
        return GUARD.findAll(text).map { balancedBraces(text, it.range.last) }.toList()
    }

    // -- reading the sources and the resources ----------------------------------------------------

    /**
     * What the READER sees, from what the XML holds: `\'`, `\"`, `\\` and `\n` are Android
     */
    private fun unescaped(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                out.append(if (text[i + 1] == 'n') '\n' else text[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** The `{ … }` block opening at or after [from], without its braces. */
    private fun balancedBraces(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        check(open >= 0) {
            "the 'if (draftSaveNeedsNotice(' at offset $from in ${VIEW_MODEL.name} opens no " +
                "block; this lint reads that block to learn which sentence the notice shows"
        }
        var depth = 1
        var i = open + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return text.substring(open + 1, (i - 1).coerceAtLeast(open + 1)).trim()
    }

    /** The file with its comment lines and trailing comments removed. Copied from
     *  `AccountToastFitTest`, which copied it from `SenderRuleDialogFitTest`. */
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

    /** The nine shipped locales, as `AccountToastFitTest.locales()` counts them. */
    private fun locales(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "strings.xml").isFile }
        .sortedBy { it.name }
        .also {
            check(it.size == 9) {
                "expected 9 locales carrying a strings.xml, found ${it.size} " +
                    "(${it.joinToString { d -> d.name }}) — a language added or a file moved " +
                    "leaves this sentence unmeasured in it"
            }
        }

    private fun stringsOf(dir: File): Map<String, String> =
        STRING.findAll(File(dir, "strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        /** The guard, and only the guard: the decision itself is executed in `LocalDraftSaveTest`,
         *  this file only follows it to the sentence. */
        val GUARD = Regex("""\bif\s*\(\s*draftSaveNeedsNotice\(""")

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
            File(root, "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt")
        }
    }
}
