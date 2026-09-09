package app.sterna.ui.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.Span
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A BEHAVIOUR test, not a lint: it builds a real [ComposerResumeSlot] on a real file and EXECUTES
 * the two decisions the composer now depends on, [saveComposerResume] and [restoreComposerResume].
 *
 * What it stands for. Replying to a ~324 kB message and pressing HOME killed the activity on a
 * `TransactionTooLargeException` (1 340 240 byte parcel) and the draft vanished in silence, because
 * the body travelled in the saved-state parcel twice over. It now travels through this slot, and
 * this file is what says the text, its caret and its baseline come back BYTE FOR BYTE — a resume
 * that quietly truncates or re-orders is the same lost draft with extra steps.
 */
class ComposerResumeSlotTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var slot: ComposerResumeSlot

    @Before
    fun setUp() {
        file = File(folder.newFolder("files"), "composer-resume")
        slot = ComposerResumeSlot(file)
    }

    // -- the round trip that closes the defect ---------------------------------------------------

    /** 400 000 characters, the size that could not fit in the parcel, out and back unchanged. */
    @Test
    fun `a very long body comes back whole, with its caret`() {
        val body = longBody(400_000)
        val baseline = "> quoted original, the baseline the leave guard compares against"
        val state = ComposerResumeState(TextFieldValue(body, TextRange(123_456, 123_456)), baseline)

        val token = saveComposerResume(state, slot)
        assertTrue("a 400 000 character body must be parked, not refused", token != null)

        val back = restoreComposerResume(token!!, slot)
        assertTrue("the parked composer must come back", back != null)
        val restored = back!!.bodyState.value
        assertEquals("the body lost or gained characters", body.length, restored.text.length)
        assertEquals("the body's opening was rewritten", body.take(200), restored.text.take(200))
        assertEquals("the body's TAIL was cut — the parcel defect, moved to disk",
            body.takeLast(200), restored.text.takeLast(200))
        assertEquals("the whole body must be identical", body, restored.text)
        assertEquals("the caret came back somewhere else", TextRange(123_456, 123_456), restored.selection)
        assertEquals("the baseline is handed back as TEXT, never a digest — `insertSignatureBlock` " +
            "and `rewrite` are given it verbatim", baseline, back.baselineState.value)
    }

    /**
     * The four arguments [saveComposerResume] hands to [ComposerResumeSlot.write], pinned by making
     * every one of them different: a body and a baseline that cannot be confused, and a selection
     * whose start and end differ so a swapped pair cannot pass.
     */
    @Test
    fun `body, baseline and both ends of the selection each go to their own place`() {
        val body = "BODY-".repeat(2_000)
        val baseline = "BASELINE-".repeat(3)
        val state = ComposerResumeState(TextFieldValue(body, TextRange(17, 4_242)), baseline)

        val saved = saveComposerResume(state, slot)!!
        assertTrue(
            "a composer whose file WAS written must be parcelled as a token, not inline: `$saved`",
            saved.startsWith("t:"),
        )
        val resume = slot.read(saved.removePrefix("t:"))!!

        assertEquals("the body went to the baseline's place", body, resume.body)
        assertEquals("the baseline went to the body's place", baseline, resume.baseline)
        assertEquals("the selection START was taken from somewhere else", 17, resume.selectionStart)
        assertEquals("the selection END was taken from somewhere else", 4_242, resume.selectionEnd)
    }

    /** A body carrying every kind of character a mail actually contains, counting included. */
    @Test
    fun `newlines, CRLF, accents, cyrillic and an emoji all survive the count`() {
        val body = buildString {
            append("Bonjour Amélie,\n\nvoici la pièce jointe.\r\n")
            append("Приветствую вас, коллеги.\r\n")
            append("Ça va ? — oui. 😀🎉\n")
            append("x".repeat(50_000))
            append("\n😀")
        }
        val token = park(slot, body, 3, 9, "→ ligne de base 😀")
        val resume = slot.read(token)!!

        assertEquals("a surrogate pair was miscounted and the split moved", body, resume.body)
        assertEquals(body.length, resume.body.length)
        assertEquals("→ ligne de base 😀", resume.baseline)
        assertEquals(3, resume.selectionStart)
        assertEquals(9, resume.selectionEnd)
    }

    /** An untouched composer: empty is a value, not "nothing to save". */
    @Test
    fun `an empty body and an empty baseline round trip`() {
        val token = park(slot, "", 0, 0, "")
        val resume = slot.read(token)!!

        assertEquals("", resume.body)
        assertEquals("", resume.baseline)
        assertEquals(0, resume.selectionStart)
        assertEquals(0, resume.selectionEnd)
    }

    // -- refusing what is not ours ---------------------------------------------------------------

    /** The token is the proof that parcel and file describe the SAME saved composer. */
    @Test
    fun `a wrong token reads nothing and does not consume the file`() {
        val token = park(slot, "the real draft", 1, 1, "base")

        assertNull("a file written under another token must not be handed over", slot.read(token + "x"))
        assertTrue("a refused read must LEAVE the file: the token that does match may still ask",
            file.isFile)
        assertEquals("the rightful owner must still get its draft back", "the real draft",
            slot.read(token)!!.body)
    }

    /** A resume is consumed once — that is what "no file left after a resume" is measured on. */
    @Test
    fun `a successful read erases the file, so the second read finds nothing`() {
        val token = park(slot, "once and once only", 2, 2, "base")

        assertEquals("once and once only", slot.read(token)!!.body)
        assertFalse("a consumed resume must leave no cleartext behind on disk", file.isFile)
        assertNull("a consumed resume must not be replayed", slot.read(token))
    }

    /** A file cut short by a crash or a full disk is refused WHOLE, never handed back amputated. */
    @Test
    fun `a truncated file reads null instead of an amputated draft`() {
        val token = park(slot, "A".repeat(20_000), 5, 5, "base")
        val whole = file.readText(Charsets.UTF_8)
        file.writeText(whole.substring(0, whole.length / 2), Charsets.UTF_8)

        assertNull("half a file must be refused, not returned as a shorter draft", slot.read(token))
    }

    /** Not even a header: nothing to parse, and still no exception out of a restore. */
    @Test
    fun `a file with no header line reads null`() {
        file.parentFile!!.mkdirs()
        file.writeText("this is not a resume", Charsets.UTF_8)

        assertNull(slot.read("any-token"))
        assertNull(restoreComposerResume("any-token", slot))
    }

    /** Missing slot: a fresh composer, not a crash. */
    @Test
    fun `restoring with no file at all yields no state`() {
        assertNull(restoreComposerResume("any-token", slot))
    }

    // -- the closed flag, and what it protects ---------------------------------------------------

    /**
     * The rule that keeps a sent — possibly ENCRYPTED — message's cleartext off the disk. The
     * navigation entry is saved as it leaves the composition, i.e. AFTER the composer closed, so
     * without this the very last thing the screen does is write the body back out.
     */
    @Test
    fun `a closed composer parks nothing and leaves no file`() {
        val state = ComposerResumeState(TextFieldValue("secret text", TextRange(0)), "base")
        state.closed = true

        assertNull("a closed composer must not be parked", saveComposerResume(state, slot))
        assertFalse("a closed composer must leave NO file on disk", file.isFile)
    }

    /** Closing also takes away what an earlier save had already parked. */
    @Test
    fun `close erases a slot that was already written`() {
        val state = ComposerResumeState(TextFieldValue("secret text", TextRange(0)), "base")
        assertTrue(saveComposerResume(state, slot) != null)
        assertTrue(file.isFile)

        state.close(slot)

        assertTrue("close must raise the flag that stops the teardown save", state.closed)
        assertFalse("close must take the parked cleartext away", file.isFile)
        assertNull("and nothing may be parked afterwards", saveComposerResume(state, slot))
    }

    // -- one record at a time --------------------------------------------------------------------

    /**
     * THE ROTATION DEFECT, and the reason this file exists a second time. Read in androidx's
     * bytecode, not guessed: `SaveableStateHolderImpl.saveAll()` parcels a COPY of the saved
     * states, and `SaveableStateProvider`'s `onDispose` then calls `saveTo` a SECOND time on the
     * live map when the composition is torn down. So the saver runs TWICE per activity recreation
     * — once for the parcel, once at teardown — and the teardown run used to mint a brand-new
     * token, leaving the file under a name no parcel could ever ask for again.
     *
     * On the bench that was: rotate the screen, the composer comes back with its subject and an
     * EMPTY body, at any size, without an exception, a toast or a word. HOME never showed it,
     * because HOME does not tear the composition down.
     */
    @Test
    fun `the teardown save that follows the parcel cannot orphan what the parcel already names`() {
        val body = "the reply she is halfway through writing"
        val state = ComposerResumeState(TextFieldValue(body, TextRange(4, 9)), "> the quoted original")

        val parcelled = saveComposerResume(state, slot)!!   // saveAll(): this is what the Bundle carries
        saveComposerResume(state, slot)                     // onDispose: saveTo() runs the saver again

        val back = restoreComposerResume(parcelled, slot)
        assertTrue(
            "the value the parcel carries must still open the file the teardown rewrote — otherwise " +
                "every rotation loses the body in silence",
            back != null,
        )
        assertEquals("the body must come back whole", body, back!!.bodyState.value.text)
        assertEquals("the baseline must come back whole", "> the quoted original", back.baselineState.value)
        assertEquals("the caret must come back where it was", TextRange(4, 9), back.bodyState.value.selection)
    }

    /** One composer is ONE record, however many times the saver is asked to park it. */
    @Test
    fun `every save of the same composer names the same record`() {
        val state = ComposerResumeState(TextFieldValue("a draft in flight", TextRange(2)), "base")

        val first = saveComposerResume(state, slot)
        val second = saveComposerResume(state, slot)

        assertEquals(
            "the parcel and the file may not diverge: a second save of the SAME composer must name " +
                "the record the first one named, or the first value — the one in the parcel — is " +
                "orphaned on disk",
            first, second,
        )
    }

    /**
     * REPLACES "two writes give two tokens and the older one no longer reads", which pinned the
     * DEFECT: it read the saver's two runs per recreation as two records, and that is exactly what
     * cost the body on every rotation. Two COMPOSERS are two records; two saves of one composer are
     * one. A record another composer wrote is still refused, and still left where it was — that is
     * what [`a wrong token reads nothing and does not consume the file`] holds.
     */
    @Test
    fun `two composers are two records`() {
        val mine = ComposerResumeState(TextFieldValue("the long reply", TextRange(0)), "")
        val theirs = ComposerResumeState(TextFieldValue("the mailto: composer", TextRange(0)), "")

        assertNotEquals(
            "each composer must be identifiable on its own, or the one on top reads — and consumes " +
                "— the record of the one underneath",
            mine.token, theirs.token,
        )
    }

    /**
     * A slot holds ONE record: a newer one takes the place of the older, and the older name stops
     * opening it. This is what the replaced test held that was true — the false half was reading
     * the saver's two runs per recreation as two records, which is what cost the body on rotation.
     */
    @Test
    fun `a newer record takes the older one's place, and the older name stops opening the slot`() {
        val older = park(slot, "first draft", 1, 1, "base")
        val newer = park(slot, "second draft", 2, 2, "base")

        assertNotEquals("two records written apart must be tellable apart", older, newer)
        assertNull("a stale name must not be served the newer record", slot.read(older))
        assertTrue("the refused stale read must not have consumed the file", file.isFile)
        assertEquals("second draft", slot.read(newer)!!.body)
    }

    /**
     * A RENAME THAT IS REFUSED IS A FAILED SAVE. The write succeeds into the temporary and only
     * the rename is turned down (here the target is a directory; in the field: SELinux, a lock, a
     * quota reached in between). Reported as success, it hands the parcel a token for a file that
     * does not exist, the inline fallback is skipped, and the composer comes back EMPTY — at 126
     * characters as at 324 kB, with no exception and no word. The other failure path, the
     * exception, is what [`a body that could not be written falls back into the parcel, whole`]
     * holds; nothing held this one.
     */
    @Test
    fun `a rename the system refuses is a failed save, and falls back into the parcel`() {
        val dir = folder.newFolder("rename")
        val target = File(dir, "composer-resume-blocked")
        assertTrue("the target must be a directory for the rename to be refused", target.mkdirs())
        val blocked = ComposerResumeSlot(target)

        assertFalse(
            "a rename the system refused must be reported as a failure, never as a token pointing " +
                "at a file that is not there",
            blocked.write(newComposerToken(), "the draft she is writing", 3, 3, "base"),
        )
        assertFalse("no temporary may be left beside a refused rename",
            File(dir, "composer-resume-blocked.tmp").isFile)

        val state = ComposerResumeState(TextFieldValue("the draft she is writing", TextRange(3)), "base")
        val saved = saveComposerResume(state, blocked)
        assertTrue("a short body must reach the parcel inline when the file could not be written: `$saved`",
            saved != null && saved.startsWith("v:"))
        assertEquals("the draft she is writing", restoreComposerResume(saved!!, blocked)!!.bodyState.value.text)
    }

    /** No temporary is left lying around beside the slot. */
    @Test
    fun `clear removes the slot and its temporary`() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        park(slot, "draft", 0, 0, "base")
        tmp.writeText("leftover", Charsets.UTF_8)

        slot.clear()

        assertFalse(file.isFile)
        assertFalse("a leftover temporary is cleartext too", tmp.isFile)
    }

    // -- the parcel fallback, for the day the file cannot be written -----------------------------

    /**
     * A `write` that fails must NOT come back as "nothing was saved". It used to: the saver then
     * parcelled `null`, the composer was rebuilt EMPTY over an `applied` that survived in the
     * parcel, `draftReopenView` drew that blank editor as if it were the draft, and `isDirty` being
     * false the back gesture left without a word. A short body costs a few kB of parcel — which is
     * what it cost yesterday, before any of this — so under a conservative bound it travels inline.
     */
    @Test
    fun `a body that could not be written falls back into the parcel, whole`() {
        val body = "short body, but every bit of it matters"
        val baseline = "> a different baseline entirely"
        val state = ComposerResumeState(TextFieldValue(body, TextRange(7, 21)), baseline)

        val saved = saveComposerResume(state, unwritableSlot())!!
        assertTrue("the fallback must be the inline form, not a token: `$saved`", saved.startsWith("v:"))

        val back = restoreComposerResume(saved, unwritableSlot())!!
        assertEquals("the body came back changed", body, back.bodyState.value.text)
        assertEquals("the baseline came back changed", baseline, back.baselineState.value)
        assertEquals("the selection START was lost", 7, back.bodyState.value.selection.start)
        assertEquals("the selection END was lost", 21, back.bodyState.value.selection.end)
    }

    /** A body with the separator in it: the inline form is length-described, never split on a token. */
    @Test
    fun `the inline fallback survives colons, newlines and an emoji in the body`() {
        val body = "v:1:2:3: not a header\nnor is this: 😀\r\nfin"
        val baseline = "base: with a colon too 😀"
        val state = ComposerResumeState(TextFieldValue(body, TextRange(3, 9)), baseline)

        val back = restoreComposerResume(saveComposerResume(state, unwritableSlot())!!, unwritableSlot())!!

        assertEquals(body, back.bodyState.value.text)
        assertEquals(baseline, back.baselineState.value)
        assertEquals(TextRange(3, 9), back.bodyState.value.selection)
    }

    /**
     * REWRITTEN, and the rule it carries is now half of what it used to be. What stays true:
     * above the bound the text is NOT forced into the parcel — the parcel is what killed the
     * activity in the first place. What was false: "the fallback may never make anything worse".
     * The crash took the WHOLE parcel, `applied` with it, so the composer came back unprimed and
     * the prefill replayed the draft from the server. Returning nothing loses only the body and
     * leaves `applied` standing: the editor is redrawn empty over a draft nobody re-reads, and the
     * next Save destroys the server copy and replaces it with a bodiless one, in silence.
     *
     * So the text is dropped and the LOSS is carried in its place.
     */
    @Test
    fun `a body over the inline bound is not forced into the parcel, and the loss travels instead`() {
        val state = ComposerResumeState(TextFieldValue("x".repeat(16_385), TextRange(0)), "")

        val saved = saveComposerResume(state, unwritableSlot())
        assertEquals(
            "16 384 characters is ~64 kB of UTF-16 parcel, far under the 1 MB buffer; past that " +
                "the fallback must refuse to carry the TEXT rather than kill the activity it " +
                "exists to save — and it must say, in the parcel, that the body was lost. `null` " +
                "here is the destroy: the composer comes back empty over a live `applied` and the " +
                "save takes the server copy with it.",
            "l:", saved,
        )
        assertFalse("not one character of the body may reach the parcel: `$saved`", saved!!.contains("x"))

        val back = restoreComposerResume(saved, unwritableSlot())
        assertTrue(
            "a lost body must still rebuild a composer: `null` would drop the flag along with the " +
                "text, and the flag is the only thing standing between the empty editor and the " +
                "destruction of the draft on the server",
            back != null,
        )
        assertEquals("nothing may be invented in place of the lost body", "", back!!.bodyState.value.text)
        assertEquals("nor in place of its baseline", "", back.baselineState.value)
        assertTrue(
            "…and the composer it rebuilds must KNOW its body was lost. An empty composer that " +
                "believes it is clean is the destroy: the screen pushes this flag into the view " +
                "model, where `editingDraftLossy` reads it, and the four destroy routes keep the " +
                "server copy instead of replacing it with a bodiless one.",
            back.bodyWasLost,
        )
    }

    /**
     * THE PARCEL NAMED A RECORD, AND THE RECORD WAS NOT THERE. Measured on the bench on
     * 2026-08-26: a 20 112 character server draft, HOME pressed, the slot written (40 284 bytes),
     * the file then removed — the removal was provoked on the bench, and this test stands for the
     * whole family `read` collapses into one `null`: a file no longer there, a truncated or foreign
     * header, a read that threw. The composer came back with an EMPTY body,
     * `To` and `Subject` intact from their own saveables, and the Save icon lit; that save left one
     * 317 byte bodiless row on the server where 21 260 bytes had been. Irreversible, in silence.
     *
     * A `t:` parcel is a PROMISE that a body was parked. Failing to read it back is a LOSS, not
     * an absence: `read` collapses five different causes into one `null`, and none of them means
     * "there was nothing to restore". The composer must come back knowing it, exactly as it does
     * for the `l:` mark, so that `editingDraftLossy` keeps the server copy instead of replacing it.
     *
     * The BOUND on this — a `t:` with no token at all must still restore to nothing — is the
     * `"t:"` entry of the junk list in `an unrecognised parcel value restores nothing`. A parcel
     * from an older build may not be allowed to arm the loss on a composer that never parked
     * anything.
     */
    @Test
    fun `a parked record that cannot be read back is a loss, not an empty composer`() {
        val body = longBody(20_112)
        val state = ComposerResumeState(TextFieldValue(body, TextRange(4_096, 4_096)), "> the quoted original")

        val saved = saveComposerResume(state, slot)
        assertEquals("the body had to be PARKED for this test to mean anything", "t:", saved?.take(2))
        assertTrue("…and the record had to reach the disk", file.isFile)
        assertTrue("the record is taken away behind the slot's back", file.delete())

        val back = restoreComposerResume(saved!!, slot)
        assertTrue(
            "the parcel names a record that could not be read, and `null` here is the destroy: " +
                "`rememberSaveable` falls back to its factory, the composer comes back empty AND " +
                "clean over a live `applied`, and the next Save replaces the server draft with a " +
                "bodiless one",
            back != null,
        )
        assertTrue(
            "…and the composer rebuilt from an unreadable record must KNOW its body was lost — a " +
                "site that cannot prove fidelity says `true`",
            back!!.bodyWasLost,
        )
        assertEquals("nothing may be invented in place of the body that was not read", "",
            back.bodyState.value.text)
        assertEquals("nor a caret into a body that is not there", TextRange(0, 0),
            back.bodyState.value.selection)
        assertEquals("nor a baseline, which would let the leave guard call this draft unchanged", "",
            back.baselineState.value)

        assertEquals(
            "and the loss must travel on: the next save of this composer parks fine, and must " +
                "still carry the mark that the body it holds is not the body that went missing",
            "l:t:", saveComposerResume(back, slot)?.take(4),
        )
    }

    /**
     * THE FLAG IS STICKY, and this is the only test that says so. Without it, raising the flag
     * solely on the save that fails passes everything else: the very next save — a rotation a
     * second later, with the disk free again — parks the body perfectly and carries NO flag, the
     * verdict falls back to clean, and the Save after that destroys the server copy of a draft
     * whose body was lost two rotations ago. What came back after the loss is not what was lost.
     */
    @Test
    fun `the lost flag is sticky, so every later save carries it, including the ones that work`() {
        val lost = restoreComposerResume("l:", slot)
        assertTrue("`l:` must rebuild a composer that knows its body was lost", lost != null)
        assertTrue("`l:` alone must raise the flag on the composer it rebuilds", lost!!.bodyWasLost)
        lost.bodyState.value = TextFieldValue("what she retyped after the loss", TextRange(4))

        val again = saveComposerResume(lost, slot)
        assertEquals(
            "a save that PARKS PERFECTLY, on a composer that has already lost a body, must still " +
                "carry the loss — the parked text is not the text that went missing",
            "l:t:", again?.take(4),
        )

        val back = restoreComposerResume(again!!, slot)
        assertEquals("the retyped text must come back whole under the flag",
            "what she retyped after the loss", back!!.bodyState.value.text)
        assertTrue("and the composer rebuilt from `l:t:…` must still know it lost a body", back.bodyWasLost)
        assertEquals(
            "…and a THIRD save must carry it too: the flag is raised once and never lowered",
            "l:t:", saveComposerResume(back, slot)?.take(4),
        )
    }

    /** A composer that has lost nothing says nothing: neither the parked form nor the inline one. */
    @Test
    fun `a save that works, and an inline fallback under the bound, carry no lost flag`() {
        val parked = saveComposerResume(ComposerResumeState(TextFieldValue("a draft", TextRange(0)), "b"), slot)
        assertFalse("an ordinary restore must hand back a composer that has lost nothing",
            restoreComposerResume(parked!!, slot)!!.bodyWasLost)
        assertEquals(
            "an ordinary save must NOT claim a loss — a flag raised on every save is a verdict " +
                "stuck at `lossy` forever, which quietly stops every legitimate draft replacement",
            "t:", parked?.take(2),
        )
        val inline = saveComposerResume(
            ComposerResumeState(TextFieldValue("a short draft", TextRange(0)), "b"), unwritableSlot(),
        )
        assertEquals("the inline fallback carried the text WHOLE — nothing was lost, so nothing is claimed",
            "v:", inline?.take(2))
    }

    /**
     * AN EMPTY BODY THAT WAS PARKED IS NOT A LOST ONE, and this is the counter-witness the
     * decision hangs on: what says "lost" is the RECORD that could not be read, never the emptiness
     * of what came back. Read the emptiness instead — the mutation looks like a hardening — and the
     * most ordinary screen in the app is armed: a new message where the recipient and the subject
     * are typed before a single word of body, a `mailto:` with no body, a reply not yet started.
     * `ComposeScreen` would push the mark into `onComposerBodyLost()`, `editingDraftLossy` would
     * stay raised for the life of the ViewModel, and EVERY save would leave a duplicate in Drafts
     * under a notice about a server copy nobody needed to keep.
     */
    @Test
    fun `an empty body that was parked and read back whole has lost nothing`() {
        val saved = saveComposerResume(ComposerResumeState(TextFieldValue("", TextRange(0)), ""), slot)
        assertEquals("the empty body had to be PARKED for this test to mean anything", "t:", saved?.take(2))

        val back = restoreComposerResume(saved!!, slot)!!

        assertFalse(
            "the record was read back exactly as it was parked — an empty body is a VALUE, and " +
                "calling it a loss arms the destroy licence on every composer whose body is still " +
                "to be typed",
            back.bodyWasLost,
        )
        assertEquals("", back.bodyState.value.text)
        assertEquals(
            "…and the next save must not claim a loss either",
            "t:", saveComposerResume(back, slot)?.take(2),
        )
    }

    /** The prefix rides on the form, it does not eat it: body, baseline and caret come back whole. */
    @Test
    fun `a lost flag over an inline fallback leaves the payload untouched`() {
        val lost = restoreComposerResume("l:", slot)!!
        val body = "v:1:2:3: still not a header\nnor is this: 😀"
        val baseline = "> the quoted original, with a : in it"
        lost.bodyState.value = TextFieldValue(body, TextRange(3, 9))
        lost.baselineState.value = baseline

        val saved = saveComposerResume(lost, unwritableSlot())!!
        assertEquals("under the bound the text must still travel, flag or no flag", "l:v:", saved.take(4))

        val back = restoreComposerResume(saved, unwritableSlot())!!
        assertEquals("the prefix ate into the body", body, back.bodyState.value.text)
        assertEquals("the prefix ate into the baseline", baseline, back.baselineState.value)
        assertEquals("the prefix ate into the caret", TextRange(3, 9), back.bodyState.value.selection)
        assertTrue("…and the flag rode along with it", back.bodyWasLost)
    }

    /**
     * Exactly at the bound — body, baseline AND the 27-character styling line of an unstyled
     * composer (`b;i;u;s;U;O;a|b;i;u;s;U;O;a`) — accepted, so the bound is a bound and not a guess.
     * Adapted three times: the baseline was 384 characters before #131 (no styling line at all),
     * then 369 when the line held the four inline families, 361 once it carried the two block
     * groups, and 357 now that the links have a section of their own. The number moves with the
     * FORMAT, never with the bound: `INLINE_LIMIT` is the size of parcel this screen may still
     * send, and it has not changed.
     */
    @Test
    fun `a body exactly at the inline bound still travels`() {
        val body = "y".repeat(16_000)
        val baseline = "z".repeat(357)
        val state = ComposerResumeState(TextFieldValue(body, TextRange(5)), baseline)

        val saved = saveComposerResume(state, unwritableSlot())!!
        val back = restoreComposerResume(saved, unwritableSlot())!!

        assertEquals(body, back.bodyState.value.text)
        assertEquals(baseline, back.baselineState.value)
    }

    /**
     * The styling line is IN the parcel, so it is in the bound: a text that fits on its own but
     * not with its spans is a loss, marked as one — never a `v:` parcel over the bound.
     */
    @Test
    fun `the styling line counts against the inline bound`() {
        val body = "y".repeat(16_356)   // 16 356 + the 27-character unstyled line = 16 383: fits
        val plain = ComposerResumeState(TextFieldValue(body, TextRange(0)), "")
        assertEquals("unstyled, this body must still travel inline", "v:",
            saveComposerResume(plain, unwritableSlot())?.take(2))

        val styled = ComposerResumeState(
            TextFieldValue(body, TextRange(0)), "",
            ranges = mapOf(Inline.BOLD to listOf(Span(0, 1), Span(2, 3), Span(4, 5), Span(6, 7))),   // +15 characters
        )
        val saved = saveComposerResume(styled, unwritableSlot())
        assertEquals(
            "with its styling line the parcel would be 16 398 characters, over the bound: the text " +
                "must be dropped and the loss marked, not forced into the parcel",
            "l:", saved,
        )
        assertFalse("not one character of the body may reach the parcel: `$saved`", saved!!.contains("y"))
    }

    /**
     * Anything the saver does not recognise is nothing, and never an exception out of a restore.
     *
     * The bound is the EMPTY form, on both carriers: `t:` and `v:` on their own NAME nothing, so
     * they must still restore to nothing — a parcel that never promised a body may not arm the
     * loss on a composer that never parked one.
     */
    @Test
    fun `an unrecognised parcel value restores nothing`() {
        for (junk in listOf("", "x:whatever", "v:", "t:")) {
            assertNull("`$junk` must restore to nothing, not to a mangled draft", restoreComposerResume(junk, slot))
        }
    }

    /**
     * …AND THE OTHER SIDE OF THAT BOUND: a `v:` that CARRIES something and cannot be read is a
     * LOSS, not an absence — the same sentence the file already writes for `t:`. Restored as
     * nothing, `rememberSaveable` falls back to its factory and the composer comes back empty AND
     * clean: `initialBody` is empty too, so `ComposeDirty` answers "unchanged", the Save icon is
     * still lit because a recipient survived, and that save goes out with `replacesEmailId` armed
     * and a clean verdict. The server copy is destroyed in favour of a bodiless one, in silence.
     */
    @Test
    fun `an inline parcel that carries something unreadable is a loss, not an absence`() {
        for (broken in listOf(
            "v:12", "v:1:2", "v:1:2:3", "v:a:b:c:body",
            // A styling line in a shape no build ever wrote: five sections opened `Z`.
            "v:1:2:5:b;i;u;s;Z|b;i;u;s;Z:hellobase",
            // …and one whose payload is shorter than the body length it promises.
            "v:1:2:99:b;i;u;s;U;O;a|b;i;u;s;U;O;a:hellobase",
        )) {
            val back = restoreComposerResume(broken, unwritableSlot())
            assertTrue("`$broken` promised a body: it must restore SOMETHING", back != null)
            assertEquals("`$broken`: nothing readable came back", "", back!!.bodyState.value.text)
            assertTrue("⛔ `$broken` must arm the loss, or the next save destroys the server copy",
                back.bodyWasLost)
        }
    }

    // -- a composer parked INLINE crosses an update too -------------------------------------------

    /**
     * THE REGRESSION THIS TEST EXISTS FOR. The parcel is NOT always this build's own: the disk
     * write fails (full disk, an unwritable `filesDir`), the body travels inline instead, the app
     * is updated, and the process comes back on a saved state the PREVIOUS build wrote. Read with
     * the current shape nailed on, that line is refused — and a refused `v:` is worse than a
     * refused `t:`, because until the loss was marked the composer came back empty AND clean.
     * Every form any build ever put in a parcel must still open here.
     */
    @Test
    fun `a parcel written by an older build still restores its body and its styling`() {
        // `v:<start>:<end>:<bodyChars>:<styling>:<body><baseline>`, written out by hand.
        val lists = "v:1:2:5:b0-5;i;u;s;U0-0;O|b;i;u;s;U;O:hellobase"
        val fromLists = restoreComposerResume(lists, unwritableSlot())
        assertTrue("⛔ a parcel from the LISTS build must not be refused", fromLists != null)
        assertEquals("hello", fromLists!!.bodyState.value.text)
        assertEquals("base", fromLists.baselineState.value)
        assertEquals(TextRange(1, 2), fromLists.bodyState.value.selection)
        assertEquals(mapOf(Inline.BOLD to listOf(Span(0, 5))), fromLists.rangesState.value)
        assertEquals(listOf(Block(BlockKind.BULLET, 0..0)), fromLists.blocksState.value)
        assertEquals("that build knew no link", emptyList<Link>(), fromLists.linksState.value)
        assertFalse("nothing was lost: the record read whole", fromLists.bodyWasLost)

        val links = "v:1:2:5:b0-5;i;u;s;a0-5-https%3A%2F%2Fx|b;i;u;s;a:hellobase"
        val fromLinks = restoreComposerResume(links, unwritableSlot())
        assertTrue("⛔ a parcel from the LINKS build must not be refused", fromLinks != null)
        assertEquals("hello", fromLinks!!.bodyState.value.text)
        assertEquals(listOf(Link(Span(0, 5), "https://x")), fromLinks.linksState.value)
        assertEquals("that build knew no list", emptyList<Block>(), fromLinks.blocksState.value)
        assertFalse(fromLinks.bodyWasLost)

        val spans = "v:1:2:5:b0-5;i;u;s|b;i;u;s:hellobase"
        val fromSpans = restoreComposerResume(spans, unwritableSlot())
        assertTrue("⛔ a parcel from the build before either of them must not be refused", fromSpans != null)
        assertEquals("hello", fromSpans!!.bodyState.value.text)
        assertEquals(mapOf(Inline.BOLD to listOf(Span(0, 5))), fromSpans.rangesState.value)
        assertEquals(emptyList<Block>(), fromSpans.blocksState.value)
        assertEquals(emptyList<Link>(), fromSpans.linksState.value)
        assertFalse(fromSpans.bodyWasLost)
    }

    /**
     * THE DECISION, from the LINE ALONE — the parcel has no header to offer, so this is the whole
     * of what it can read. Executed, with its arguments pinned.
     */
    @Test
    fun `the shape of a styling line is read from the line alone`() {
        assertEquals("four sections: the families alone",
            StylingShape.FAMILIES, stylingShapeOfLine("b0-5;i;u;s|b;i;u;s"))
        assertEquals("five, the fifth opened by `a`: the families and the links",
            StylingShape.FAMILIES_LINKS, stylingShapeOfLine("b;i;u;s;a0-5-x|b;i;u;s;a"))
        assertEquals("six, opened by `U` then `O`: the families and the lists",
            StylingShape.FAMILIES_BLOCKS, stylingShapeOfLine("b;i;u;s;U0-0;O|b;i;u;s;U;O"))
        assertEquals("seven: all three, what is written today",
            StylingShape.FAMILIES_BLOCKS_LINKS, stylingShapeOfLine("b;i;u;s;U;O;a|b;i;u;s;U;O;a"))
        for (never in listOf(
            "", "garbage", "b;i;u", "b;i;u;s;U|b;i;u;s;U", "b;i;u;s;a;O|b;i;u;s;a;O",
            "b;i;u;s;U;a|b;i;u;s;U;a", "b;i;u;s;a;O;a|b;i;u;s;a;O;a", "b;i;u;s;U;O;U|b;i;u;s;U;O;U",
            "b;i;u;s;U;O;a;a|b;i;u;s;U;O;a;a",
        )) {
            assertNull("`$never` is a line no build ever wrote", stylingShapeOfLine(never))
        }
    }


    // -- the styling travels with the text (#131) ------------------------------------------------

    /** A body with every family, a baseline with one: the maps come back EQUAL, through the file. */
    @Test
    fun `the body's and the baseline's styling round trip through the file`() {
        val body = "Bonjour Amélie,\n\nvoici la pièce jointe.\r\n" + "x".repeat(2_000)
        val state = ComposerResumeState(
            TextFieldValue(body, TextRange(3, 9)), "> quoted",
            ranges = STYLED, baselineRanges = mapOf(Inline.BOLD to listOf(Span(2, 6))),
        )

        val saved = saveComposerResume(state, slot)!!
        assertEquals("the record had to be PARKED for this test to mean anything", "t:", saved.take(2))
        val back = restoreComposerResume(saved, slot)!!

        assertEquals("the body's styling did not come back as it went in", STYLED, back.rangesState.value)
        assertEquals("the baseline's styling did not come back as it went in",
            mapOf(Inline.BOLD to listOf(Span(2, 6))), back.baselineRangesState.value)
        assertEquals("the text must still come back whole beside the styling", body, back.bodyState.value.text)
        assertEquals("> quoted", back.baselineState.value)
        assertEquals(TextRange(3, 9), back.bodyState.value.selection)
    }

    /**
     * The lists (#131), through the file: both sides, both kinds, and the numbering that is NOT
     * stored — a block is a run of lines, the markers are drawn from it.
     */
    @Test
    fun `the body's and the baseline's blocks round trip through the file`() {
        val body = "shopping\nmilk\neggs\nlater\none\ntwo"
        val blocks = listOf(Block(BlockKind.BULLET, 1..2), Block(BlockKind.NUMBER, 4..5))
        val state = ComposerResumeState(
            TextFieldValue(body, TextRange(9, 13)), "shopping\nmilk",
            ranges = mapOf(Inline.BOLD to listOf(Span(0, 8))),
            blocks = blocks,
            baselineBlocks = listOf(Block(BlockKind.BULLET, 1..1)),
        )

        val saved = saveComposerResume(state, slot)!!
        assertEquals("the record had to be PARKED for this test to mean anything", "t:", saved.take(2))
        val back = restoreComposerResume(saved, slot)!!

        assertEquals(
            "⛔ the lists must come back: a rotation that flattens them is the list REMOVED, and " +
                "the next save writes the removal",
            blocks, back.blocksState.value,
        )
        assertEquals(
            "…and the baseline's own, or the leave guard sees an edit nobody made",
            listOf(Block(BlockKind.BULLET, 1..1)), back.baselineBlocksState.value,
        )
        assertEquals("the text must still come back whole beside them", body, back.bodyState.value.text)
        assertEquals(mapOf(Inline.BOLD to listOf(Span(0, 8))), back.rangesState.value)
        assertEquals(TextRange(9, 13), back.bodyState.value.selection)
    }

    /** The same, through the parcel: the inline fallback carries the blocks or it carries a lie. */
    @Test
    fun `the inline fallback carries the blocks`() {
        val blocks = listOf(Block(BlockKind.NUMBER, 0..1))
        val state = ComposerResumeState(
            TextFieldValue("milk\neggs", TextRange(0)), "",
            blocks = blocks,
        )

        val saved = saveComposerResume(state, unwritableSlot())!!
        assertTrue("the fallback must be the inline form: `$saved`", saved.startsWith("v:"))
        assertEquals(blocks, restoreComposerResume(saved, unwritableSlot())!!.blocksState.value)
    }

    /**
     * A file written by the build before either the lists or the links: `/2`, four groups per
     * side. It still opens, with no list and no link — the composer that was parked before this
     * lot came back or the first rotation after the update loses a body.
     */
    @Test
    fun `a version 2 file still reads, with its spans and neither block nor link`() {
        file.parentFile!!.mkdirs()
        file.writeText(
            "sterna-composer-resume/2 tok-2 1 2 5 4\nb0-4;i;u;s|b;i;u;s\nhellobase",
            Charsets.UTF_8,
        )

        val resume = slot.read("tok-2")
        assertTrue("a `/2` file written by the previous build must still open", resume != null)
        assertEquals("hello", resume!!.body)
        assertEquals("base", resume.baseline)
        assertEquals(mapOf(Inline.BOLD to listOf(Span(0, 4))), resume.ranges)
        assertEquals("a `/2` file carries no list", emptyList<Block>(), resume.blocks)
        assertEquals(emptyList<Block>(), resume.baselineBlocks)
        assertEquals("…and no link either", emptyList<Link>(), resume.links)
        assertEquals(emptyList<Link>(), resume.baselineLinks)
    }

    /**
     * And what is WRITTEN is `/4`, always. A `/3` header over a seven-section line is a record
     * NO build can read — not the lists build, which expects six groups, not the links build,
     * which expects five, not this one, which reads the shape and finds a line `/3` never held.
     * Every one of them refuses it, and a refused record is a composer that comes back empty.
     */
    @Test
    fun `what this build writes is the version 4 header`() {
        park(
            slot, "hello", 1, 2, "base",
            blocks = listOf(Block(BlockKind.BULLET, 0..0)),
            links = listOf(Link(Span(0, 5), "https://x")),
        )
        val text = file.readText(Charsets.UTF_8)

        assertEquals(
            "the header line must open with the /4 magic: `${text.take(40)}`",
            "sterna-composer-resume/4", text.substringBefore(' '),
        )
        assertEquals(
            "…over a line of seven sections a side, in the one order they are written",
            "b;i;u;s;U0-0;O;a0-5-https%3A%2F%2Fx|b;i;u;s;U;O;a",
            text.split('\n')[1],
        )
    }

    /** A file written by the build before #131: the old magic, no styling line, still readable. */
    @Test
    fun `a version 1 file still reads, with no styling`() {
        file.parentFile!!.mkdirs()
        file.writeText("sterna-composer-resume/1 tok-1 1 2 5 4\nhellobase", Charsets.UTF_8)

        val resume = slot.read("tok-1")
        assertTrue("a `/1` file written by the previous build must still open", resume != null)
        assertEquals("hello", resume!!.body)
        assertEquals("base", resume.baseline)
        assertEquals(1, resume.selectionStart)
        assertEquals(2, resume.selectionEnd)
        assertEquals("a `/1` file carries no styling", emptyMap<Inline, List<Span>>(), resume.ranges)
        assertEquals(emptyMap<Inline, List<Span>>(), resume.baselineRanges)
        assertEquals("…and no list either", emptyList<Block>(), resume.blocks)
        assertEquals("…and no link either", emptyList<Link>(), resume.links)
    }

    /** The styling line is parsed strictly: anything unreadable refuses the WHOLE record. */
    @Test
    fun `a corrupt styling line reads null, never a draft with guessed styling`() {
        val token = park(slot, "hello", 1, 2, "base", ranges = mapOf(Inline.BOLD to listOf(Span(0, 4))))
        val lines = file.readText(Charsets.UTF_8).split('\n', limit = 3)
        assertEquals("a `/4` file is a header line, a styling line, then the texts", 3, lines.size)
        for (corrupt in listOf(
            "garbage", "", "b0-4;i;u", "b0-4;i;u;s;U;O;a", "b0-4;i;u;s;U;O;a|b;i;u;s;U;O",
            "x0-4;i;u;s;U;O;a|b;i;u;s;U;O;a", "b4-0;i;u;s;U;O;a|b;i;u;s;U;O;a",
            "b0-x;i;u;s;U;O;a|b;i;u;s;U;O;a", "b0-99;i;u;s;U;O;a|b;i;u;s;U;O;a",
            "b0-4,;i;u;s;U;O;a|b;i;u;s;U;O;a", "b;i;u;s;U;O;a|b0-9;i;u;s;U;O;a",
            // …and the block groups (#131), read exactly as strictly: a wrong letter is not ours,
            // a reversed or unparsable run is not a run, and a run past the last possible line is
            // a list over letters that are not there.
            "b;i;u;s;X;O;a|b;i;u;s;U;O;a", "b;i;u;s;U;X;a|b;i;u;s;U;O;a",
            "b;i;u;s;U3-0;O;a|b;i;u;s;U;O;a", "b;i;u;s;U0-x;O;a|b;i;u;s;U;O;a",
            "b;i;u;s;U0-99;O;a|b;i;u;s;U;O;a", "b;i;u;s;U0-1,;O;a|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a|b;i;u;s;U0-9;O;a",
        )) {
            file.writeText(lines[0] + "\n" + corrupt + "\n" + lines[2], Charsets.UTF_8)
            assertNull("`$corrupt` must refuse the record whole", slot.read(token))
            assertTrue("a refused read must leave the file", file.isFile)
        }
        file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        assertEquals("the intact line must still read", mapOf(Inline.BOLD to listOf(Span(0, 4))), slot.read(token)!!.ranges)
    }

    /** The inline fallback carries the styling too, both maps, and the four separators stay four. */
    @Test
    fun `the inline fallback carries the styling`() {
        val state = ComposerResumeState(
            TextFieldValue("short body, styled", TextRange(7, 11)), "> base",
            ranges = STYLED, baselineRanges = mapOf(Inline.ITALIC to listOf(Span(0, 2))),
        )

        val saved = saveComposerResume(state, unwritableSlot())!!
        assertTrue("the fallback must be the inline form: `$saved`", saved.startsWith("v:7:11:18:"))
        val back = restoreComposerResume(saved, unwritableSlot())!!

        assertEquals(STYLED, back.rangesState.value)
        assertEquals(mapOf(Inline.ITALIC to listOf(Span(0, 2))), back.baselineRangesState.value)
        assertEquals("short body, styled", back.bodyState.value.text)
        assertEquals("> base", back.baselineState.value)
        assertEquals(TextRange(7, 11), back.bodyState.value.selection)
    }

    /** Colons and newlines in the text may not be read as separators, on disk or in the parcel. */
    @Test
    fun `styling survives a body full of colons and newlines, on disk and inline`() {
        val body = "v:1:2:3: not a header\nb0-4;i;u;s|b;i;u;s\nnor is this: 😀\r\nfin"
        val baseline = "base: with a colon\nand a newline 😀"
        val ranges = mapOf(Inline.BOLD to listOf(Span(0, 7)), Inline.STRIKE to listOf(Span(22, 30)))
        val baselineRanges = mapOf(Inline.UNDERLINE to listOf(Span(5, 18)))
        val state = ComposerResumeState(TextFieldValue(body, TextRange(3, 9)), baseline, ranges, baselineRanges)

        for ((name, s) in listOf("disk" to slot, "inline" to unwritableSlot())) {
            val back = restoreComposerResume(saveComposerResume(state, s)!!, s)!!
            assertEquals("$name: body", body, back.bodyState.value.text)
            assertEquals("$name: baseline", baseline, back.baselineState.value)
            assertEquals("$name: caret", TextRange(3, 9), back.bodyState.value.selection)
            assertEquals("$name: body styling", ranges, back.rangesState.value)
            assertEquals("$name: baseline styling", baselineRanges, back.baselineRangesState.value)
        }
    }


    // -- the links travel with the text (#131) ---------------------------------------------------

    /**
     * THE RECORD THIS VOLET EXISTS FOR: one body carrying BOTH a list and a link, on both
     * sides, through the file. Two builds shipped a `#131` that could carry one or the other; a
     * record holding the two together is what neither of them could read back.
     */
    @Test
    fun `blocks and links come back together, on both sides`() {
        val body = "shopping\nmilk\neggs\nlater\none\ntwo"
        val blocks = listOf(Block(BlockKind.BULLET, 1..2), Block(BlockKind.NUMBER, 4..5))
        val links = listOf(
            Link(Span(0, 8), "https://shop.example/a"),
            Link(Span(9, 13), "mailto:iris@example.org"),
        )
        val baselineBlocks = listOf(Block(BlockKind.BULLET, 1..1))
        val baselineLinks = listOf(Link(Span(0, 8), "http://e.example/z"))
        val state = ComposerResumeState(
            TextFieldValue(body, TextRange(9, 13)), "shopping\nmilk",
            ranges = mapOf(Inline.BOLD to listOf(Span(0, 8))),
            blocks = blocks, baselineBlocks = baselineBlocks,
            links = links, baselineLinks = baselineLinks,
        )

        for ((name, s) in listOf("disk" to slot, "inline" to unwritableSlot())) {
            val saved = saveComposerResume(state, s)!!
            val back = restoreComposerResume(saved, s)!!
            assertEquals("$name: the body's lists", blocks, back.blocksState.value)
            assertEquals("$name: the baseline's lists", baselineBlocks, back.baselineBlocksState.value)
            assertEquals("$name: the body's links", links, back.linksState.value)
            assertEquals("$name: the baseline's links", baselineLinks, back.baselineLinksState.value)
            assertEquals("$name: the styling beside them",
                mapOf(Inline.BOLD to listOf(Span(0, 8))), back.rangesState.value)
            assertEquals("$name: the text", body, back.bodyState.value.text)
            assertEquals("$name: the baseline", "shopping\nmilk", back.baselineState.value)
            assertEquals("$name: the caret", TextRange(9, 13), back.bodyState.value.selection)
        }
    }

    /**
     * The URL is the ONE value in this record that can hold the format's own punctuation — `:`
     * would end the parcel's header, `\n` would end the styling line, `|` separates the two sides,
     * `,` and `-` cut the spans apart. It is percent-encoded for exactly that reason, and this is
     * the test that says so: a URL carrying every one of them at once, through the FILE and
     * through the PARCEL.
     */
    @Test
    fun `a link whose URL holds every separator round trips, on disk and inline`() {
        val url = "https://e.example/a:b,c;d|e-f g?x=1&y=2#é"
        // THE UNRESERVED SET, ON BOTH SIDES. The encoder writes `A-Za-z0-9._~` raw and the
        // decoder accepts exactly those raw; nothing else in this suite carries a `~`, a `_`, a
        // digit or an upper-case letter OUTSIDE a `%XX` escape, so nothing else can see the two
        // sets drift apart. Drop `~` from either one and `https://example.org/~iris` refuses the
        // whole record — an empty composer, then a destroyed server copy. `+` is on the other
        // side of the line and must come back through `%2B`.
        val unreserved = "https://E.example/~Iris_9+a"
        val links = listOf(
            Link(Span(0, 5), url),
            Link(Span(6, 11), "mailto:iris@example.org"),
            Link(Span(12, 14), unreserved),
        )
        val baselineLinks = listOf(Link(Span(2, 6), "http://e.example/z"))
        val state = ComposerResumeState(
            // 14 characters: STYLED reaches to 14, and a shorter text would clamp it silently.
            TextFieldValue("hello world :)", TextRange(3, 9)), "> quoted",
            ranges = STYLED, baselineRanges = mapOf(Inline.BOLD to listOf(Span(2, 6))),
            links = links, baselineLinks = baselineLinks,
        )

        for ((name, s) in listOf("disk" to slot, "inline" to unwritableSlot())) {
            val saved = saveComposerResume(state, s)!!
            val back = restoreComposerResume(saved, s)!!
            assertEquals("$name: the body's links", links, back.linksState.value)
            assertEquals("$name: the baseline's links", baselineLinks, back.baselineLinksState.value)
            assertEquals("$name: the styling must still come back beside them", STYLED, back.rangesState.value)
            assertEquals("$name: the text", "hello world :)", back.bodyState.value.text)
            assertEquals("$name: the baseline", "> quoted", back.baselineState.value)
            assertEquals("$name: the caret", TextRange(3, 9), back.bodyState.value.selection)
        }
    }

    /** A body with no link at all writes the section empty, and reads back empty. */
    @Test
    fun `a body with no link comes back with none`() {
        val token = park(slot, "hello", 0, 0, "base", ranges = mapOf(Inline.BOLD to listOf(Span(0, 5))))
        val resume = slot.read(token)!!
        assertEquals(emptyList<Link>(), resume.links)
        assertEquals(emptyList<Link>(), resume.baselineLinks)
    }

    /** The link section is parsed as strictly as the spans: one bad character refuses the record. */
    @Test
    fun `a corrupt link section reads null, never a draft with a guessed address`() {
        val token = park(slot, "hello", 1, 2, "base", links = listOf(Link(Span(0, 4), "https://x")))
        val lines = file.readText(Charsets.UTF_8).split('\n', limit = 3)
        assertEquals("a `/4` file is a header line, a styling line, then the texts", 3, lines.size)
        for (corrupt in listOf(
            "b;i;u;s;U;O|b;i;u;s;U;O",                       // six sections where seven are due
            "b;i;u;s;U;O;a0-4-https%3A%2F%2Fx",              // …and on one side only
            "b;i;u;s;U;O;x0-4-https%3A%2F%2Fx|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a0-4|b;i;u;s;U;O;a",                // no URL field at all
            "b;i;u;s;U;O;a0-4-https%3A%2F%2Fx-more|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a4-0-https%3A%2F%2Fx|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a0-99-https%3A%2F%2Fx|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a0-x-https%3A%2F%2Fx|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;aX-4-https%3A%2F%2Fx|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a0-4-https%3|b;i;u;s;U;O;a",        // a truncated percent sequence
            "b;i;u;s;U;O;a0-4-https%ZZx|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a0-4-https://x|b;i;u;s;U;O;a",      // a raw URL, which the format cannot hold
            "b;i;u;s;U;O;a|b;i;u;s;U;O;a0-9-https%3A%2F%2Fx",
        )) {
            file.writeText(lines[0] + "\n" + corrupt + "\n" + lines[2], Charsets.UTF_8)
            assertNull("`$corrupt` must refuse the record whole", slot.read(token))
            assertTrue("a refused read must leave the file", file.isFile)
        }
        file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        assertEquals(
            "the intact line must still read",
            listOf(Link(Span(0, 4), "https://x")),
            slot.read(token)!!.links,
        )
    }

    /**
     * THE THIRD BORDER. The guard is written as living at every border a URL crosses, and this
     * file is one of them: a parked record hands a `Link` straight to the composer, and the next
     * send writes it into the message as `<a href="…">`. A record carrying `javascript:` — or a
     * RELATIVE address, which this border may not invent a scheme for either — refuses the whole
     * record, exactly like any other unreadable section.
     */
    @Test
    fun `a parked record may not bring back an address the guard would refuse`() {
        val token = park(slot, "hello", 0, 0, "base", links = listOf(Link(Span(0, 5), "https://x")))
        val lines = file.readText(Charsets.UTF_8).split('\n', limit = 3)
        for (hostile in listOf(
            "b;i;u;s;U;O;a0-5-javascript%3Aalert%281%29|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a0-5-data%3Atext%2Fhtml%2Cx|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a0-5-file%3A%2F%2F%2Fetc%2Fpasswd|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a0-5-exemple.org|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a0-5-%2Fprix|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a0-5-%23top|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a0-5-|b;i;u;s;U;O;a",
            "b;i;u;s;U;O;a|b;i;u;s;U;O;a0-4-javascript%3Ax",
        )) {
            file.writeText(lines[0] + "\n" + hostile + "\n" + lines[2], Charsets.UTF_8)
            assertNull("`$hostile` must refuse the record whole", slot.read(token))
            assertTrue("a refused read must leave the file", file.isFile)
        }
        file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        assertEquals(
            "an address the guard accepts must still come back",
            listOf(Link(Span(0, 5), "https://x")),
            slot.read(token)!!.links,
        )
    }

    // -- one `/3` header, two builds that wrote different lines under it --------------------------

    /**
     * A file left by the LISTS build, written by hand: `/3`, SIX groups a side. It must read,
     * blocks and all. Refusing it is not a lost list: `read` answers null, the parcel still says
     * `t:<token>`, and the composer comes back EMPTY over a draft it believes was parked.
     */
    @Test
    fun `a version 3 file from the lists build reads its blocks, and is not refused`() {
        file.parentFile!!.mkdirs()
        file.writeText(
            "sterna-composer-resume/3 tok-b 1 2 5 4\nb0-5;i;u;s;U0-0;O|b;i;u;s;U;O\nhellobase",
            Charsets.UTF_8,
        )

        val resume = slot.read("tok-b")
        assertTrue("⛔ a `/3` file from the lists build must NOT be refused", resume != null)
        assertEquals("hello", resume!!.body)
        assertEquals("base", resume.baseline)
        assertEquals(mapOf(Inline.BOLD to listOf(Span(0, 5))), resume.ranges)
        assertEquals("its list must come back", listOf(Block(BlockKind.BULLET, 0..0)), resume.blocks)
        assertEquals("that build knew no link, so there is none", emptyList<Link>(), resume.links)
        assertEquals(emptyList<Link>(), resume.baselineLinks)
    }

    /**
     * And a file left by the LINKS build, under the SAME `/3`: FIVE sections a side. It must
     * read too, links and all — which is why the version number alone may not decide the shape.
     */
    @Test
    fun `a version 3 file from the links build reads its links, and is not refused`() {
        file.parentFile!!.mkdirs()
        file.writeText(
            "sterna-composer-resume/3 tok-a 1 2 5 4\nb0-5;i;u;s;a0-5-https%3A%2F%2Fx|b;i;u;s;a\nhellobase",
            Charsets.UTF_8,
        )

        val resume = slot.read("tok-a")
        assertTrue("⛔ a `/3` file from the links build must NOT be refused", resume != null)
        assertEquals("hello", resume!!.body)
        assertEquals("base", resume.baseline)
        assertEquals(mapOf(Inline.BOLD to listOf(Span(0, 5))), resume.ranges)
        assertEquals("its link must come back",
            listOf(Link(Span(0, 5), "https://x")), resume.links)
        assertEquals("that build knew no list, so there is none", emptyList<Block>(), resume.blocks)
        assertEquals(emptyList<Block>(), resume.baselineBlocks)
    }

    /**
     * THE DECISION ITSELF, executed: which shape a styling line was written in, from the header
     * and the LINE — never from the header alone, because two builds wrote a `/3`.
     */
    @Test
    fun `the shape of a styling line is read from its sections and their letters`() {
        assertEquals(
            "seven sections under a /4: what this build writes",
            StylingShape.FAMILIES_BLOCKS_LINKS,
            ComposerResumeSlot.stylingShapeOf("sterna-composer-resume/4", "b;i;u;s;U;O;a|b;i;u;s;U;O;a"),
        )
        assertEquals(
            "six groups under a /3: the lists build",
            StylingShape.FAMILIES_BLOCKS,
            ComposerResumeSlot.stylingShapeOf("sterna-composer-resume/3", "b;i;u;s;U0-0;O|b;i;u;s;U;O"),
        )
        assertEquals(
            "five sections under a /3: the links build — the LETTER is what tells them apart",
            StylingShape.FAMILIES_LINKS,
            ComposerResumeSlot.stylingShapeOf("sterna-composer-resume/3", "b;i;u;s;a0-5-x|b;i;u;s;a"),
        )
        assertEquals(
            "four groups under a /2: the build before either of them",
            StylingShape.FAMILIES,
            ComposerResumeSlot.stylingShapeOf("sterna-composer-resume/2", "b0-5;i;u;s|b;i;u;s"),
        )
        // And every form no build ever wrote. A shape answered here is a draft handed back as
        // something other than what was written.
        for ((magic, line) in listOf(
            "sterna-composer-resume/3" to "b;i;u;s;U|b;i;u;s;U",           // five, opened by U
            "sterna-composer-resume/3" to "b;i;u;s;a;O|b;i;u;s;a;O",       // six, fifth opened by a
            "sterna-composer-resume/3" to "b;i;u;s;U;O;a|b;i;u;s;U;O;a",   // seven under a /3
            "sterna-composer-resume/4" to "b;i;u;s;U;O|b;i;u;s;U;O",       // six under a /4
            "sterna-composer-resume/4" to "b;i;u;s;a|b;i;u;s;a",           // five under a /4
            "sterna-composer-resume/4" to "b;i;u;s|b;i;u;s",               // four under a /4
            "sterna-composer-resume/2" to "b;i;u;s;U;O;a|b;i;u;s;U;O;a",   // seven under a /2
            "sterna-composer-resume/2" to "b;i;u;s;a|b;i;u;s;a",           // five under a /2
            "sterna-composer-resume/1" to "b;i;u;s;U;O;a|b;i;u;s;U;O;a",   // a /1 has no line at all
            "sterna-composer-resume/5" to "b;i;u;s;U;O;a|b;i;u;s;U;O;a",   // a header from the future
        )) {
            assertNull("`$magic` + `$line` is a form no build wrote", ComposerResumeSlot.stylingShapeOf(magic, line))
        }
    }

    /** …and the record carrying such a line is refused whole, exactly like a bad header. */
    @Test
    fun `a styling line no build ever wrote refuses the record`() {
        file.parentFile!!.mkdirs()
        for ((magic, line) in listOf(
            "sterna-composer-resume/3" to "b;i;u;s;U|b;i;u;s;U",
            "sterna-composer-resume/3" to "b;i;u;s;a;O|b;i;u;s;a;O",
            "sterna-composer-resume/3" to "b;i;u;s;U;O;a|b;i;u;s;U;O;a",
            "sterna-composer-resume/4" to "b;i;u;s;U;O|b;i;u;s;U;O",
            "sterna-composer-resume/4" to "b;i;u;s|b;i;u;s",
        )) {
            file.writeText("$magic tok-x 1 2 5 4\n$line\nhellobase", Charsets.UTF_8)
            assertNull("`$magic` + `$line` must refuse the record whole", slot.read("tok-x"))
            assertTrue("a refused read must leave the file", file.isFile)
        }
    }

    // -- one slot per navigation entry ------------------------------------------------------------

    /**
     * TWO composers ARE reachable: `MainActivity` is singleTask, `onNewIntent` feeds
     * `pendingMailto`, and the host navigates to `compose` unguarded — a mailto: link or a share
     * received with the composer open stacks a SECOND one on the first. On one shared file, closing
     * the second erased the first's text, and the long reply underneath came back blank. Each entry
     * gets its own file, and closing one may not touch the other's.
     */
    @Test
    fun `two slots do not erase each other`() {
        val dir = folder.newFolder("two")
        val mine = ComposerResumeSlot(composerResumeSlotFile(dir, newComposerSlotId()))
        val theirs = ComposerResumeSlot(composerResumeSlotFile(dir, newComposerSlotId()))

        val token = park(mine, "the long reply underneath", 4, 4, "base")
        park(theirs, "the mailto: composer on top", 0, 0, "")
        theirs.clear()

        assertEquals("closing the composer on top must not empty the one underneath",
            "the long reply underneath", mine.read(token)!!.body)
    }

    @Test
    fun `slot ids are distinct`() {
        assertNotEquals(newComposerSlotId(), newComposerSlotId())
    }

    // -- the launch sweep -------------------------------------------------------------------------

    /** With no saved state nothing can consume ANY slot, so all of them are a crash's orphans. */
    @Test
    fun `the sweep erases every slot and nothing else`() {
        val dir = folder.newFolder("sweep")
        val first = composerResumeSlotFile(dir, newComposerSlotId())
        val second = composerResumeSlotFile(dir, newComposerSlotId())
        park(ComposerResumeSlot(first), "one", 0, 0, "")
        park(ComposerResumeSlot(second), "two", 0, 0, "")
        val neighbour = File(dir, "attachments.db")
        neighbour.writeText("not ours", Charsets.UTF_8)

        ComposerResumeSlot.sweep(dir)

        assertFalse("a leftover composer body is cleartext under filesDir", first.isFile)
        assertFalse("sweeping only one file is why this had to become a sweep", second.isFile)
        assertTrue("the sweep must not go near anything that is not a slot", neighbour.isFile)
    }

    /**
     * Parks one record and hands back the token it was parked UNDER — the argument, not something
     * the slot chose. A `write` that minted its own would hand this token to a file that does not
     * carry it, and every read below would come back empty.
     */
    private fun park(
        slot: ComposerResumeSlot,
        body: String,
        selectionStart: Int,
        selectionEnd: Int,
        baseline: String,
        token: String = newComposerToken(),
        ranges: Map<Inline, List<Span>> = emptyMap(),
        blocks: List<Block> = emptyList(),
        links: List<Link> = emptyList(),
    ): String {
        assertTrue(
            "the record had to be written for this test to mean anything",
            slot.write(
                token, body, selectionStart, selectionEnd, baseline, ranges, emptyMap(), blocks,
                emptyList(), links,
            ),
        )
        return token
    }

    /** A slot whose file cannot be written, ever: its parent is a regular file, not a directory. */
    private fun unwritableSlot(): ComposerResumeSlot {
        val blocker = File(folder.root, "blocker")
        if (!blocker.isFile) blocker.writeText("I am a file, not a directory", Charsets.UTF_8)
        return ComposerResumeSlot(File(blocker, "composer-resume-nope"))
    }

    /** Every family, two of them with two spans, so a family or a span dropped on the way is seen. */
    private val STYLED: Map<Inline, List<Span>> = mapOf(
        Inline.BOLD to listOf(Span(0, 4), Span(9, 12)),
        Inline.ITALIC to listOf(Span(2, 6)),
        Inline.UNDERLINE to listOf(Span(1, 3), Span(5, 8)),
        Inline.STRIKE to listOf(Span(10, 14)),
    )

    private fun longBody(chars: Int): String {
        val line = "Le corps de ce message est long, et c'est tout le problème. "
        return buildString(chars + line.length) {
            var i = 0
            while (length < chars) {
                append(i).append(' ').append(line).append('\n')
                i++
            }
        }.substring(0, chars)
    }
}
