package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class ComposerBodyLeavesTheParcelTest {

    // -- the body and its baseline no longer travel in the parcel --------------------------------

    @Test
    fun `the body is held in the resume state, not in a saveable`() {
        assertPinned(
            SCREEN, "var body by resume.bodyState",
            "the body must be delegated to the resume state, whose saver puts a short TOKEN in the " +
                "parcel and the text in an app-private file. Put back as a `rememberSaveable`, a " +
                "long reply kills the activity on HOME and the user loses everything they typed.",
        )
    }

    @Test
    fun `the baseline is held in the resume state, not in a saveable`() {
        assertPinned(
            SCREEN, "var initialBody by resume.baselineState",
            "the baseline is a SECOND full copy of the body in the parcel, and it comes back as " +
                "TEXT because `insertSignatureBlock(quoted = initialBody)` and `rewrite(initialBody)` " +
                "are handed it verbatim — a digest would break the signature rules instead.",
        )
    }

    /**
     * THE MUTATION THAT SURVIVED THE FIRST ROUND. Turned into a plain `remember`, this line
     */
    @Test
    fun `the resume state is parcelled through its own saver`() {
        assertPinned(
            SCREEN, "val resume = rememberSaveable(saver = composerResumeSaver(resumeSlot)) {",
            "`rememberSaveable(saver = …)` is what puts the token in the parcel and reads it back. " +
                "A plain `remember` compiles, passes every other rule here, and silently drops the " +
                "user's text on every process death.",
        )
    }

    /**
     * ONE FILE PER NAVIGATION ENTRY, and two entries ARE reachable: `MainActivity` is singleTask,
     */
    @Test
    fun `each composer entry gets a slot of its own`() {
        assertPinned(
            SCREEN, "val slotId = rememberSaveable { newComposerSlotId() }",
            "the id must be `rememberSaveable` — a few bytes of parcel — or the composer coming " +
                "back from a process death would look for a file it can no longer name.",
        )
        assertPinned(
            SCREEN, "val resumeSlot = remember(context, slotId) { ComposerResumeSlot(composerResumeFile(context, slotId)) }",
            "and the slot must be keyed on that id, so each composer erases only its own file.",
        )
    }

    /** Where the cleartext lives, pinned: app-private storage, through the application context. */
    @Test
    fun `the slot lives in app-private storage`() {
        assertPinned(
            SLOT, "composerResumeSlotFile(context.applicationContext.filesDir, slotId)",
            "`filesDir` is app-private. Moved to `externalCacheDir` or any other shared location, " +
                "the body of every draft — including one being ENCRYPTED — becomes readable by " +
                "anything on the device. `applicationContext`, not the activity's, so a remembered " +
                "slot cannot hold a destroyed activity alive.",
        )
    }

    @Test
    fun `no line of the screen puts the body or its baseline back into the parcel`() {
        val offenders = codeLines(SCREEN).filter { line ->
            SAVEABLE.containsMatchIn(line) && (BODY.containsMatchIn(line) || BASELINE.containsMatchIn(line))
        }
        assertEquals(
            "no line of $SCREEN may name `rememberSaveable` together with `body` or `initialBody`: " +
                "that is the parcel the 324 kB reply overflows, and it kills the activity on HOME " +
                "with the draft in it. Found:\n" + offenders.joinToString("\n") { "    $it" },
            emptyList<String>(), offenders,
        )
    }

    // -- and when the body could NOT be parked, the loss is carried to the destroy routes --------

    /**
     * THE HALF NO OTHER RULE IN THIS FILE COVERS. `no line of the screen puts the body or its
     */
    @Test
    fun `a body lost on the way into the parcel is pushed to the view model`() {
        assertPinned(
            SCREEN,
            WIRING,
            "the flag must reach the view model as an EFFECT, keyed on itself, right after the " +
                "resume state is restored — not as an argument handed to `saveDraft`/`send` at the " +
                "moment of the tap. There are four routes that destroy (the interactive save, the " +
                "deferred upload, the discard, the send) and they all read ONE field; a per-call " +
                "argument would have to be threaded through every one of them and the one that was " +
                "forgotten is the data loss. ⛔ `if (…)` included: an unconditional call would raise " +
                "the flag on every composer that ever opens and freeze the verdict at `lossy`, " +
                "which stops every legitimate draft replacement instead.",
        )
        // AND ITS POSITION, because `assertPinned` counts lines and cannot see where they sit.
        // The line above is valid Kotlin anywhere in this composable: moved inside the
        val lines = codeLines(SCREEN)
        val built = lines.indexOf(RESUME_STATE)
        assertTrue("$SCREEN no longer builds the resume state as '$RESUME_STATE'", built >= 0)
        assertEquals(
            "these FOUR code lines of $SCREEN, whole and adjacent, in this order: the resume " +
                "state's saveable, its factory, the brace that closes it, and the effect that " +
                "carries a lost body to the view model. The effect belongs at the composable's " +
                "TOP LEVEL and immediately after the state it reads — anywhere inside a branch " +
                "it runs only when that branch is composed, and the path this exists for is " +
                "precisely the one where the editor is drawn from a parcel nobody re-read.",
            listOf(
                RESUME_STATE,
                "ComposerResumeState(TextFieldValue(), \"\")",
                "}",
                WIRING,
            ),
            lines.subList(built, (built + 4).coerceAtMost(lines.size)),
        )
    }

    /**
     * The verdict must read BOTH terms. Either one alone is a destroy: `editingDraftLossyOnOpen`
     */
    @Test
    fun `the destroy verdict reads what the draft held AND what the composer lost`() {
        assertPinned(
            VIEW_MODEL,
            "private val editingDraftLossy: Boolean get() = editingDraftLossyOnOpen || composerBodyWasLost",
            "the name `editingDraftLossy` must stay the one the eight read sites use — that is the " +
                "whole point of the montage: the four destroy routes (finishDraftSave, discardDraft, " +
                "performSend, destroyReplacedServerDraft) are covered without one of them being " +
                "touched, and the deferred upload comes free because `saveDraft(bodyIsLossy = …)` " +
                "already writes the `local_drafts` column. Turned back into a `var`, `prepare()`'s " +
                "last statement would overwrite the lost-body flag every time a draft is reopened.",
        )
    }

    /**
     * RAISED ONCE, NEVER LOWERED, and pinned as a CLOSED list because that is the only shape a
     */
    @Test
    fun `the lost-body flag is only ever raised`() {
        val assignments = codeLines(VIEW_MODEL).filter { LOST_ASSIGNED.containsMatchIn(it) }
        assertEquals(
            "every line of $VIEW_MODEL that ASSIGNS `composerBodyWasLost`, whole lines and in " +
                "order: its declaration, the single line that raises it, and the named ARGUMENT " +
                "that hands it to the save (which this rule also pins by value — " +
                "`composerBodyWasLost = false` there puts the silent refusal of an erasure back). " +
                "Nothing else, ever. " +
                "`prepare()` starts from a `LaunchedEffect(Unit)` and its last statement writes the " +
                "reopen verdict, so a flag living in that same field would be wiped by a rotation " +
                "that reopens a draft — which is why there are two fields and why this one may only " +
                "go up. Found:\n" + assignments.joinToString("\n") { "    $it" },
            listOf(
                "private var composerBodyWasLost = false",
                "composerBodyWasLost = true",
                "composerBodyWasLost = composerBodyWasLost,",
            ),
            assignments,
        )
        // AND THE WHOLE BODY OF THE RAISER, because the list above only sees lines that ASSIGN.
        // `if (_draftLoading.value) return` added on a line of its own leaves that list untouched
        val lines = codeLines(VIEW_MODEL)
        val raiser = lines.indexOf(RAISER)
        assertTrue("$VIEW_MODEL no longer declares '$RAISER'", raiser >= 0)
        assertEquals(
            "the raiser's body, whole and adjacent: the assignment, and the brace. Nothing may " +
                "stand between them — no guard, no early return, no condition. Nothing in this " +
                "module can EXECUTE this class (no Robolectric), so a line added around the " +
                "assignment is seen by nobody at all.",
            listOf(RAISER, "composerBodyWasLost = true", "}"),
            lines.subList(raiser, (raiser + 3).coerceAtMost(lines.size)),
        )
    }

    /**
     * …and the OTHER half of the verdict is a closed list too. `editingDraftLossyOnOpen` is the
     */
    @Test
    fun `the reopen verdict is written in five places and nowhere else`() {
        val assignments = codeLines(VIEW_MODEL).filter { ON_OPEN_ASSIGNED.containsMatchIn(it) }
        assertEquals(
            "every line of $VIEW_MODEL that ASSIGNS `editingDraftLossyOnOpen`: its declaration, " +
                "then the five prepare()/localDraftPrefill writes. A sixth one anywhere lowers " +
                "the licence to destroy outside the two places that have earned the right to, " +
                "and `draftReplacementIsFaithful` is `!bodyIsLossy && …` — a clean verdict is an " +
                "expunged original. Found:\n" + assignments.joinToString("\n") { "    $it" },
            listOf(
                "private var editingDraftLossyOnOpen = false",
                "editingDraftLossyOnOpen = d.draftBodyIsLossy",
                "editingDraftLossyOnOpen = true",
                "editingDraftLossyOnOpen = lossy || !carried",
                "editingDraftLossyOnOpen = true",
                "editingDraftLossyOnOpen = fields.bodyIsLossy",
            ),
            assignments,
        )
    }

    // -- and the file goes away when the composer is done with it --------------------------------

    @Test
    fun `all four ways out of the composer close the slot`() {
        assertPinned(
            SCREEN, "resume.close(resumeSlot)",
            "three of the composer's FOUR exits close the slot on a line of their own — the Done " +
                "state (send, save-as-draft, schedule), the deleted local draft, and `cancel` " +
                "(abandon, and a plain back with nothing changed). Without the close, the body " +
                "stays in cleartext under `filesDir` after the message is gone, including one that " +
                "was being ENCRYPTED.",
            times = 3,
        )
    }

    /**
     * THE FOURTH EXIT, and it was missed. The trash on a SERVER draft — `editingLocalDraftId`
     */
    @Test
    fun `deleting a server draft closes the slot too`() {
        assertPinned(
            SCREEN, "onDeleteDraft { viewModel.takeEditingDraft()?.also { resume.close(resumeSlot) } }",
            "the close belongs on the taker's result: outside it, a tap the navigation guard drops " +
                "would erase the draft's text while deleting nothing at all; missing altogether, " +
                "the deleted draft's body stays in cleartext under `filesDir`.",
        )
    }

    @Test
    fun `a launch with no saved state sweeps an orphaned slot`() {
        assertPinned(
            ACTIVITY, SWEEP,
            "with no saved state there is no `rememberSaveable` left to consume ANY slot, so every " +
                "file still there is the orphan of a crash — this is the only moment that can be " +
                "said without guessing. It sweeps them ALL, which is safer and not less so: there " +
                "is one file per composer entry now, and a sweep of a single name would leave the " +
                "others' cleartext under `filesDir` forever.",
        )
        val lines = codeLines(ACTIVITY)
        val opener = lines.indexOf("if (savedInstanceState == null) {")
        val sweep = lines.indexOf(SWEEP)
        val after = lines.indexOf("val settings = application.container.settingsRepository")
        assertTrue(
            "in $ACTIVITY the sweep (line index $sweep) must sit INSIDE the " +
                "`if (savedInstanceState == null) {` block (opens at $opener, and the block is over " +
                "by $after). Swept on every recreation — a rotation, a theme flip, a language " +
                "change — it would delete the very file the recomposition is about to read, and the " +
                "draft would be lost by the fix meant to save it.",
            opener in 0 until sweep && sweep < after,
        )
    }

    // -- nothing is said to the user, so nothing is translated ------------------------------------

    /**
     * The property is LOCAL to this change, not a freeze of `strings.xml`: a count pinned there
     */
    @Test
    fun `the resume slot says nothing to the user`() {
        val speaks = codeLines(SLOT).filter { "R.string" in it || "stringResource" in it }
        assertEquals(
            "$SLOT must name no string resource at all. This change is invisible on purpose: no " +
                "\"draft recovered\" banner, no resume error. One new string reopens a bench pass in " +
                "nine languages for a mechanism the user is not supposed to notice. Found:\n" +
                speaks.joinToString("\n") { "    $it" },
            emptyList<String>(), speaks,
        )
    }

    // -- reading the sources ---------------------------------------------------------------------

    /** Asserts exactly [times] code lines of [path], trimmed, ARE [pinned] — whole line, never a fragment. */
    private fun assertPinned(path: String, pinned: String, why: String, times: Int = 1) {
        val hits = codeLines(path).count { it == pinned }
        assertEquals(
            "$why\nExpected exactly $times line(s) of $path whose trimmed text is:\n    $pinned" +
                "\nbut found $hits. The line was rewritten, removed, split or duplicated — this " +
                "lint compares the WHOLE line, so a change that only LENGTHENS it lands here too.",
            times, hits,
        )
    }

    /** [path]'s code as trimmed whole lines, comments cut: prose must never satisfy a rule. */
    private fun codeLines(path: String): List<String> = source(path).lines().mapNotNull { line ->
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

    private fun source(path: String): String {
        val file = File(root, path)
        assertTrue("$path is not a file under ${root.absolutePath}", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        const val ACTIVITY = "app/src/main/kotlin/app/sterna/MainActivity.kt"
        const val SLOT = "app/src/main/kotlin/app/sterna/ui/compose/ComposerResumeSlot.kt"
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        const val SWEEP = "ComposerResumeSlot.sweep(filesDir)"

        val SAVEABLE = Regex("""\brememberSaveable\b""")
        val BODY = Regex("""\bbody\b""")
        val BASELINE = Regex("""\binitialBody\b""")

        const val RESUME_STATE = "val resume = rememberSaveable(saver = composerResumeSaver(resumeSlot)) {"
        const val WIRING =
            "LaunchedEffect(resume.bodyWasLost) { if (resume.bodyWasLost) viewModel.onComposerBodyLost() }"
        const val RAISER = "fun onComposerBodyLost() {"

        /** An assignment TO the lost-body flag — `==` is a read and must not be caught here. */
        val LOST_ASSIGNED = Regex("""\bcomposerBodyWasLost\s*=(?!=)""")

        /** …and to the reopen verdict beside it, same shape, same reason. */
        val ON_OPEN_ASSIGNED = Regex("""\beditingDraftLossyOnOpen\s*=(?!=)""")

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SCREEN).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }
    }
}
