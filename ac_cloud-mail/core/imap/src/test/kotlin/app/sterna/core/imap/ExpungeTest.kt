package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * How a permanent purge behaves on a server WITHOUT the UIDPLUS extension, driven against a
 */
class ExpungeTest {

    /**
     * Enough UIDs to span several `UID STORE` chunks. That is the whole point: what happens when
     * the server has no UIDPLUS is decided once for the operation, not once per chunk.
     */
    private val manyUids = (1L..450L).toList()

    /** What each scripted server still holds flagged `\Deleted`, read after the session closed. */
    private val flaggedPerServer = mutableMapOf<FakeImapServer, () -> List<Long>>()

    /**
     * A server that either implements UIDPLUS or does not, and — crucially — really keeps track
     */
    private fun scriptedServer(
        uidPlus: Boolean,
        advertiseAtLogin: Boolean = false,
        flaggedByOthers: List<Long> = emptyList(),
        forgetsFlagsFor: Set<Long> = emptySet(),
    ): FakeImapServer {
        val flaggedOnServer: MutableSet<Long> =
            Collections.synchronizedSet(sortedSetOf<Long>().apply { addAll(flaggedByOthers) })
        val server = FakeImapServer { tag, line ->
            val uidPlusName = if (uidPlus) " UIDPLUS" else ""
            when {
                advertiseAtLogin && line.startsWith("LOGIN") ->
                    "$tag OK [CAPABILITY IMAP4rev1 LITERAL+$uidPlusName] logged in\r\n"
                line.startsWith("CAPABILITY") ->
                    "* CAPABILITY IMAP4rev1 LITERAL+$uidPlusName\r\n$tag OK capability completed\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = manyUids.size)
                line.startsWith("UID STORE") -> {
                    val set = line.removePrefix("UID STORE ").substringBefore(' ')
                    if (line.contains("+FLAGS (\\Deleted)")) flaggedOnServer.addAll(expandUidSet(set))
                    ok(tag)
                }
                line.startsWith("UID SEARCH DELETED") ->
                    searchResponse(tag, flaggedOnServer.toList() - forgetsFlagsFor)
                // A server without UIDPLUS does not merely dislike this command — it has never
                // heard of it, which is exactly how it answers.
                line.startsWith("UID EXPUNGE") -> if (uidPlus) ok(tag) else "$tag BAD unknown command\r\n"
                line == "EXPUNGE" -> { flaggedOnServer.clear(); ok(tag) }
                // No MOVE extension either: the copy fallback is the path under test.
                line.startsWith("UID MOVE") -> "$tag NO [CANNOT] MOVE unsupported\r\n"
                line.startsWith("UID COPY") -> {
                    val set = line.removePrefix("UID COPY ").substringBefore(' ')
                    "$tag OK [COPYUID 1 $set $set] copy completed\r\n"
                }
                else -> ok(tag)
            }
        }
        flaggedPerServer[server] = { flaggedOnServer.toList() }
        return server
    }

    private fun FakeImapServer.stillFlagged(): List<Long> = flaggedPerServer.getValue(this)()

    private fun List<String>.setsOf(verb: String): List<String> =
        filter { it.startsWith("$verb ") }.map { it.removePrefix("$verb ").substringBefore(' ') }

    private fun List<String>.uidsFlagged(): List<Long> =
        setsOf("UID STORE").flatMap { expandUidSet(it) }.sorted()

    private fun List<String>.bareExpunges(): Int = count { it == "EXPUNGE" }

    // ---- delete(): the conditional purge ----

    /**
     * The defect itself. Somebody else has a message flagged in this Trash; the purge must leave
     */
    @Test
    fun `a foreign flagged message stops the purge, over every chunk`() {
        scriptedServer(uidPlus = false, flaggedByOthers = listOf(9_000L)).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            val issued = server.issued()
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
            // Not attempted-then-recovered either: with the capability known, the command that
            // does not exist on this server is never sent.
            assertTrue("UID EXPUNGE was attempted: $issued", issued.none { it.startsWith("UID EXPUNGE") })
            // Somebody else's message is still there, and so is everything we flagged.
            assertEquals(manyUids + 9_000L, server.stillFlagged())
        }
    }

    /**
     * The other half of the decision: nothing foreign is flagged, so the folder can be emptied
     * for real — with ONE `EXPUNGE`, not one per chunk.
     */
    @Test
    fun `a folder flagged by nobody else is emptied with exactly one EXPUNGE`() {
        scriptedServer(uidPlus = false).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            val issued = server.issued()
            assertTrue("several chunks were expected: $issued", issued.setsOf("UID STORE").size > 1)
            assertEquals("not exactly one EXPUNGE: $issued", 1, issued.bareExpunges())
            assertEquals(emptyList<Long>(), server.stillFlagged())
            // And the EXPUNGE came last, after every chunk had been flagged.
            assertEquals("EXPUNGE", issued.filterNot { it == "LOGOUT" }.last())
        }
    }

    /**
     * The question belongs to the operation, not to the chunk: one `UID SEARCH DELETED` and one
     */
    @Test
    fun `the server is questioned once for a purge spanning several chunks`() {
        scriptedServer(uidPlus = false).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            val issued = server.issued()
            assertTrue("only one chunk: $issued", issued.setsOf("UID STORE").size > 1)
            // `drop(1)`: index 0 is the connect-time question of the RFC 2971 ID command (#173),
            // which every session now puts and which deliberately keeps nothing. What is counted
            // here is what the PURGE asked — one for the whole purge, not one per chunk.
            assertEquals(
                "the capability was asked per chunk: $issued",
                1,
                issued.drop(1).count { it.startsWith("CAPABILITY") },
            )
            assertEquals("DELETED was searched per chunk: $issued", 1, issued.count { it.startsWith("UID SEARCH") })
        }
    }

    /**
     * A STRICT SUBSET is a yes. The server reports fewer flagged messages than we flagged — one
     */
    @Test
    fun `a subset of our own flags still allows the purge`() {
        scriptedServer(uidPlus = false, forgetsFlagsFor = setOf(7L, 42L)).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            assertEquals("not exactly one EXPUNGE: ${server.issued()}", 1, server.issued().bareExpunges())
        }
    }

    /**
     * An empty answer is a no — not because it is unsafe, but because there is nothing left to
     * erase and the command would be pure noise on the wire.
     */
    @Test
    fun `a folder the server reports as holding nothing flagged is left alone`() {
        scriptedServer(uidPlus = false, forgetsFlagsFor = manyUids.toSet()).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            assertEquals("an EXPUNGE went out for nothing: ${server.issued()}", 0, server.issued().bareExpunges())
        }
    }

    /**
     * The cap on the answer. A folder holding far more flagged messages than we flagged must be
     */
    @Test
    fun `a folder full of foreign flags refuses the purge`() {
        scriptedServer(uidPlus = false, flaggedByOthers = (10_000L..20_000L).toList()).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(listOf(1L, 2L, 3L))
            }

            val issued = server.issued()
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
            assertEquals(listOf("UID SEARCH DELETED"), issued.filter { it.startsWith("UID SEARCH") })
            // Ours are flagged and everybody else's are untouched — 10 001 + 3 still there.
            assertEquals(10_004, server.stillFlagged().size)
        }
    }

    /**
     * Stopping short of the erase does not mean stopping short of the flagging: every message the
     */
    @Test
    fun `every named message is flagged deleted, and only those`() {
        scriptedServer(uidPlus = false, flaggedByOthers = listOf(9_000L)).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            assertEquals(manyUids, server.issued().uidsFlagged())
        }
    }

    /** The normal path, unchanged: a server with UIDPLUS erases by UID and asks nothing extra. */
    @Test
    fun `a server advertising UIDPLUS erases exactly the named uids`() {
        scriptedServer(uidPlus = true, flaggedByOthers = listOf(9_000L)).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(manyUids)
            }

            val issued = server.issued()
            // CAPABILITY first: every session asks whether the server knows the RFC 2971 ID
            // command before the caller says anything (#173). That answer is deliberately NOT
            // kept — see ImapSession.identify — so the second one here really is the delete's.
            assertEquals(listOf("CAPABILITY", """SELECT "Trash"""", "CAPABILITY"), issued.take(3))
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
            // No question to ask: UID EXPUNGE already names its victims.
            assertTrue("DELETED was searched: $issued", issued.none { it.startsWith("UID SEARCH") })
            // Each chunk flagged then erased, by the same set: nothing wider than what was named.
            assertEquals(issued.setsOf("UID STORE"), issued.setsOf("UID EXPUNGE"))
            assertEquals(manyUids, issued.uidsFlagged())
        }
    }

    /**
     * A `UID EXPUNGE` refused by a server that DOES advertise UIDPLUS is a failure, not a cue to
     */
    @Test
    fun `a refused UID EXPUNGE fails instead of falling back to the folder`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS\r\n$tag OK done\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID EXPUNGE") -> "$tag NO [SERVERBUG] not now\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("Trash")
                val failure = runCatching { session.delete(listOf(1L, 2L, 3L)) }.exceptionOrNull()
                assertTrue("expected an ImapException, got $failure", failure is ImapException)
            }

            assertEquals("a bare EXPUNGE went out: ${server.issued()}", 0, server.issued().bareExpunges())
        }
    }

    /** Nothing to destroy: no flagging, no question, no command at all. */
    @Test
    fun `deleting nothing asks the server nothing`() {
        scriptedServer(uidPlus = false).use { server ->
            server.session().use { it.delete(emptyList()) }

            // The CAPABILITY is the connect-time ID question (RFC 2971, #173), not the deletion.
            assertEquals(listOf("CAPABILITY", "LOGOUT"), server.issued())
        }
    }

    // ---- the capability list itself ----

    /** The usual case costs no round trip: the server volunteered its list at login. */
    @Test
    fun `capabilities advertised at login are used without a CAPABILITY command`() {
        scriptedServer(uidPlus = true, advertiseAtLogin = true).use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.delete(listOf(1L, 2L))
            }

            val issued = server.issued()
            assertTrue("a CAPABILITY round trip was made: $issued", issued.none { it.startsWith("CAPABILITY") })
            assertEquals(listOf("1:2"), issued.setsOf("UID EXPUNGE"))
        }
    }

    /** RFC 3501 §6.1.1: capability names are case-insensitive, wherever they are read. */
    @Test
    fun `a lower-case capability list is understood`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") -> "* capability imap4rev1 uidplus\r\n$tag OK done\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 2)
                else -> ok(tag)
            }
        }.use { server ->
            val supported = server.session().use { it.hasCapability("UIDPLUS") }

            assertTrue(supported)
        }
    }

    /**
     * The response-code form, read off the flattened line because `[` and `]` are not IMAP token
     * delimiters — the parser hands back `[CAPABILITY` and `UIDPLUS]` as atoms.
     */
    @Test
    fun `a CAPABILITY response code is read whole`() {
        val tagged = listOf("a1", "OK", "[CAPABILITY", "IMAP4rev1", "LITERAL+", "UIDPLUS]", "logged", "in")

        assertEquals(setOf("IMAP4REV1", "LITERAL+", "UIDPLUS"), capabilitiesInResponseCode(tagged))
    }

    /** A completion without the code answers nothing, so the question stays open for a query. */
    @Test
    fun `a login completion without a CAPABILITY code advertises nothing`() {
        assertNull(capabilitiesInResponseCode(listOf("a1", "OK", "logged", "in")))
    }

    /** A `CAPABILITY` answer with no list at all reads as "no optional extension". */
    @Test
    fun `an empty capability answer denies every extension`() {
        assertFalse(CAP_UIDPLUS in parseCapabilities(listOf(listOf("a1", "OK", "done"))))
    }

    // ---- move()'s copy fallback ----

    /**
     * The same fallback, the same danger: a server with neither MOVE nor UIDPLUS made the copy
     */
    @Test
    fun `a move falling back to copy spares a folder flagged by somebody else`() {
        scriptedServer(uidPlus = false, flaggedByOthers = listOf(9_000L)).use { server ->
            val mapping = server.session().use { session ->
                session.select("INBOX")
                session.move(manyUids, "Archive")
            }

            val issued = server.issued()
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
            assertTrue("UID EXPUNGE was attempted: $issued", issued.none { it.startsWith("UID EXPUNGE") })
            // The move itself still happened, in several chunks, and still reports its COPYUIDs.
            assertTrue("only one chunk: $issued", issued.setsOf("UID COPY").size > 1)
            assertEquals(manyUids, issued.uidsFlagged())
            assertEquals(manyUids.associateWith { it }, mapping.uids)
        }
    }

    /** Nothing foreign flagged: the copy fallback clears the originals with one `EXPUNGE`. */
    @Test
    fun `a move falling back to copy purges once when the folder is only ours`() {
        scriptedServer(uidPlus = false).use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.move(manyUids, "Archive")
            }

            val issued = server.issued()
            assertEquals("not exactly one EXPUNGE: $issued", 1, issued.bareExpunges())
            // `UID SEARCH DELETED` and not `UID SEARCH`: the identity probe a move now sends
            // BEFORE each chunk (`UID SEARCH UID <set>`, see MoveConfirmsWhatLeftTheSourceTest)
            // is a different question, asked of a set and not of the folder. What is pinned here
            // is that the FOLDER-WIDE one still belongs to the operation, not to the chunk.
            assertEquals(
                "DELETED was searched per chunk: $issued",
                1,
                issued.count { it.startsWith("UID SEARCH DELETED") },
            )
        }
    }

    /** With UIDPLUS the copy fallback erases only what it copied — one `UID EXPUNGE` per chunk. */
    @Test
    fun `a move falling back to copy erases by uid when the server can`() {
        scriptedServer(uidPlus = true, flaggedByOthers = listOf(9_000L)).use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.move(manyUids, "Archive")
            }

            val issued = server.issued()
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
            // No question to ask about the folder: `UID EXPUNGE` names its victims. (The
            // per-chunk `UID SEARCH UID <set>` of the move is another question entirely.)
            assertTrue("DELETED was searched: $issued", issued.none { it.startsWith("UID SEARCH DELETED") })
            assertEquals(issued.setsOf("UID COPY"), issued.setsOf("UID EXPUNGE"))
        }
    }

    /** A move the server performs itself flags nothing, so there is nothing to purge or ask. */
    @Test
    fun `a move the server performs itself asks nothing of the folder and purges nothing`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> "$tag OK [COPYUID 1 1:3 7:9] moved\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Archive")
            }

            val issued = server.issued()
            // Nothing is flagged, so nothing is asked about the FOLDER's `\Deleted` set and
            // nothing is purged. The `UID SEARCH UID 1:3` the move sends before the chunk is the
            // source-side observation of [ImapMoved.confirmedGone] and belongs to another file.
            assertTrue("DELETED was searched: $issued", issued.none { it.startsWith("UID SEARCH DELETED") })
            // Past the connect-time ID question (#173, index 0, kept by nobody): the MOVE
            // succeeded, so nothing behind it had a capability to ask.
            assertEquals(
                "the capability was asked for nothing: $issued",
                0,
                issued.drop(1).count { it.startsWith("CAPABILITY") },
            )
            assertEquals(0, issued.bareExpunges())
        }
    }

    /**
     * The fallback is for a server that has no MOVE extension, and for nothing else. A server that
     */
    @Test
    fun `a refused move is not answered with a copy when the server advertises MOVE`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") ->
                    "* CAPABILITY IMAP4rev1 LITERAL+ MOVE UIDPLUS\r\n$tag OK capability completed\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> "$tag NO [OVERQUOTA] over quota\r\n"
                line.startsWith("UID COPY") -> "$tag OK [COPYUID 1 1:3 7:9] copy completed\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val error = assertThrows(ImapException::class.java) {
                server.session().use { session ->
                    session.select("INBOX")
                    session.move(listOf(1L, 2L, 3L), "Archive")
                }
            }

            assertEquals("OVERQUOTA", error.responseCode)
            val issued = server.issued()
            assertTrue("the mail was copied: $issued", issued.none { it.startsWith("UID COPY") })
            assertTrue("the originals were flagged: $issued", issued.none { it.startsWith("UID STORE") })
            assertTrue("UID EXPUNGE went out: $issued", issued.none { it.startsWith("UID EXPUNGE") })
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
        }
    }

    /**
     * The other half of the same decision, and the path that must NOT move: no MOVE in the
     */
    @Test
    fun `a refused move still falls back to copy when the server does not advertise MOVE`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") ->
                    "* CAPABILITY IMAP4rev1 LITERAL+ UIDPLUS\r\n$tag OK capability completed\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> "$tag NO [OVERQUOTA] over quota\r\n"
                line.startsWith("UID COPY") -> "$tag OK [COPYUID 1 1:3 7:9] copy completed\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val mapping = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Archive")
            }

            val issued = server.issued()
            assertEquals("the copy did not go out: $issued", listOf("1:3"), issued.setsOf("UID COPY"))
            assertEquals(listOf(1L, 2L, 3L), issued.uidsFlagged())
            assertEquals(mapOf(1L to 7L, 2L to 8L, 3L to 9L), mapping.uids)
        }
    }

    /**
     * The socket died with the UID MOVE: no tagged completion was ever read, so the server was
     */
    @Test(timeout = 60_000)
    fun `a move cut mid-command takes no copy fallback on a refusal it never heard`() {
        FakeImapServer(
            responder = { tag, line ->
                when {
                    line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                    else -> ok(tag)
                }
            },
            cutAfter = { line -> if (line.startsWith("UID MOVE")) emptyList() else null },
        ).use { server ->
            assertThrows(ImapException::class.java) {
                server.session().use { session ->
                    session.select("INBOX")
                    session.move(listOf(1L, 2L, 3L), "Archive")
                }
            }
            server.awaitDone()

            val issued = server.issued()
            // Counted from the UID MOVE on: the question put at connect for the RFC 2971 ID
            // command (#173) sits ahead of it and is nobody's fallback probe.
            assertEquals(
                "the client went on asking a dead socket about MOVE, i.e. it was setting up the " +
                    "copy fallback on a refusal it never heard: $issued",
                0,
                issued.dropWhile { !it.startsWith("UID MOVE") }.count { it.startsWith("CAPABILITY") },
            )
            assertTrue("the mail was copied: $issued", issued.none { it.startsWith("UID COPY") })
            assertTrue("the originals were flagged: $issued", issued.none { it.startsWith("UID STORE") })
            assertTrue("UID EXPUNGE went out: $issued", issued.none { it.startsWith("UID EXPUNGE") })
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
        }
    }

    /**
     * The same cut, on the production shape: the capability list arrived with the LOGIN completion
     */
    @Test(timeout = 60_000)
    fun `a move cut mid-command takes no copy fallback when the login advertised no MOVE`() {
        FakeImapServer(
            responder = { tag, line ->
                when {
                    line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 LITERAL+ UIDPLUS] logged in\r\n"
                    line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                    else -> ok(tag)
                }
            },
            cutAfter = { line -> if (line.startsWith("UID MOVE")) emptyList() else null },
        ).use { server ->
            assertThrows(ImapException::class.java) {
                server.session().use { session ->
                    session.select("INBOX")
                    session.move(listOf(1L, 2L, 3L), "Archive")
                }
            }
            server.awaitDone()

            val issued = server.issued()
            assertTrue("the mail was copied: $issued", issued.none { it.startsWith("UID COPY") })
            assertTrue("the originals were flagged: $issued", issued.none { it.startsWith("UID STORE") })
            assertTrue("UID EXPUNGE went out: $issued", issued.none { it.startsWith("UID EXPUNGE") })
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
        }
    }

    /**
     * Not a refusal but a SILENCE. [ImapSession.command] wraps neither its write nor its read, so
     */
    @Test(timeout = 60_000)
    fun `a move that runs out its read budget is not answered with a copy`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") ->
                    "* CAPABILITY IMAP4rev1 LITERAL+ MOVE UIDPLUS\r\n$tag OK capability completed\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                // Never answered at all: the wedged server, the black-holed network.
                line.startsWith("UID MOVE") -> ""
                else -> ok(tag)
            }
        }.use { server ->
            val failure = server.session().use { session ->
                session.select("INBOX")
                runCatching {
                    session.withReadTimeout(200) { session.move(listOf(1L, 2L, 3L), "Archive") }
                }.exceptionOrNull()
            }
            server.awaitDone()

            val issued = server.issued()
            assertTrue("the mail was copied: $issued", issued.none { it.startsWith("UID COPY") })
            assertTrue("the originals were flagged: $issued", issued.none { it.startsWith("UID STORE") })
            assertTrue("UID EXPUNGE went out: $issued", issued.none { it.startsWith("UID EXPUNGE") })
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
            assertTrue("expected a read timeout, got $failure", failure is SocketTimeoutException)
        }
    }

    /**
     * The same silence, on the server this is developed against: one that publishes its capability
     */
    @Test(timeout = 60_000)
    fun `a move that goes unanswered takes no copy fallback when the login advertised no MOVE`() {
        FakeImapServer { tag, line ->
            when {
                // Production shape: the capability list travels in the LOGIN completion, so the
                // session cache is hot and no CAPABILITY command ever goes out.
                line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 LITERAL+ UIDPLUS] logged in\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                // Never answered at all: the wedged server, the black-holed network.
                line.startsWith("UID MOVE") -> ""
                line.startsWith("UID COPY") -> "$tag OK [COPYUID 1 1:3 7:9] copy completed\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val failure = server.session().use { session ->
                session.select("INBOX")
                runCatching {
                    session.withReadTimeout(200) { session.move(listOf(1L, 2L, 3L), "Archive") }
                }.exceptionOrNull()
            }
            server.awaitDone()

            val issued = server.issued()
            assertTrue("the capability was asked: $issued", issued.none { it.startsWith("CAPABILITY") })
            assertTrue("the mail was copied: $issued", issued.none { it.startsWith("UID COPY") })
            assertTrue("the originals were flagged: $issued", issued.none { it.startsWith("UID STORE") })
            assertTrue("UID EXPUNGE went out: $issued", issued.none { it.startsWith("UID EXPUNGE") })
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
            assertTrue("expected a read timeout, got $failure", failure is SocketTimeoutException)
        }
    }

    /**
     * The last unguarded corner of the decision, and the one the rewritten cut-socket tests above
     */
    @Test(timeout = 60_000)
    fun `a refused move takes no copy fallback when the capability probe is refused too`() {
        var moved = false
        var probesRefused = 0
        FakeImapServer { tag, line ->
            when {
                // No [CAPABILITY] on the LOGIN completion: the cache stays cold, so the fallback's
                // question really does go out on the wire and really can be refused.
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> { moved = true; "$tag NO [CANNOT] MOVE unsupported\r\n" }
                line.startsWith("CAPABILITY") && moved && probesRefused++ == 0 ->
                    "$tag NO [UNAVAILABLE] capability unavailable\r\n"
                line.startsWith("CAPABILITY") ->
                    "* CAPABILITY IMAP4rev1 LITERAL+ UIDPLUS\r\n$tag OK capability completed\r\n"
                line.startsWith("UID COPY") -> "$tag OK [COPYUID 1 1:3 7:9] copy completed\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val error = assertThrows(ImapException::class.java) {
                server.session().use { session ->
                    session.select("INBOX")
                    session.move(listOf(1L, 2L, 3L), "Archive")
                }
            }

            val issued = server.issued()
            // The MOVE's own refusal travels up, not the probe's: what the caller is told is why
            // the move failed, not why the question about it could not be put.
            assertEquals("CANNOT", error.responseCode)
            assertEquals(
                "the client asked a second capability question, i.e. it read an unanswerable " +
                    "probe as \"this server has no MOVE\" and went on setting up the copy: $issued",
                1,
                issued.dropWhile { !it.startsWith("UID MOVE") }.count { it.startsWith("CAPABILITY") },
            )
            assertTrue("the mail was copied: $issued", issued.none { it.startsWith("UID COPY") })
            assertTrue("the originals were flagged: $issued", issued.none { it.startsWith("UID STORE") })
            assertTrue("UID EXPUNGE went out: $issued", issued.none { it.startsWith("UID EXPUNGE") })
            assertEquals("a bare EXPUNGE went out: $issued", 0, issued.bareExpunges())
        }
    }
}
