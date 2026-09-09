package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeScreen.kt` and `SternaApp.kt` as text and
 */
class ComposeDeleteWiringTest {

    @Test fun `the composer's delete is the list's delete, through the shared inbox ViewModel`() {
        val route = composeRoute()
        val handler = argument(route, HANDLER)
        assertTrue(
            "the compose route must delegate onDeleteDraft to inboxViewModel.delete(email) — the " +
                "move to the Trash, with the Undo the list offers. Anything that destroys outright " +
                "(repo.discardDraft, a destroy call) makes \"Delete\" mean two different things in " +
                "one app, and the destructive one has no way back. Handler was:\n$handler",
            "inboxViewModel.delete(email)" in handler.replace(Regex("""\s+"""), " "),
        )
        assertTrue(
            "onDeleteDraft must not reach a destroying call. Handler was:\n$handler",
            "discardDraft" !in handler && "destroy" !in handler,
        )
        assertTrue(
            "the ViewModel it deletes through must be the INBOX's own — looked up on the inbox " +
                "backstack entry, the same instance the list and the reader share — or the Undo " +
                "snackbar would be posted to a ViewModel no screen is observing. Route was:\n$route",
            Regex("""nav\.getBackStackEntry\(\s*"inbox"\s*\)""").containsMatchIn(route) &&
                "viewModel(inboxEntry)" in route.replace(Regex("""\s+"""), " "),
        )
    }

    @Test fun `deleting a draft does not fly the tern away`() {
        val handler = argument(composeRoute(), HANDLER)
        assertTrue(
            "onDeleteDraft must leave with a plain popBackStack(), landing on the Drafts list it " +
                "was opened from — which is where the \"Message deleted / Undo\" snackbar shows. " +
                "popBackStack(\"inbox\", …) skips it, and ComposeState.Done flies the tern away, " +
                "an animation that belongs to a message that went OUT. Handler was:\n$handler",
            Regex("""nav\.popBackStack\(\s*\)""").containsMatchIn(handler) &&
                "ComposeState.Done" !in handler,
        )
    }

    /**
     * **The SAME rule for the draft the phone is keeping** (#95), and it was not held: that
     */
    @Test fun `deleting the draft the phone is keeping does not fly the tern away either`() {
        val screen = code(COMPOSE_SCREEN).lines().map { it.trim() }
        assertEquals(
            "⛔ the composer's own deletion must close on the ViewModel's one-shot, WHOLE LINE, and " +
                "with the plain-pop callback: `onDone()` there flies the tern away and lands on " +
                "the inbox, and `ComposeState.Done` (which is what this branch used to end on) " +
                "does both. Lines naming the signal were:",
            listOf("viewModel.localDraftDeleted.collect {"),
            screen.filter { "localDraftDeleted" in it },
        )
        // The collector's body, WHOLE LINES and in order. The one-liner this used to be could not
        // hold anything else; now that it has a body, the rule above only pins where it opens, so
        // what is inside is pinned here — `onDone()` or `ComposeState.Done` slipped in would be the
        // very regression this test exists for, and it would sit in these two lines.
        val opens = screen.indexOf("viewModel.localDraftDeleted.collect {")
        assertEquals(
            "⛔ …and its body must be exactly the resume slot being closed, then the plain-pop " +
                "callback. The slot holds the composer's body in cleartext under filesDir, so a " +
                "deleted draft that skips the close leaves its text on disk after the row is gone.",
            listOf("resume.close(resumeSlot)", "onCancel()", "}"),
            screen.subList(opens + 1, opens + 4),
        )
        val route = composeRoute()
        assertEquals(
            "…and the callback it closes through must still be the bare pop, on the list the " +
                "composer was opened from — the same leave the X uses. Argument was:",
            "onCancel = { entry.navigateOnce { nav.popBackStack() } }",
            argument(route, "onCancel =").replace(Regex("""\s+"""), " "),
        )
    }

    /**
     * **The BINDING, whole line — the mutation every rule in this file was blind to.**
     */
    @Test fun `the delete gesture is bound to the lease itself, and to the kept-copy verdict`() {
        val screen = code(COMPOSE_SCREEN).lines().map { it.trim() }
        assertEquals(
            "⛔ the lease binding, WHOLE LINE. Anything else of type StateFlow<String?> on the " +
                "right-hand side compiles and disarms every other rule in this file. Lines were:",
            listOf("val editingLocalDraftId by viewModel.editingLocalDraftId.collectAsStateWithLifecycle()"),
            screen.filter { it.startsWith("val editingLocalDraftId") },
        )
        assertEquals(
            "⛔ …and the verdict the confirmation warns from, WHOLE LINE: bound to another Boolean " +
                "flow the dialog would promise the server copy stays exactly where it does not, or " +
                "stay silent where it does. Lines were:",
            listOf("val deleteKeepsServerCopy by viewModel.deleteKeepsServerCopy.collectAsStateWithLifecycle()"),
            screen.filter { it.startsWith("val deleteKeepsServerCopy") },
        )
    }

    @Test fun `the button is offered by the shared decision, and asks before deleting`() {
        val screen = code(COMPOSE_SCREEN)
        val at = screen.indexOf(OFFERED)
        assertTrue(
            "ComposeScreen must gate its Delete button on $OFFERED — the decision " +
                "DraftDeleteOfferedTest runs.",
            at >= 0,
        )
        val guard = balanced(screen, at, '(', ')')
        assertEquals(
            "draftDeleteOffered must be handed the live arguments: a constant here would offer the " +
                "button on a message pulled back out of the outbox, or on a draft that could not " +
                "be read. ⛔ The fourth is the LEASE on a draft the phone is keeping (#95) — " +
                "`editingLocalDraftId != null`, and nothing else: `false` there takes the only way " +
                "out of such a draft away again (the swipe refuses it, the selection refuses it, " +
                "and emptying it by hand is unannounced), while `draftOpenRoute(draftId) == LOCAL` " +
                "offers a dead button over a row the upload worker has already consumed. " +
                "Arguments were:\n$guard",
            "restore, draftId, editingDraft != null, editingLocalDraftId != null",
            guard.replace(Regex("""\s+"""), " "),
        )
        val block = balanced(screen, screen.indexOf('{', at), '{', '}')
        assertTrue(
            "the Delete draft entry must only RAISE the confirmation (viewModel.askDraftDelete()), "
                + "having closed the overflow it lives in since #164: what " +
                "was typed since the composer opened is unsaved, the delete takes the server copy, " +
                "and the Undo behind it restores that copy — not the screen. Deleting straight " +
                "from the icon drops the editing with no question and no way back, while the same " +
                "screen asks before dropping it when the X is tapped (#127). The flag is the " +
                "ViewModel's, so that it survives the rotation that recreates MainActivity — see " +
                "[DialogFlagsSurviveRotationTest]. Block was:\n$block",
            Regex("""onClick = \{ moreMenu = false; viewModel\.askDraftDelete\(\) }""")
                .containsMatchIn(block.replace(Regex("""\s+"""), " ")),
        )
        assertTrue(
            "the icon must not delete on its own: neither the taker nor the handler belongs here " +
                "any more. Block was:\n$block",
            "takeEditingDraft" !in block && "onDeleteDraft" !in block,
        )
    }

    @Test fun `the confirmation says what this delete really does, and offers a way out`() {
        val dialog = confirmationBlock()
        val flat = dialog.replace(Regex("""\s+"""), " ")
        assertTrue(
            "the confirmation must use its OWN words (compose_delete_draft_title / _body). The " +
                "outbox's delete strings say the message 'isn't saved anywhere else' and will be " +
                "'lost', which is false here: this draft goes to the Trash, exactly as it would " +
                "from the list. Dialog was:\n$dialog",
            "R.string.compose_delete_draft_title" in flat && "R.string.compose_delete_draft_body" in flat,
        )
        assertTrue(
            "no outbox wording may be reused here. Dialog was:\n$dialog",
            "outbox_delete" !in flat,
        )
        assertTrue(
            "the confirming button must take the draft through the taker and hand it to " +
                "onDeleteDraft — one shot, refused while a send is in flight (INV-6). " +
                "Dialog was:\n$dialog",
            "onDeleteDraft { viewModel.takeEditingDraft()?.also { resume.close(resumeSlot) } }" in flat,
        )
        assertTrue(
            "there must be a Cancel that only closes the dialog: the way back to the draft. " +
                "Dialog was:\n$dialog",
            Regex("""dismissButton = \{ TextButton\(onClick = \{ viewModel\.clearDraftDelete\(\) }\)""")
                .containsMatchIn(flat),
        )
    }

    /**
     * **One title, two BODIES, and which one is drawn is pinned WHOLE-LINE** (#95).
     */
    @Test fun `the confirmation names the body that is true of the draft in hand`() {
        val text = bodySlot().let { slot -> callArguments(slot, "Text").single() }
        assertEquals(
            "⛔ the confirmation's one Text, WHOLE LINES. Both bodies must be here and chosen on " +
                "the LEASE this composer holds (`editingLocalDraftId != null`): the server body " +
                "promises the Trash and an Undo that a draft the phone is keeping has neither of, " +
                "and the local body may only state what is true of ALL THREE local cases — the " +
                "copy this phone holds, and its attachments, go at once. What is true of one case " +
                "only is the second sentence, drawn under the first when the server copy behind " +
                "the row is KEPT: without it the dialog promises the opposite of what happens. A " +
                "substring check would not see either swap, nor a " +
                "`stringResource(R.string.compose_delete_draft_body)` put back over both. Text " +
                "was:\n$text",
            listOf(
                "listOfNotNull(",
                "stringResource(",
                "if (editingLocalDraftId != null) {",
                "R.string.compose_delete_draft_body_local",
                "} else {",
                "R.string.compose_delete_draft_body",
                "},",
                "),",
                "if (deleteKeepsServerCopy) {",
                "stringResource(R.string.compose_emptied_draft_server_copy_kept)",
                "} else {",
                "null",
                "},",
                ").joinToString(\"\\n\\n\"),",
                SCROLL_MODIFIER,
            ),
            text.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    /**
     * **The confirming button's two branches, WHOLE, and they are two different gestures.**
     */
    @Test fun `the confirming button sends a kept draft to the composer's own deletion`() {
        val click = confirmOnClick()
        assertEquals(
            "⛔ the confirming button, whole and in order. `viewModel.deleteEditingLocalDraft()` on " +
                "the local branch: the server branch's taker put back there " +
                "hands the inbox a null server row — nothing is deleted, nothing is said, and the " +
                "draft is still at the top of Drafts when the screen closes. And the SERVER branch " +
                "must stay word for word what it is, taker included, since that one really does " +
                "own a cached row to move to the Trash — and it closes the composer's resume slot " +
                "on the taker's result, or the deleted draft's body stays in cleartext under " +
                "filesDir. Block was:\n" + click.joinToString("\n"),
            listOf(
                "viewModel.clearDraftDelete()",
                "if (editingLocalDraftId != null) {",
                "viewModel.deleteEditingLocalDraft()",
                "} else {",
                "onDeleteDraft { viewModel.takeEditingDraft()?.also { resume.close(resumeSlot) } }",
                "}",
            ),
            click,
        )
    }

    /**
     * The body of this confirmation must SCROLL, because Material's `text` slot is a
     */
    @Test fun `the confirmation body scrolls instead of being clipped`() {
        val slot = bodySlot()
        val texts = callArguments(slot, "Text")
        assertEquals(
            "the confirmation's body slot must draw exactly ONE Text, the one holding " +
                "R.string.compose_delete_draft_body. With a second one the scroll modifier can sit " +
                "on a sibling — Column { Text(body); Text(\"\", modifier = …verticalScroll…) } — " +
                "while the sentence itself is drawn bare and cut exactly as before, and every rule " +
                "below still counts the modifier it can see. Slot was:\n$slot",
            1,
            texts.size,
        )
        val body = texts.single()
        assertTrue(
            "the one Text of the body slot must be the one drawing " +
                "R.string.compose_delete_draft_body — this rule pins a modifier onto THAT Text, " +
                "and must fail loudly rather than pin it onto something it never located. Text " +
                "was:\n$body",
            "R.string.compose_delete_draft_body" in body,
        )
        val lines = body.lines().map { it.trim() }
        assertEquals(
            "the confirmation body must be bounded NOWHERE ELSE. A maxLines or an overflow beside " +
                "the scroll cuts the last sentence off again — at font_scale 2.0 in German the " +
                "body already ends at \"…getippt hast, wird\" — and the scroll then hides a cut " +
                "instead of preventing one. softWrap = false is worse still: the body stops " +
                "wrapping altogether, comes out as ONE line clipped at the dialog's edge " +
                "(\"Wandert in den Papierkorb ode…\") in all nine languages and at EVERY font " +
                "size, and a vertical scroll reaches nothing that overflowed sideways. Text " +
                "was:\n$body",
            emptyList<String>(),
            lines.filter { BOUNDS.any { bound -> bound in it } },
        )
        assertEquals(
            "the confirmation body must carry exactly '$SCROLL_MODIFIER' on its own line. Without " +
                "it, Material's bounded text slot cuts the German body at font_scale 2.0 mid-word " +
                "(measured: \"…getippt hast, wird\", Moto G, 720×1280) with no ellipsis and no " +
                "hint on screen — and what is lost is \"nicht gespeichert.\", the sentence warning " +
                "that everything typed since the composer opened is not saved. The reader then " +
                "confirms the delete believing her keystrokes are kept (#127). Text was:\n$body",
            1,
            lines.count { it == SCROLL_MODIFIER },
        )
    }

    @Test fun `the draft is taken inside the navigation guard, not before it`() {
        // Ordering, and it is the reader's precedent: the mutating step belongs INSIDE
        // navigateOnce. takeEditingDraft() hands the row over exactly once, so consuming it
        // outside meant a tap the guard drops — two fingers, the X and the trash in one frame —
        // had thrown the draft away while deleting nothing.
        val handler = argument(composeRoute(), HANDLER).replace(Regex("""\s+"""), " ")
        val guard = handler.indexOf("entry.navigateOnce {")
        val take = handler.indexOf("take()")
        assertTrue("onDeleteDraft must still go through entry.navigateOnce. Handler was:\n$handler", guard >= 0)
        assertTrue("onDeleteDraft must call the taker it is handed. Handler was:\n$handler", take >= 0)
        assertTrue(
            "the taker must be called INSIDE entry.navigateOnce { … }. Handler was:\n$handler",
            guard < take,
        )
    }

    @Test fun `the delete button does not live inside the encryption-only block`() {
        // draftSaveAllowed hides the save and schedule icons while encrypting, because both would
        // put plaintext on the server. A delete persists nothing, so it has no business being
        // hidden with them — and the draft it removes is exactly as deletable encrypted or not.
        val screen = code(COMPOSE_SCREEN)
        val guardAt = screen.indexOf(SAVE_GUARD)
        check(guardAt >= 0) { "ComposeScreen no longer contains '$SAVE_GUARD'" }
        val saveBlock = balanced(screen, screen.indexOf('{', guardAt), '{', '}')
        assertTrue(
            "draftDeleteOffered must not appear inside the 'if (draftSaveAllowed(pgpMode))' block: " +
                "the Delete button would then vanish when the padlock closes, for no reason.",
            OFFERED !in saveBlock,
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** The body of the `composable(route = "compose?…")` destination, braces balanced. */
    private fun composeRoute(): String {
        val code = code(STERNA_APP)
        val at = code.indexOf(COMPOSE_ROUTE)
        check(at >= 0) { "SternaApp.kt no longer declares a route starting '$COMPOSE_ROUTE'" }
        // From the route declaration to the end of the `composable(...) { … }` trailing lambda.
        val brace = code.indexOf("{ entry ->", at)
        check(brace >= 0) { "the compose destination no longer opens with '{ entry ->'" }
        return balanced(code, brace, '{', '}')
    }

    /** The `if (pendingDraftDelete) { … }` block of `ComposeScreen.kt`, braces balanced. */
    private fun confirmationBlock(): String {
        val code = code(COMPOSE_SCREEN)
        val at = code.indexOf(CONFIRMATION)
        check(at >= 0) {
            "ComposeScreen.kt no longer contains '$CONFIRMATION' — the trash icon must ask before " +
                "it deletes (#127)"
        }
        return balanced(code, code.indexOf('{', at), '{', '}')
    }

    /**
     * The `text = { … }` slot of the confirmation dialog, braces balanced.
     */
    private fun bodySlot(): String {
        val dialog = confirmationBlock()
        val found = SLOT.findAll(dialog).toList()
        check(found.size == 1) {
            "the confirmation must declare exactly one 'text = {' slot, found ${found.size} — this " +
                "rule reads that slot and has nothing to read if it moved or was renamed. Dialog " +
                "was:\n$dialog"
        }
        val slot = balanced(dialog, found.single().range.last, '{', '}')
        check("confirmButton" !in slot) {
            "the confirmation's 'text = {' slot does not close before the next slot — the braces " +
                "did not balance and this rule is reading the rest of the dialog. Read:\n$slot"
        }
        return slot
    }

    /**
     * The `onClick = { … }` block of the confirmation's `confirmButton` slot, braces balanced, as
     * trimmed non-empty lines — comments already cut by [code].
     */
    private fun confirmOnClick(): List<String> {
        val dialog = confirmationBlock()
        val slotAt = dialog.indexOf("confirmButton = {")
        check(slotAt >= 0) {
            "the confirmation no longer declares a 'confirmButton = {' slot — this rule reads it " +
                "and has nothing to read if it moved. Dialog was:\n$dialog"
        }
        val slot = balanced(dialog, slotAt, '{', '}')
        val clickAt = slot.indexOf("onClick = {")
        check(clickAt >= 0) {
            "the confirming button no longer carries an 'onClick = {' block. Slot was:\n$slot"
        }
        return balanced(slot, clickAt, '{', '}').lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The argument text of every `name(…)` call in [text], parentheses balanced. */
    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last, '(', ')') }
            .toList()

    /** The `name = …` argument of [text], up to the comma that closes it (brackets balanced). */
    private fun argument(text: String, name: String): String {
        val at = text.indexOf(name)
        check(at >= 0) { "no '$name' argument found" }
        var i = at + name.length
        var depth = 0
        while (i < text.length) {
            when (text[i]) {
                '(', '{' -> depth++
                ')', '}' -> depth--
                ',' -> if (depth == 0) return text.substring(at, i)
            }
            i++
        }
        return text.substring(at)
    }

    /** [text] from the first [open] at or after [from], up to the [close] that balances it. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    /** [file]'s code as one string, comments cut — load-bearing: the comments beside both call
     *  sites name `discardDraft`, `ComposeState.Done` and `popBackStack("inbox", …)` on purpose. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
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

    companion object {
        private const val COMPOSE_ROUTE = "route = \"compose?"
        private const val HANDLER = "onDeleteDraft ="
        private const val OFFERED = "draftDeleteOffered("
        private const val CONFIRMATION = "if (pendingDraftDelete) {"
        private const val SAVE_GUARD = "if (draftSaveAllowed(pgpMode))"

        /**
         * Material's body slot of the confirmation — the height-bounded box that clips. The
         * lookbehind is the whole point: without it this also matches inside `context = `.
         */
        private val SLOT = Regex("""(?<![\w.])text = \{""")

        /** The whole line, as this repo's other source lints pin theirs. */
        private const val SCROLL_MODIFIER =
            "modifier = Modifier.verticalScroll(rememberScrollState()),"

        /** Everything that can bound a `Text` back into a cut, scroll or no scroll. */
        private val BOUNDS = listOf("maxLines", "overflow", "softWrap")

        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val COMPOSE_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/compose/ComposeScreen.kt"
        private const val STERNA_APP_PATH = "$APP_SOURCES/app/sterna/ui/SternaApp.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_SCREEN: File by lazy { File(root, COMPOSE_SCREEN_PATH) }
        private val STERNA_APP: File by lazy { File(root, STERNA_APP_PATH) }
    }
}
