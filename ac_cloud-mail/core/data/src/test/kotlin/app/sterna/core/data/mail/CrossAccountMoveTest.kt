package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The ORDER of a move between accounts, EXECUTED (#189): copy confirmed on B, then — and only
 */
class CrossAccountMoveTest {

    private val source = "Message-ID: <a@x>\r\n\r\nbody\r\n".toByteArray(Charsets.ISO_8859_1)

    private class FakeSteps(
        private val bytes: ByteArray,
        private val written: String? = "imap:B:Projets:12",
        private val found: String? = null,
        private val failing: Map<String, Throwable> = emptyMap(),
    ) : CrossAccountMoveSteps {
        val calls = mutableListOf<String>()
        var bytesWritten: ByteArray? = null

        private fun step(name: String) {
            calls += name
            failing[name]?.let { throw it }
        }

        override fun checkSourceConfigured() = step("checkSource")
        override fun checkTargetConfigured() = step("checkTarget")
        override suspend fun readSource(): ByteArray { step("read"); return bytes }
        override suspend fun writeTarget(bytes: ByteArray): String? {
            step("write")
            bytesWritten = bytes
            return written
        }
        override suspend fun findTarget(): String? { step("find"); return found }
        override fun markTargetLocalMove(targetId: String) = step("mark($targetId)")
        override suspend fun trashSource() = step("trash")
        override suspend fun refreshTargetFolder() = step("refresh")
    }

    @Test fun `the happy path is the seven steps in order, with A's bytes written to B`() = runTest {
        val steps = FakeSteps(source)
        val verdict = crossAccountMove(steps)
        assertEquals(
            listOf("checkSource", "checkTarget", "read", "write", "mark(imap:B:Projets:12)", "trash", "refresh"),
            steps.calls,
        )
        assertEquals(CrossAccountMove.Moved, verdict)
        assertArrayEquals(source, steps.bytesWritten)
    }

    @Test fun `a write B refuses is Failed, and A is never touched`() = runTest {
        val steps = FakeSteps(source, failing = mapOf("write" to IllegalStateException("APPEND failed: NO [OVERQUOTA]")))
        val verdict = crossAccountMove(steps)
        assertEquals(listOf("checkSource", "checkTarget", "read", "write"), steps.calls)
        assertEquals(CrossAccountMove.Failed("APPEND failed: NO [OVERQUOTA]"), verdict)
    }

    @Test fun `a copy B did not name and cannot be found back is CopiedNotRemoved, A untouched`() = runTest {
        val steps = FakeSteps(source, written = null, found = null)
        val verdict = crossAccountMove(steps)
        assertEquals(listOf("checkSource", "checkTarget", "read", "write", "find"), steps.calls)
        assertEquals(CrossAccountMove.CopiedNotRemoved::class, verdict::class)
    }

    @Test fun `a lookup on B that throws is CopiedNotRemoved, not Failed, and A is untouched`() = runTest {
        val steps = FakeSteps(
            source,
            written = null,
            failing = mapOf("find" to IllegalStateException("search failed")),
        )
        val verdict = crossAccountMove(steps)
        assertEquals(listOf("checkSource", "checkTarget", "read", "write", "find"), steps.calls)
        assertEquals(
            CrossAccountMove.CopiedNotRemoved("The copy on the target account could not be named."),
            verdict,
        )
    }

    @Test fun `a cancellation during the lookup on B propagates instead of becoming a verdict`() = runTest {
        val steps = FakeSteps(
            source,
            written = null,
            failing = mapOf("find" to CancellationException("screen left")),
        )
        val thrown = runCatching { crossAccountMove(steps) }.exceptionOrNull()
        assertEquals(CancellationException::class, thrown?.let { it::class })
        assertEquals(listOf("checkSource", "checkTarget", "read", "write", "find"), steps.calls)
    }

    @Test fun `a copy found back by its Message-ID is a move, and the mark carries the id found`() = runTest {
        val steps = FakeSteps(source, written = null, found = "imap:B:Projets:12")
        val verdict = crossAccountMove(steps)
        assertEquals(
            listOf("checkSource", "checkTarget", "read", "write", "find", "mark(imap:B:Projets:12)", "trash", "refresh"),
            steps.calls,
        )
        assertEquals(CrossAccountMove.Moved, verdict)
    }

    @Test fun `a trash that fails after the copy is CopiedNotRemoved, marked before, not refreshed`() = runTest {
        val steps = FakeSteps(source, failing = mapOf("trash" to IllegalStateException("Trash could not be created")))
        val verdict = crossAccountMove(steps)
        assertEquals(
            listOf("checkSource", "checkTarget", "read", "write", "mark(imap:B:Projets:12)", "trash"),
            steps.calls,
        )
        assertEquals(CrossAccountMove.CopiedNotRemoved("Trash could not be created"), verdict)
    }

    @Test fun `a source that cannot be read stops before B is written`() = runTest {
        val steps = FakeSteps(source, failing = mapOf("read" to MessageUnavailableException("Source of a came back empty")))
        val verdict = crossAccountMove(steps)
        assertEquals(listOf("checkSource", "checkTarget", "read"), steps.calls)
        assertEquals(CrossAccountMove.Failed("Source of a came back empty"), verdict)
    }

    @Test fun `a target no longer configured stops before the source is read`() = runTest {
        val steps = FakeSteps(source, failing = mapOf("checkTarget" to AccountGoneException("acc-b")))
        val verdict = crossAccountMove(steps)
        assertEquals(listOf("checkSource", "checkTarget"), steps.calls)
        assertEquals(CrossAccountMove.Failed::class, verdict::class)
    }

    @Test fun `a failed refresh of B's folder is still a move`() = runTest {
        val steps = FakeSteps(source, failing = mapOf("refresh" to IllegalStateException("offline")))
        val verdict = crossAccountMove(steps)
        assertEquals(
            listOf("checkSource", "checkTarget", "read", "write", "mark(imap:B:Projets:12)", "trash", "refresh"),
            steps.calls,
        )
        assertEquals(CrossAccountMove.Moved, verdict)
    }

    @Test fun `a cancellation during the write propagates instead of becoming a verdict`() = runTest {
        val steps = FakeSteps(source, failing = mapOf("write" to CancellationException("screen left")))
        val thrown = runCatching { crossAccountMove(steps) }.exceptionOrNull()
        assertEquals(CancellationException::class, thrown?.let { it::class })
        assertEquals(listOf("checkSource", "checkTarget", "read", "write"), steps.calls)
    }

    @Test fun `a plain cancellation during the checks propagates too`() = runTest {
        val steps = FakeSteps(source, failing = mapOf("checkTarget" to CancellationException("screen left")))
        val thrown = runCatching { crossAccountMove(steps) }.exceptionOrNull()
        assertEquals(CancellationException::class, thrown?.let { it::class })
        assertEquals(listOf("checkSource", "checkTarget"), steps.calls)
    }

    // ---- the three translations ----

    @Test fun `IMAP flags are Seen Flagged Answered in that order and nothing else`() {
        assertEquals(
            "\\Seen \\Flagged \\Answered",
            imapFlagsOf(mapOf("\$draft" to true, "\$answered" to true, "\$flagged" to true, "\$seen" to true)),
        )
        assertEquals("", imapFlagsOf(emptyMap()))
        assertEquals("\\Flagged", imapFlagsOf(mapOf("\$seen" to false, "\$flagged" to true, "\$forwarded" to true)))
    }

    @Test fun `JMAP keywords are the same three, true ones only`() {
        assertEquals(
            setOf("\$seen", "\$flagged", "\$answered"),
            jmapKeywordsOf(mapOf("\$draft" to true, "\$answered" to true, "\$flagged" to true, "\$seen" to true)),
        )
        assertEquals(emptySet<String>(), jmapKeywordsOf(emptyMap()))
        assertEquals(setOf("\$flagged"), jmapKeywordsOf(mapOf("\$seen" to false, "\$flagged" to true, "\$phishing" to true)))
    }

    @Test fun `an RFC 3339 date becomes its epoch millis, and an unreadable one no date at all`() {
        assertEquals(Instant.parse("2026-09-01T21:15:03Z").toEpochMilli(), internalDateOf("2026-09-01T21:15:03Z"))
        assertEquals(1788297303000L, internalDateOf("2026-09-01T21:15:03Z"))
        assertNull(internalDateOf(null))
        assertNull(internalDateOf("garbage"))
        assertNull(internalDateOf(""))
    }

    // ---- the name a copy is found back by ----

    @Test fun `the Message-ID comes from the Email when it has exactly one, else from the source's header`() {
        assertEquals("srv@x", messageIdOf(Email(id = "1", messageId = listOf("srv@x")), source))
        assertEquals("a@x", messageIdOf(Email(id = "1"), source))
        assertEquals("a@x", messageIdOf(Email(id = "1", messageId = listOf("one@x", "two@x")), source))
        assertNull(messageIdOf(Email(id = "1"), "Subject: none\r\n\r\nbody\r\n".toByteArray(Charsets.ISO_8859_1)))
        assertNull(
            messageIdOf(
                Email(id = "1"),
                "Message-ID: <a@x>\r\nMessage-ID: <b@x>\r\n\r\nbody\r\n".toByteArray(Charsets.ISO_8859_1),
            ),
        )
    }

    @Test fun `keywords and date come from the Email, and from the cached row only when the Email has none`() {
        val cached = Email(id = "1", keywords = mapOf("\$seen" to true), receivedAt = "2026-09-01T21:15:03Z")
        val bare = withCachedFallback(Email(id = "1"), cached)
        assertEquals(mapOf("\$seen" to true), bare.keywords)
        assertEquals("2026-09-01T21:15:03Z", bare.receivedAt)
        val full = withCachedFallback(Email(id = "1", keywords = mapOf("\$flagged" to true), receivedAt = "2026-01-01T00:00:00Z"), cached)
        assertEquals(mapOf("\$flagged" to true), full.keywords)
        assertEquals("2026-01-01T00:00:00Z", full.receivedAt)
        val uncached = withCachedFallback(Email(id = "1"), null)
        assertEquals(emptyMap<String, Boolean>(), uncached.keywords)
        assertNull(uncached.receivedAt)
    }
}
