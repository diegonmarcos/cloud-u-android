package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE + RESOURCE LINT, NOT A BEHAVIOUR TEST — and this file says so first because the defect it
 */
class SenderRuleDialogFitTest {

    /**
     * ≈ 14 characters is one line of a dialog action at the largest font scale — see the second
     */
    private val maxActionLabelChars = 14

    @Test fun `every confirmation is found, with both of its action slots`() {
        // The rule that keeps every other rule in this file honest. Each of the assertions below
        // reads slots out of a dialog located by its title string; if a dialog moves, is renamed,
        // or loses a slot, the extraction must fail LOUDLY here rather than quietly asserting over
        // an empty string somewhere else.
        assertEquals(
            "each confirmation that writes to the server must be found by its own title string",
            DIALOGS.map { it.name },
            DIALOGS.filter { runCatching { dialogSource(it) }.isSuccess }.map { it.name },
        )
        DIALOGS.forEach { dialog ->
            val source = dialogSource(dialog)
            listOf("title", "text", "confirmButton", "dismissButton").forEach { slot ->
                assertTrue(
                    "${dialog.name} must still carry a '$slot = {' slot — this file reads that " +
                        "slot and would otherwise pass over a dialog it never saw",
                    "$slot = {" in source,
                )
            }
        }
    }

    /**
     * Which slot WRITES. The one rule in this file that is not about layout, and the worst
     */
    @Test fun `the write leaves from the confirm slot of every confirmation, and the dismiss slot writes nothing`() {
        DIALOGS.forEach { dialog ->
            val source = dialogSource(dialog)
            val confirm = namedLambda(source, "confirmButton")
            val dismiss = namedLambda(source, "dismissButton")
            assertTrue(
                "${dialog.name} must write from its CONFIRM slot: no call matching $WRITE was " +
                    "found there. Either the write moved out of the confirmation — which is the " +
                    "defect this dialog exists to prevent — or it was renamed, in which case this " +
                    "rule must be taught the new name rather than left green. " +
                    "Confirm slot was:\n$confirm",
                WRITE.containsMatchIn(confirm),
            )
            assertEquals(
                "${dialog.name}'s DISMISS slot must write nothing at all. Swapped with the confirm " +
                    "slot it still compiles, every other rule stays green, and the permanent " +
                    "server-side write moves to where the user learned to find 'Cancel'. Found " +
                    "in the dismiss slot:\n$dismiss",
                emptyList<String>(),
                WRITE.findAll(dismiss).map { it.value }.toList(),
            )
        }
    }

    /**
     * The rule that makes the length rule mean anything: an action label is ONE WHOLE STRING
     */
    @Test fun `each action label of every confirmation is one whole string resource`() {
        DIALOGS.forEach { dialog ->
            listOf("confirmButton", "dismissButton").forEach { slot ->
                actionLabelIds(dialog, slot)
            }
        }
    }

    /**
     * Defect (A), at its cause: a button holds a verb, never a sentence.
     */
    @Test fun `no action label of any confirmation is long enough to wrap`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val offenders = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        DIALOGS.forEach { dialog ->
            listOf("confirmButton", "dismissButton").forEach { slot ->
                actionLabelIds(dialog, slot).forEach { id ->
                    // FAIL CLOSED. This read used to be `texts[id] ?: return@forEach`: an id the
                    // string map does not resolve was measured by nothing, silently, at any
                    val absent = strings.filterValues { id !in it }.keys
                    if (absent.isNotEmpty()) {
                        unresolved += "${dialog.name}/$slot: R.string.$id resolves in none of $absent"
                    }
                    strings.forEach { (locale, texts) ->
                        val text = texts[id] ?: return@forEach
                        if (text.length > maxActionLabelChars) {
                            offenders += "${dialog.name}/$slot: $locale/$id is ${text.length} chars — \"$text\""
                        }
                    }
                }
            }
        }
        assertEquals(
            "every action label of these dialogs must be READABLE by this rule in all nine " +
                "locales, or the length rule below is measuring nothing while reporting success. " +
                "An id that resolves nowhere is not a translation problem — TranslationParityTest " +
                "is happy with a key that is consistently absent from this rule's view — it is " +
                "this rule going blind. Unresolved:\n" + unresolved.joinToString("\n"),
            emptyList<String>(),
            unresolved,
        )
        assertEquals(
            "a dialog action label that wraps makes its row taller than the other action's, and " +
                "Material then paints the dismiss button INSIDE the confirm button (measured: " +
                "'Отмена' entirely within the confirm button's rectangle, on the gesture that " +
                "writes a permanent server-side rule). The fix is a verb, ≤ $maxActionLabelChars " +
                "characters in all nine languages — where the object does not fit, the verb alone " +
                "is the translation. Offenders:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Defect (B): Material's `text` slot is a height-bounded box with no scrolling of its own,
     */
    @Test fun `the body of every confirmation scrolls instead of being clipped`() {
        DIALOGS.forEach { dialog ->
            val body = namedLambda(dialogSource(dialog), "text")
            val lines = body.lines().map { it.trim() }
            // Written after watching the mutation survive, twice: `maxLines = 3` next to the scroll
            // modifier left this rule green and the body clipped at three lines again — the exact
            assertEquals(
                "${dialog.name}'s body must bound its text NOWHERE ELSE: a maxLines or " +
                    "an overflow beside the scroll modifier clips the sentence again, and the " +
                    "scroll then hides a cut instead of preventing one. Body was:\n$body",
                emptyList<String>(),
                lines.filter { "maxLines" in it || "overflow" in it },
            )
            assertEquals(
                "${dialog.name}'s body must carry exactly '$SCROLL_MODIFIER' on its " +
                    "own line. Material's text slot is a bounded box: without it the German body " +
                    "(236 characters, ~17 lines at font_scale 2.0) is cut mid-sentence with " +
                    "nothing on screen saying so — and what is lost is the half that names what " +
                    "the write costs, which is the reason each of these dialogs exists at all. " +
                    "Body was:\n$body",
                1,
                lines.count { it == SCROLL_MODIFIER },
            )
        }
    }

    // -- reading the sources and the resources ------------------------------------------------

    /** The argument text of the one `AlertDialog(` in [dialog]'s file that carries its title. */
    private fun dialogSource(dialog: Confirmation): String {
        val found = callArguments(code(dialog.file), "AlertDialog")
            .filter { "R.string.${dialog.titleId}" in it }
        check(found.size == 1) {
            "${dialog.file.name} must draw exactly one dialog titled R.string.${dialog.titleId}, " +
                "found ${found.size} — this lint reads that dialog's slots and has nothing to " +
                "read if it moved or was renamed"
        }
        return found.single()
    }

    /**
     * The string ids [slot] of [file]'s rule dialog uses as labels — with the SHAPE of each label
     */
    private fun actionLabelIds(dialog: Confirmation, slot: String): List<String> {
        val lambda = namedLambda(dialogSource(dialog), slot)
        val labels = callArguments(lambda, "Text")
        assertTrue(
            "${dialog.name}'s '$slot' draws no Text at all — a label built somewhere this rule " +
                "cannot see is a label this rule does not check. Slot was:\n$lambda",
            labels.isNotEmpty(),
        )
        return labels.map { content ->
            val id = LABEL.matchEntire(content.trim())?.groupValues?.get(1)
            assertTrue(
                "${dialog.name}'s '$slot' must read exactly 'stringResource(R.string.<id>)' and " +
                    "nothing more. Found '${content.trim()}'. Anything appended to the resource " +
                    "puts characters on the button that no rule here counts: " +
                    "'stringResource(R.string.sender_volume_block_confirm) + \" \" + " +
                    "ruleFor.email' renders 30 to 45 characters, wraps at a large font scale, and " +
                    "brings back the overlap this whole file exists for — with every length rule " +
                    "still green, because they count the RESOURCE.",
                id != null,
            )
            id!!
        }
    }

    /** The text of the `name = { … }` argument of a call, braces balanced. Fails loudly when the
     *  slot is gone: a rule quietly matching an empty string is worse than no rule. */
    private fun namedLambda(text: String, name: String): String {
        val at = text.indexOf("$name = {")
        check(at >= 0) { "no '$name = {' in:\n$text" }
        val open = text.indexOf('{', at)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        error("unbalanced braces after '$name ='")
    }

    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last) }
            .toList()

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
     *  satisfied — or defeated — by prose. */
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

    /** The nine shipped locales, as `SenderVolumeCopyTest` counts them: a tenth added without a
     *  translation of these labels must not slip through as "not checked". */
    private fun locales(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "strings.xml").isFile }
        .sortedBy { it.name }
        .also { check(it.size == 9) { "expected 9 locales, found ${it.size}" } }

    private fun stringsOf(dir: File): Map<String, String> =
        STRING.findAll(File(dir, "strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** One confirmation drawn in front of a permanent server-side write, found by its title. */
    private class Confirmation(val file: File, val titleId: String) {
        val name: String get() = "${file.name}'s R.string.$titleId dialog"
    }

    private companion object {
        /**
         * A `<string>` with its text — attributes ALLOWED, as `TranslationParityTest` reads them.
         */
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        /** An action label, whole: one `stringResource(R.string.<id>)` and nothing else. */
        val LABEL = Regex("""stringResource\(R\.string\.(\w+)\)""")

        /** The server-side write, on any of the three surfaces. Named, because nothing here can
         *  derive it — and every rule that uses it is written to fail when the name stops being
         *  found. `viewModel.save()` rewrites the whole Sieve script of the account. */
        val WRITE = Regex("""\b(onBlock|blockSender|viewModel\.save)\(""")

        /** The whole line, as the repo's other source lints pin theirs. */
        const val SCROLL_MODIFIER = "modifier = Modifier.verticalScroll(rememberScrollState()),"

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

        /**
         * Every confirmation these rules apply to. A dialog put in front of an irreversible
         */
        val DIALOGS: List<Confirmation> by lazy {
            listOf(
                Confirmation(
                    File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"),
                    "sender_volume_block_title",
                ),
                Confirmation(
                    File(root, "app/src/main/kotlin/app/sterna/ui/sender/MailBySenderScreen.kt"),
                    "sender_volume_block_title",
                ),
                Confirmation(
                    File(root, "app/src/main/kotlin/app/sterna/ui/settings/FiltersScreen.kt"),
                    "settings_filters_overwrite_title",
                ),
            )
        }
    }
}
