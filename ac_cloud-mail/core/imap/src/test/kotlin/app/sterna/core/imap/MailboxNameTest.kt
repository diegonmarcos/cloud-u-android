package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-ASCII folder names, both ways (Codeberg #101).
 */
class MailboxNameTest {

    // Wire forms, as a server really announces them.
    private val flagged = "&BB8EPgQ8BDUERwQ1BD0EPQRLBDU-" to "Помеченные"
    private val sent = "&BB4EQgQ,BEAEMAQyBDsENQQ9BD0ESwQ1-" to "Отправленные" // note the ',' — base64 '/'
    private val hello = "&BB8EQAQ4BDIENQRC-" to "Привет"

    // ---- the encoding itself ---------------------------------------------------------------

    @Test fun `a Cyrillic folder name is decoded for display`() {
        assertEquals(flagged.second, decodeModifiedUtf7(flagged.first))
        assertEquals(sent.second, decodeModifiedUtf7(sent.first))
    }

    @Test fun `a decoded name encodes back to the exact bytes the server sent`() {
        for ((wire, _) in listOf(flagged, sent, hello)) {
            assertEquals(wire, encodeModifiedUtf7(decodeModifiedUtf7(wire)))
        }
    }

    /** The compatibility guarantee: an English folder produces the bytes it always did. */
    @Test fun `an ASCII name is untouched in both directions`() {
        for (name in listOf("INBOX", "Archive", "Sent Items", "[Gmail]/All Mail", "a.b.c")) {
            assertEquals(name, encodeModifiedUtf7(name))
            assertEquals(name, decodeModifiedUtf7(name))
        }
    }

    /** `&` is the shift character, so a literal one is `&-`. */
    @Test fun `an ampersand in a folder name survives the round trip`() {
        assertEquals("R&-D", encodeModifiedUtf7("R&D"))
        assertEquals("R&D", decodeModifiedUtf7("R&-D"))
        assertEquals("&-", encodeModifiedUtf7("&"))
        assertEquals("&", decodeModifiedUtf7("&-"))
    }

    /**
     * The hierarchy delimiter is never encoded and never swallowed: `/` and `.` are outside the
     */
    @Test fun `the hierarchy delimiter stays a delimiter`() {
        assertEquals("INBOX/&BB8EQAQ4BDIENQRC-", encodeModifiedUtf7("INBOX/Привет"))
        assertEquals("INBOX/Привет", decodeModifiedUtf7("INBOX/&BB8EQAQ4BDIENQRC-"))
        assertEquals("INBOX.Привет.Ж", decodeModifiedUtf7("INBOX.&BB8EQAQ4BDIENQRC-.&BBY-"))
        // Unterminated shift, delimiter right after: the shift ends AT the delimiter.
        assertEquals("Привет/x", decodeModifiedUtf7("&BB8EQAQ4BDIENQRC/x"))
    }

    /** A malformed sequence must survive AS TEXT: a name is an identifier, losing part of it
     *  would address a different mailbox — or none. */
    @Test fun `a malformed encoding survives as text instead of vanishing`() {
        assertEquals("&12-", decodeModifiedUtf7("&12-")) // 2 base64 chars = 1 byte: not UTF-16
        assertEquals("&AB!", decodeModifiedUtf7("&AB!"))
        assertEquals("x&", decodeModifiedUtf7("x&"))
        assertEquals("&Zz", decodeModifiedUtf7("&Zz"))
    }

    /**
     * …and surviving as text is NOT enough on its own. Those decodings all contain a literal `&`,
     */
    @Test fun `a name that would not encode back keeps its wire form`() {
        for (raw in listOf("x&", "&12-", "&AB!", "&Zz", "R&D")) {
            assertEquals("$raw must not be decoded", raw, decodeMailboxPath(raw))
        }
    }

    @Test fun `a well-formed name is still decoded`() {
        assertEquals(flagged.second, decodeMailboxPath(flagged.first))
        assertEquals("R&D", decodeMailboxPath("R&-D"))
        assertEquals("INBOX/Привет", decodeMailboxPath("INBOX/&BB8EQAQ4BDIENQRC-"))
    }

    /**
     * End to end, and the residual corner the pair cannot resolve on its own: a folder whose wire
     */
    @Test fun `an undecodable folder still opens, on the second try`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("LIST") -> "* LIST () \"/\" \"R&D\"\r\n" + ok(tag)
                // The folder really is called `R&D` on the wire, so the encoded form misses.
                line == """SELECT "R&-D"""" -> "$tag NO [NONEXISTENT] no such mailbox\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 2)
                else -> ok(tag)
            }
        }.use { server ->
            val (folder, status) = server.session().use { session ->
                val listed = session.listFolders().first()
                listed to session.select(listed.path)
            }

            assertEquals("R&D", folder.path)
            assertEquals(2, status.exists)
            assertEquals(
                listOf("""SELECT "R&-D"""", """SELECT "R&D""""),
                server.issued().filter { it.startsWith("SELECT") },
            )
        }
    }

    /** A conformant name costs no second command: the first form is the right one. */
    @Test fun `a well-formed name is selected once`() {
        folderServer(flagged.first).use { server ->
            server.session().use { session -> session.select(session.listFolders().first().path) }

            assertEquals(1, server.issued().count { it.startsWith("SELECT") })
        }
    }

    /**
     * A non-ASCII name has exactly ONE wire form, so a refusal there means what it says: no
     * second SELECT, and above all no raw UTF-8 put on the wire after a failure.
     */
    @Test fun `a refused Cyrillic folder is not retried verbatim`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> "$tag NO [NOPERM] denied\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                val failure = runCatching { session.select(flagged.second) }.exceptionOrNull()
                assertTrue("expected the refusal to propagate, got $failure", failure is ImapException)
            }

            assertEquals(
                listOf("""SELECT "${flagged.first}""""),
                server.issued().filter { it.startsWith("SELECT") },
            )
        }
    }

    @Test fun `a surrogate pair and other planes make the round trip`() {
        for (name in listOf("日本語", "café", "☺", "emoji 😀 folder")) {
            assertEquals(name, decodeModifiedUtf7(encodeModifiedUtf7(name)))
        }
    }

    // ---- on the wire ------------------------------------------------------------------------

    /** Answers LIST with one Cyrillic folder and accepts everything else. */
    private fun folderServer(vararg wireNames: String, delimiter: String = "/") = FakeImapServer { tag, line ->
        when {
            line.startsWith("LIST") ->
                wireNames.joinToString("") { "* LIST () \"$delimiter\" \"$it\"\r\n" } + ok(tag)
            line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
            line.startsWith("UID MOVE") -> "$tag OK [COPYUID 1 1 1] moved\r\n"
            else -> ok(tag)
        }
    }

    // ---- a name that is not modified UTF-7 at all --------------------------------------------

    /** The wire bytes of [name] in raw UTF-8, as the byte container a parsed token is. */
    private fun rawUtf8(name: String): String =
        String(name.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    private fun listing(nameToken: String) = FakeImapServer { tag, line ->
        when {
            line.startsWith("LIST") -> "* LIST () \"/\" $nameToken\r\n" + ok(tag)
            else -> ok(tag)
        }
    }

    /**
     * A folder whose name travels as RAW UTF-8 rather than modified UTF-7 — not hypothetical,
     * Sterna itself created such folders up to 1.4.3, which is why [select] carries a retry for
     * them. The parser hands those octets over as octets, so the DISPLAY name has to be read
     * before it is shown; without that, `stripBidiAndControls` eats the U+0080–U+009F half of
     * every UTF-8 continuation byte and the drawer shows truncated mojibake.
     *
     * What is NOT claimed: such a folder becomes openable. It never was — its path can only be
     * re-encoded as modified UTF-7, which is not what the server called it, and `select` refuses
     * rather than guess. This is a display fix and nothing more, which is why `path` is asserted
     * to still be the server's exact bytes.
     */
    @Test fun `a raw UTF-8 folder name delivered as a literal is displayed as text`() {
        val wire = rawUtf8(flagged.second)
        listing("{${wire.length}}\r\n$wire").use { server ->
            val folder = server.session().use { it.listFolders().first() }

            assertEquals(flagged.second, folder.name)
            assertEquals("the identifier must stay the server's bytes", wire, folder.path)
        }
    }

    /** The same name in the quoted form, which is what most servers send. Quoted and atom tokens
     *  have always been read one char per byte, so this one was mojibake before too. */
    @Test fun `a raw UTF-8 folder name delivered quoted is displayed as text`() {
        val wire = rawUtf8(flagged.second)
        listing("\"$wire\"").use { server ->
            val folder = server.session().use { it.listFolders().first() }

            assertEquals(flagged.second, folder.name)
            assertEquals(wire, folder.path)
        }
    }

    /**
     * THE GUARD, and the reason the wire form is what gets tested rather than the decoded one: a
     * legitimately encoded name whose characters all happen to sit in 0x80–0xFF must NOT be
     * re-read. `&AMMAqQ-` really is a folder called "Ã©", and its latin-1 bytes happen to be
     * valid UTF-8 for "é" — reinterpreting it would rename it on screen.
     */
    @Test fun `a properly encoded name is never reinterpreted`() {
        listing("\"&AMMAqQ-\"").use { server ->
            val folder = server.session().use { it.listFolders().first() }

            assertEquals("Ã©", folder.name)
            assertEquals("Ã©", folder.path)
        }
    }

    @Test fun `a Cyrillic folder is listed decoded and selected re-encoded`() {
        folderServer(flagged.first).use { server ->
            val folders = server.session().use { session ->
                val listed = session.listFolders()
                session.select(listed.first().path)
                listed
            }

            assertEquals(listOf(flagged.second), folders.map { it.name })
            assertEquals(listOf(flagged.second), folders.map { it.path })
            // THE point of the pair: what goes back out is what the server announced.
            assertTrue(
                "SELECT did not carry the wire form: ${server.issued()}",
                """SELECT "${flagged.first}"""" in server.issued(),
            )
        }
    }

    @Test fun `a nested Cyrillic folder keeps its path and shows only its leaf`() {
        folderServer("INBOX/${flagged.first}").use { server ->
            val folder = server.session().use { it.listFolders().first() }

            assertEquals("INBOX/${flagged.second}", folder.path)
            assertEquals(flagged.second, folder.name)
        }
    }

    /** Creating or renaming a Russian folder sent raw UTF-8 before this — the live defect. */
    @Test fun `create, rename and delete carry the encoded name`() {
        folderServer().use { server ->
            server.session().use { session ->
                session.createFolder("Привет")
                session.renameFolder("Привет", "INBOX/Помеченные")
                session.deleteFolder("INBOX/Помеченные")
            }

            assertEquals(
                listOf(
                    // Every session opens by asking whether the server knows the RFC 2971 ID
                    // command (#173); this fixture answers without it, so nothing is named.
                    "CAPABILITY",
                    """CREATE "${hello.first}"""",
                    // The SUBSCRIBE that follows a CREATE (#174) is here for the same reason as
                    // the line above it: it names a mailbox, so it must carry the wire form.
                    """SUBSCRIBE "${hello.first}"""",
                    """RENAME "${hello.first}" "INBOX/${flagged.first}"""",
                    """DELETE "INBOX/${flagged.first}"""",
                    "LOGOUT",
                ),
                server.issued(),
            )
        }
    }

    @Test fun `a move encodes its destination`() {
        folderServer().use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(4L), "Помеченные")
            }

            assertTrue(
                "the destination was not encoded: ${server.issued()}",
                """UID MOVE 4 "${flagged.first}"""" in server.issued(),
            )
        }
    }

    /** The easy one to miss: the COPY fallback names the destination a second time. */
    @Test fun `the copy fallback encodes its destination too`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS\r\n$tag OK done\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID MOVE") -> "$tag NO [CANNOT] MOVE unsupported\r\n"
                line.startsWith("UID COPY") -> "$tag OK [COPYUID 1 4 9] copied\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(4L), "Помеченные")
            }

            assertTrue(
                "the COPY destination was not encoded: ${server.issued()}",
                """UID COPY 4 "${flagged.first}"""" in server.issued(),
            )
        }
    }

    @Test fun `append encodes its mailbox`() {
        var appendTag = ""
        FakeImapServer { tag, line ->
            when {
                line.startsWith("APPEND") -> { appendTag = tag; "+ ready\r\n" }
                else -> if (appendTag.isNotEmpty() && !line.startsWith("LOGOUT")) {
                    "$appendTag OK appended\r\n".also { appendTag = "" }
                } else {
                    ok(tag)
                }
            }
        }.use { server ->
            server.session().use { it.append("Помеченные", "X", "\\Seen") }

            assertTrue(
                "APPEND did not carry the wire form: ${server.issued()}",
                server.issued().any { it.startsWith("""APPEND "${flagged.first}"""") },
            )
        }
    }

    /** A folder name with a control character is shown filtered but ADDRESSED faithfully:
     *  filtering the path would change which mailbox it names. */
    @Test fun `the display name is filtered while the path stays addressable`() {
        val spoofed = "Ma\u202Eil" // RIGHT-TO-LEFT OVERRIDE, the display-spoofing character
        val wire = encodeModifiedUtf7(spoofed)
        folderServer(wire).use { server ->
            val folder = server.session().use { session ->
                val listed = session.listFolders().first()
                session.select(listed.path)
                listed
            }

            assertEquals("Mail", folder.name)
            assertEquals(spoofed, folder.path)
            assertTrue("""SELECT "$wire"""" in server.issued())
        }
    }
}
