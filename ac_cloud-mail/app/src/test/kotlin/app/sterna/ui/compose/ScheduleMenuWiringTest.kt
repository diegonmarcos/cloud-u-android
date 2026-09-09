package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads a source file as text, same instrument and
 */
class ScheduleMenuWiringTest {

    @Test fun `the tap decides again instead of scheduling what composition captured`() {
        assertTrue(
            "the preset tap must take the decision again, at the tap: '$DECISION'. Without it the " +
                "instant drawn when the menu opened is what gets scheduled, and once it has lapsed " +
                "enqueue coerces the delay to zero — the message is sent, irreversibly. Both the " +
                "drawn instant and the fresh clock are arguments: dropping either one loses a case " +
                "PresetMillisAtTapTest pins.",
            DECISION in lines(COMPOSE_SCREEN),
        )
    }

    @Test fun `nothing stands between the tap and the decision`() {
        val all = lines(COMPOSE_SCREEN)
        val list = all.indexOf(LIST_FROM_STAMP)
        assertTrue("the preset loop is missing entirely — see the drawing test below", list >= 0)
        val relative = all.drop(list).indexOf(TAP_HEAD)
        assertTrue("the preset item's '$TAP_HEAD' is missing", relative >= 0)
        assertEquals(
            "the FIRST line inside the preset item's onClick must be '$DECISION'. Anything run " +
                "before it is outside every rule below — `scheduleMenu = false` slipped in here " +
                "closes the menu on EVERY tap, refusals included: nothing is scheduled, nothing is " +
                "said, and the user walks away believing the message is queued.",
            DECISION,
            all.getOrNull(list + relative + 1),
        )
    }

    @Test fun `the refusal guard sits immediately on the decision`() {
        val all = lines(COMPOSE_SCREEN)
        val decision = all.indexOf(DECISION)
        assertTrue("the decision is missing entirely — see the test above", decision >= 0)
        assertEquals(
            "the line after '$DECISION' must be '$GUARD'. An instant that is decided and then used " +
                "unconditionally is the same defect with an extra line: null means the entry has " +
                "lapsed, and nothing below may run.",
            GUARD,
            all[decision + 1],
        )
    }

    @Test fun `closing, scheduling and the banner all live inside the guard`() {
        assertEquals(
            "everything between '$GUARD' and '$ELSE' must be exactly the schedule path, in this " +
                "order. Moving the menu close out takes the choice off screen having scheduled " +
                "nothing. ⛔ And the banner is INSIDE the schedule's own answer: `scheduleSend` " +
                "returns false on every refusal it owns — the PGP/attachment choke point, and a " +
                "body this composer LOST — so the confirmation and the schedule arrive together " +
                "or not at all. Announced unconditionally it reads \"Scheduled — Mon 1 Sep, " +
                "09:00\" over a message that was refused, and the refusal's own notice " +
                "contradicts it one toast later.",
            listOf(
                "scheduleMenu = false",
                "if (viewModel.scheduleSend(to, cc, bcc, subject.text, rich, sendAt, requestReceipt)) {",
                "Toast.makeText(",
                "context,",
                "context.getString(R.string.compose_scheduled_toast, label),",
                "Toast.LENGTH_SHORT,",
                ").show()",
                "}",
            ),
            guardedBlock(),
        )
    }

    /**
     * The other confirmation, on the free picker three hundred lines above — the same rule, and it
     * has no `else` branch to bound it, so it is read as the lines that FOLLOW the schedule.
     */
    @Test fun `the hand-picked confirmation lives inside the same answer`() {
        val all = lines(COMPOSE_SCREEN)
        val at = all.indexOf(PICKER_SCHEDULE)
        assertTrue(
            "the hand-picked schedule must read '$PICKER_SCHEDULE' — the confirmation belongs to " +
                "the schedule's own answer, not to the tap.",
            at >= 0,
        )
        assertEquals(
            "everything under '$PICKER_SCHEDULE' must be exactly the confirmation, then the brace " +
                "that closes it. Outside that block, \"Scheduled — <date>\" is announced over a " +
                "send `scheduleSend` refused (PGP, attachments, or a body this composer LOST) and " +
                "the notice saying why arrives right after it.",
            listOf(
                "val shown = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)",
                "Toast.makeText(",
                "context,",
                "context.getString(R.string.compose_scheduled_toast, shown),",
                "Toast.LENGTH_SHORT,",
                ").show()",
                "}",
            ),
            all.drop(at + 1).take(7),
        )
    }

    @Test fun `the refused tap re-stamps the menu instead of closing it`() {
        assertEquals(
            "the else branch must be exactly 'menuOpenedAt = System.currentTimeMillis()'. The wall " +
                "clock is not snapshot state, so a refused tap recomposes nothing on its own: " +
                "without the re-stamp the lapsed entry stays on screen, tappable and silent. Its " +
                "disappearance IS the message — deliberately no new string, no nine translations " +
                "for a window a few seconds wide. And closing the menu here would hide the choice.",
            listOf("menuOpenedAt = System.currentTimeMillis()"),
            elseBlock(),
        )
    }

    @Test fun `the menu is drawn from the stamp taken when it opened`() {
        val all = lines(COMPOSE_SCREEN)
        assertTrue(
            "the composer must hold the instant the list was drawn for: '$STAMP_STATE'.",
            STAMP_STATE in all,
        )
        assertTrue(
            "opening the menu must stamp that instant: '$STAMP_ON_OPEN'. Left at 0 the list is " +
                "built for 1 Jan 1970, where all four entries are offered and none ever lapses: " +
                "the menu never changes, and at the tap the three absolute entries are refused " +
                "forever (their drawn instant is in 1970) — taps that do nothing and say nothing.",
            STAMP_ON_OPEN in all,
        )
        assertTrue(
            "the list must be built from the stamp and carry BOTH the preset and the instant it " +
                "was drawn for out to the tap: '$LIST_FROM_STAMP'. Dropping the third field (`_`) " +
                "leaves the tap no choice but to re-compute the absolute entries, which moves them " +
                "a whole day across midnight.",
            LIST_FROM_STAMP in all,
        )
    }

    @Test fun `the label table pairs each preset with its own string`() {
        // Nothing else pins this table: `schedulePresets` is private and needs a Context, so no JVM
        // test executes it. Permute two branches, or hard-wire the Triple's first field, and the
        // menu offers "Tomorrow, 8 AM" while the tap schedules an hour from now — the label and the
        // instant would disagree, and the confirmation repeats the LABEL.
        assertEquals(
            "schedulePresets' label table and its Triple must be exactly these lines, in order",
            LABEL_TABLE,
            presetsBody().dropWhile { it != LABEL_TABLE.first() },
        )
    }

    @Test fun `the list is built for the instant handed in, not for another one`() {
        assertEquals(
            "schedulePresets' first line must be '$PRESETS_CALL'. Handed anything else — 0L, a " +
                "literal — the list is drawn for that instant instead of for the menu's stamp: at " +
                "the epoch all four entries are offered forever, the lapsed one never disappears, " +
                "and re-stamping after a refusal redraws exactly the same menu.",
            PRESETS_CALL,
            presetsBody().firstOrNull(),
        )
    }

    @Test fun `schedulePresets takes the clock and no longer reads it`() {
        val body = presetsBody()
        assertEquals(
            "schedulePresets must not read the clock in its own body; found: " +
                "${body.filter { CLOCK in it }}. Reading it there ties the drawn list to " +
                "recomposition, which a tap does not cause — the lapsed entry would never go away.",
            emptyList<String>(),
            body.filter { CLOCK in it },
        )
    }

    @Test fun `no third schedule call hides in the composer`() {
        // Exactly the two production call sites, each fed by its own decision taken at the tap: the
        // free picker's `millis` (pickedScheduleMillis, asked again inside the tap) and the preset
        // menu's `sendAt`. A third one added with a captured instant would reopen the defect while
        // every line pinned above stays present.
        val calls = lines(COMPOSE_SCREEN).filter { "viewModel.scheduleSend(" in it }
        assertEquals(
            "ComposeScreen must schedule from exactly two places, both deciding at the tap, and " +
                "both reading the answer: a call whose Boolean is dropped confirms a schedule that " +
                "may not have happened. Found: $calls",
            listOf(
                PICKER_SCHEDULE,
                "if (viewModel.scheduleSend(to, cc, bcc, subject.text, rich, sendAt, requestReceipt)) {",
            ).sorted(),
            calls.sorted(),
        )
    }

    // -- locating the pieces ---------------------------------------------------------------------

    /** `schedulePresets`' body: it is the last declaration in the file, so it runs to the end. */
    private fun presetsBody(): List<String> {
        val all = lines(COMPOSE_SCREEN)
        val signature = all.indexOf(PRESETS_SIGNATURE)
        assertTrue("the label resolver must take the instant: '$PRESETS_SIGNATURE'", signature >= 0)
        return all.drop(signature + 1)
    }

    /** The code lines strictly between [GUARD] and [ELSE], i.e. the path that schedules. */
    private fun guardedBlock(): List<String> = tapBranches().first

    /** The code lines strictly between [ELSE] and the brace that closes it. */
    private fun elseBlock(): List<String> = tapBranches().second

    private fun tapBranches(): Pair<List<String>, List<String>> {
        val all = lines(COMPOSE_SCREEN)
        val decision = all.indexOf(DECISION)
        if (decision < 0 || all.getOrNull(decision + 1) != GUARD) {
            // Reported by the two tests above; here the branches are simply empty, so the equality
            // fails on content rather than on an index out of bounds.
            return emptyList<String>() to emptyList()
        }
        val rest = all.drop(decision + 2)
        val elseAt = rest.indexOf(ELSE)
        if (elseAt < 0) return rest.takeWhile { it != "}" } to emptyList()
        val after = rest.drop(elseAt + 1)
        return rest.take(elseAt) to after.takeWhile { it != "}" }
    }

    // -- reading the sources ---------------------------------------------------------------------

    /** [file]'s code as trimmed WHOLE lines, comments cut. Rules compare with equality, never
     *  contains: a mutation that lengthens a line must change the line. */
    private fun lines(file: File): List<String> = code(file).lines().map { it.trim() }

    /** [file]'s code as one string, comments cut — same reader as [OutgoingDateWiringTest]. */
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
        private const val CLOCK = "System.currentTimeMillis()"
        private const val TAP_HEAD = "onClick = {"
        private const val DECISION =
            "val sendAt = presetMillisAtTap(preset, drawnAt, System.currentTimeMillis(), java.time.ZoneId.systemDefault())"
        private const val GUARD = "if (sendAt != null) {"

        /** The free picker's own call, WHOLE: the confirmation hangs off the answer, or off nothing. */
        private const val PICKER_SCHEDULE =
            "if (viewModel.scheduleSend(to, cc, bcc, subject.text, rich, millis, requestReceipt)) {"
        private const val ELSE = "} else {"
        private const val STAMP_STATE = "var menuOpenedAt by remember { mutableLongStateOf(0L) }"
        private const val STAMP_ON_OPEN =
            "onClick = { moreMenu = false; menuOpenedAt = System.currentTimeMillis(); " +
                "scheduleMenu = true },"
        private const val LIST_FROM_STAMP =
            "schedulePresets(context, menuOpenedAt).forEach { (preset, label, drawnAt) ->"
        private const val PRESETS_SIGNATURE =
            "private fun schedulePresets(context: android.content.Context, nowMillis: Long): " +
                "List<Triple<SchedulePreset, String, Long>> ="
        private const val PRESETS_CALL = "schedulePresetsAt(nowMillis, java.time.ZoneId.systemDefault())"

        /** From the `when` that resolves the labels to the end of the file, whole lines. */
        private val LABEL_TABLE = listOf(
            "val label = when (preset) {",
            "SchedulePreset.IN_1_HOUR -> R.string.schedule_in_1_hour",
            "SchedulePreset.THIS_EVENING -> R.string.schedule_this_evening",
            "SchedulePreset.TOMORROW_MORNING -> R.string.schedule_tomorrow_morning",
            "SchedulePreset.TOMORROW_EVENING -> R.string.schedule_tomorrow_evening",
            "}",
            "Triple(preset, context.getString(label), millis)",
            "}",
        )

        private const val COMPOSE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_SCREEN: File by lazy { File(root, COMPOSE_SCREEN_PATH) }
    }
}
