package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument and the same disclaimer as
 */
class LinkDialogWiringTest {

    /** (a) What the dialog OPENS with: one answer, for the live body and the live selection. */
    @Test fun `the dialog opens on the model's own answer for this body and this selection`() {
        assertPinned(
            "val opening = remember(body, selection) { linkDialogFields(body, selection) }",
            "the opening state must be `linkDialogFields` (executed in [LinkDialogTest]) on the " +
                "body and the selection the caller handed in, re-derived when either changes. " +
                "⛔ Keyed on nothing, the dialog reopens on the PREVIOUS selection's link and its " +
                "OK then re-addresses a link the user is no longer on.",
        )
        assertPinned(
            "var url by remember(opening) { mutableStateOf(opening.url) }",
            "the address box must START on the link's own address, or editing a link opens an " +
                "empty box and OK writes that emptiness over an address that was there.",
        )
        assertPinned(
            "var text by remember(opening) { mutableStateOf(opening.text) }",
            "…and the label box on the words the link covers, for the same reason.",
        )
        assertPinned(
            "enabled = opening.textEditable,",
            "⛔ the label box must be FROZEN whenever characters are already involved (a " +
                "selection, or a link being edited): the words are in the body, and letting this " +
                "box rewrite them is a second, invisible way to edit the message.",
        )
    }

    /** (b) What OK does with the two boxes — the mutation that emptied the label lives here. */
    @Test fun `OK hands the model the live body, selection, address and label`() {
        assertPinned(
            "val applied = linkApplied(body, selection, url, text)",
            "⛔ ALL FOUR arguments, and each one is a silent defect on its own. `text` replaced by " +
                "\"\" is the sharpest: a bare cursor with an address and a label typed then reaches " +
                "`setLink`'s do-nothing arm, the body comes back unchanged, `onApply` runs, the " +
                "dialog closes — and nothing happened, with no message. `url` replaced by " +
                "`opening.url` ignores what was just typed. `selection` replaced by anything else " +
                "puts the link somewhere the user is not looking.",
        )
        assertPinned(
            "if (applied == null) refused = true else onApply(applied)",
            "⛔ a refused address must RAISE the error and keep the dialog open. Inverted, or " +
                "reduced to `onApply(applied ?: body)`, a scheme this app will not follow closes " +
                "the dialog as though it had been accepted and the link is simply not there.",
        )
    }

    /** (c) Remove, and the door out. */
    @Test fun `remove takes the link off the live body, and is offered only when there is one`() {
        assertPinned(
            "TextButton(onClick = { onRemove(removeLink(body, selection)) }) {",
            "the remove button must call `removeLink` (executed in `RichBodyLinkTest`) on the LIVE " +
                "body and selection. ⛔ `onRemove(body)` there closes the dialog and leaves the " +
                "link exactly where it was, which is the button doing nothing at all.",
        )
        assertPinned(
            "if (opening.canRemove) {",
            "…and it is offered only when there IS a link to remove — the same answer the button " +
                "in the bar is lit by.",
        )
        assertPinned(
            "onDismissRequest = onDismiss,",
            "a tap outside and the system Back must close the dialog. Emptied to `{ }` the box " +
                "can only be left through one of its buttons.",
        )
    }

    /**
     * (d) The dialog's fields are Material `TextField`s and never `BasicTextField`.
     */
    @Test fun `the dialog's two boxes are Material fields, in this file`() {
        assertEquals(
            "⛔ no `BasicTextField(` may appear in $DIALOG",
            0,
            lines().count { "BasicTextField(" in it },
        )
        assertEquals(
            "the dialog has exactly TWO fields — the address and the label. A third is a way to " +
                "edit the message this lint has said nothing about.",
            2,
            lines().count { "TextField(" in it },
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    private fun assertPinned(pinned: String, why: String, times: Int = 1) {
        val hits = lines().count { it == pinned }
        assertEquals(
            "$why\nExpected exactly $times line(s) of $DIALOG whose trimmed text is:\n    $pinned" +
                "\nbut found $hits — this lint compares the WHOLE line, so a change that only " +
                "lengthens it lands here too.",
            times, hits,
        )
    }

    /** The dialog's code as trimmed whole lines, comments cut: prose must never satisfy a rule. */
    private fun lines(): List<String> = File(root, DIALOG).readLines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).trim().takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private companion object {
        const val DIALOG = "app/src/main/kotlin/app/sterna/ui/compose/LinkDialog.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, DIALOG).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }
    }
}
