package app.sterna.ui.compose

import app.sterna.core.jmap.model.EmailBodyPart
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The DECISION is executed here, not read. [sweepStagedAttachments] answers the one question the
 */
class StagedAttachmentSweepTest {

    private val outgoing = File("/data/user/0/app.sterna/cache/outgoing")

    /** Every file the sweep asked about, in the order it asked. */
    private val existenceProbes = mutableListOf<File>()

    /** A predicate that says yes for [present] and no for everything else, and records every ask. */
    private fun probe(vararg present: String): (File) -> Boolean = { file ->
        existenceProbes += file
        file.path in present
    }

    private fun staged(name: String) = EmailBodyPart(
        partId = File(outgoing, name).path,
        size = 12,
        type = "text/plain",
        name = name,
    )

    /** What plain JMAP puts in the list: a blob on the server, no local path at all. */
    private fun blob(id: String) = EmailBodyPart(
        blobId = id,
        size = 12,
        type = "text/plain",
        name = "$id.txt",
    )

    /** A staged part the BODY points at, the shape a forward stages its inline images in. */
    private fun stagedInline(name: String, disposition: String? = "inline", cid: String? = "i1@sterna") =
        EmailBodyPart(
            partId = File(outgoing, name).path,
            size = 12,
            type = "image/png",
            name = name,
            disposition = disposition,
            cid = cid,
        )

    @Test
    fun `a staged part whose file is gone leaves the list`() {
        val vanished = staged("1-report.pdf")
        val sweep = sweepStagedAttachments(listOf(vanished), outgoing, probe())
        assertEquals("the chip has no bytes behind it any more and must not stay on screen", emptyList<EmailBodyPart>(), sweep.kept)
        assertEquals("and the caller has to be told WHICH parts went, to be able to say so", listOf(vanished), sweep.gone)
    }

    @Test
    fun `a staged part whose file is still there stays`() {
        val alive = staged("2-photo.jpg")
        val sweep = sweepStagedAttachments(listOf(alive), outgoing, probe(alive.partId!!))
        assertEquals(listOf(alive), sweep.kept)
        assertEquals(emptyList<EmailBodyPart>(), sweep.gone)
    }

    /**
     * THE ONE THAT MUST NEVER GO GREEN BY ACCIDENT. The predicate says no to everything, exactly
     */
    @Test
    fun `a JMAP blob part is kept and never asked about`() {
        val onServer = blob("Gb1a2c3")
        val sweep = sweepStagedAttachments(listOf(onServer), outgoing, probe())
        assertEquals(
            "a JMAP attachment lives on the server and has no file on this phone to lose — a sweep " +
                "that drops it empties the list of a composer that lost nothing",
            listOf(onServer), sweep.kept,
        )
        assertEquals(emptyList<EmailBodyPart>(), sweep.gone)
        assertEquals(
            "the sweep must not go near the filesystem for a part that was never staged there",
            emptyList<File>(), existenceProbes,
        )
    }

    /**
     * The same guard, for the shape that would survive a null check: a `partId` that is a bare
     */
    @Test
    fun `a part id that is not a path under outgoing is kept and never asked about`() {
        val serverSide = EmailBodyPart(partId = "2", blobId = "Gz9", size = 3, name = "note.txt")
        val elsewhere = EmailBodyPart(partId = "/data/user/0/app.sterna/cache/other/9-x.bin", size = 3, name = "x.bin")
        val sweep = sweepStagedAttachments(listOf(serverSide, elsewhere), outgoing, probe())
        assertEquals(
            "only paths under cacheDir/outgoing are this composer's own staging; everything else " +
                "belongs to someone who did not ask to be swept",
            listOf(serverSide, elsewhere), sweep.kept,
        )
        assertEquals(emptyList<File>(), existenceProbes)
    }

    /**
     * AN INLINE PART THE BODY POINTS AT IS NEVER SWEPT, file or no file. On a forward, an inline
     */
    @Test
    fun `an inline part with a cid is kept and never asked about`() {
        val inlineImage = stagedInline("1-logo.png")
        val sweep = sweepStagedAttachments(listOf(inlineImage), outgoing, probe())
        assertEquals(
            "the body still holds <img src=\"cid:…\"> for this part and the forwarded block is " +
                "never rebuilt — dropping the part sends a broken image where the send used to refuse",
            listOf(inlineImage), sweep.kept,
        )
        assertEquals(emptyList<EmailBodyPart>(), sweep.gone)
        assertEquals(
            "the verdict does not depend on the file, so the file is not asked about",
            emptyList<File>(), existenceProbes,
        )
    }

    /**
     * THE CONDITION IS THE CONJUNCTION, half 1: a regular attachment that happens to carry a
     * `cid` is not referenced by the body and is swept like any other.
     */
    @Test
    fun `an attachment disposition part with a cid is still swept`() {
        val part = stagedInline("2-scan.png", disposition = "attachment", cid = "c9@sterna")
        val sweep = sweepStagedAttachments(listOf(part), outgoing, probe())
        assertEquals(emptyList<EmailBodyPart>(), sweep.kept)
        assertEquals(
            "a cid alone does not make the body point at it; only an inline disposition does",
            listOf(part), sweep.gone,
        )
        assertEquals(listOf(File(part.partId!!)), existenceProbes)
    }

    /** THE CONJUNCTION, half 2: inline with no `cid` is nothing the body can reference. */
    @Test
    fun `an inline part without a cid is still swept`() {
        val part = stagedInline("3-photo.png", cid = null)
        val sweep = sweepStagedAttachments(listOf(part), outgoing, probe())
        assertEquals(emptyList<EmailBodyPart>(), sweep.kept)
        assertEquals(listOf(part), sweep.gone)
        assertEquals(listOf(File(part.partId!!)), existenceProbes)
    }

    /** A blank `cid` is no `cid` — the same `isNullOrBlank` the repository's own two arms use. */
    @Test
    fun `an inline part with a blank cid is still swept`() {
        val part = stagedInline("4-photo.png", cid = "   ")
        val sweep = sweepStagedAttachments(listOf(part), outgoing, probe())
        assertEquals(emptyList<EmailBodyPart>(), sweep.kept)
        assertEquals(listOf(part), sweep.gone)
    }

    /** The disposition is matched the way MIME writes it, in any case. */
    @Test
    fun `an INLINE disposition in another case is still an inline part`() {
        val part = stagedInline("5-logo.png", disposition = "Inline")
        val sweep = sweepStagedAttachments(listOf(part), outgoing, probe())
        assertEquals(listOf(part), sweep.kept)
        assertEquals(emptyList<File>(), existenceProbes)
    }

    /**
     * The root is a PREFIX, not a substring: a sibling directory whose name starts with the same
     * letters is not inside it. `/…/cache/outgoing-old/1-x` starts with `/…/cache/outgoing`.
     */
    @Test
    fun `a sibling directory with a longer name is not inside the staging root`() {
        val sibling = EmailBodyPart(partId = "/data/user/0/app.sterna/cache/outgoing-old/1-x.bin", size = 3, name = "x.bin")
        val sweep = sweepStagedAttachments(listOf(sibling), outgoing, probe())
        assertEquals(listOf(sibling), sweep.kept)
        assertEquals(emptyList<File>(), existenceProbes)
    }

    /** A mixed list: the survivors keep their order, and only the staged ones are asked about. */
    @Test
    fun `the list keeps its order and only staged parts are probed`() {
        val alive = staged("1-alive.txt")
        val onServer = blob("Gkeep")
        val gone = staged("2-gone.txt")
        val alsoAlive = staged("3-alive.txt")
        val sweep = sweepStagedAttachments(
            listOf(alive, onServer, gone, alsoAlive),
            outgoing,
            probe(alive.partId!!, alsoAlive.partId!!),
        )
        assertEquals(listOf(alive, onServer, alsoAlive), sweep.kept)
        assertEquals(listOf(gone), sweep.gone)
        assertEquals(
            "the sweep must ask about the staged parts, each one by its own path, and about nothing else",
            listOf(File(alive.partId!!), File(gone.partId!!), File(alsoAlive.partId!!)),
            existenceProbes,
        )
    }

    /** Nothing on screen, nothing to say. */
    @Test
    fun `an empty list sweeps to an empty list`() {
        val sweep = sweepStagedAttachments(emptyList(), outgoing, probe())
        assertEquals(emptyList<EmailBodyPart>(), sweep.kept)
        assertEquals(emptyList<EmailBodyPart>(), sweep.gone)
        assertEquals(emptyList<File>(), existenceProbes)
    }

    /** A root handed in with a trailing separator names the same directory. */
    @Test
    fun `a trailing separator on the root changes nothing`() {
        val vanished = staged("1-report.pdf")
        val sweep = sweepStagedAttachments(
            listOf(vanished),
            File("/data/user/0/app.sterna/cache/outgoing/"),
            probe(),
        )
        assertEquals(emptyList<EmailBodyPart>(), sweep.kept)
        assertEquals(listOf(vanished), sweep.gone)
    }
}
