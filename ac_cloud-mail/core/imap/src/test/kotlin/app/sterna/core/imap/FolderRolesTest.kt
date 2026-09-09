package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * The role a LIST gives each folder, on servers that never send a SPECIAL-USE attribute.
 *
 * What is at stake is mail, not tidiness: with no folder carrying the "trash" role,
 * `MailRepository.deleteWouldDestroy` answers "this delete destroys" for EVERY message, and the
 * delete swipe erases instead of moving. A German, Polish or Russian mailbox on a server without
 * RFC 6154 got exactly that.
 *
 * Every expectation here is a LITERAL. Nothing recomputes the rule the code applies, or the
 * shipped condition could be inverted and this file would still pass.
 */
class FolderRolesTest {

    /** Parse each raw "* LIST ..." line and run it through the real folder mapper. */
    private fun folders(vararg lines: String): List<ImapFolder> =
        parseListFolders(lines.map { ImapParser(ByteArrayInputStream(it.toByteArray(Charsets.UTF_8))).readResponse() })

    /** A server that answers a plain LIST: no special-use attribute anywhere, only names. */
    private fun mute(vararg names: String) =
        folders(*names.map { "* LIST (\\HasNoChildren) \".\" \"$it\"\r\n" }.toTypedArray())

    private fun roles(vararg names: String) = mute(*names).map { it.role }

    // ── the nine locales, trash and junk first: the two roles whose absence destroys or pollutes ──

    @Test fun english() =
        assertEquals(listOf("trash", "junk", "sent", "drafts", "archive"), roles("Trash", "Junk", "Sent", "Drafts", "Archive"))

    @Test fun german() =
        assertEquals(
            listOf("trash", "junk", "sent", "drafts", "archive"),
            roles("Papierkorb", "Junk-E-Mail", "Gesendet", "Entw&APw-rfe", "Archiv"),
        )

    @Test fun spanish() =
        assertEquals(
            listOf("trash", "junk", "sent", "drafts", "archive"),
            roles("Papelera", "Correo no deseado", "Elementos enviados", "Borradores", "Archivo"),
        )

    @Test fun french() =
        assertEquals(
            listOf("trash", "junk", "sent", "drafts", "archive"),
            roles("Corbeille", "Courrier ind&AOk-sirable", "Envoy&AOk-s", "Brouillons", "Archives"),
        )

    @Test fun italian() =
        assertEquals(
            listOf("trash", "junk", "sent", "drafts", "archive"),
            roles("Cestino", "Posta indesiderata", "Posta inviata", "Bozze", "Archivio"),
        )

    @Test fun dutch() =
        assertEquals(
            listOf("trash", "junk", "sent", "drafts", "archive"),
            roles("Prullenbak", "Ongewenste e-mail", "Verzonden items", "Concepten", "Archief"),
        )

    @Test fun polish() =
        assertEquals(
            listOf("trash", "junk", "sent", "drafts", "archive"),
            roles("Kosz", "Wiadomo&AVs-ci-&AVs-mieci", "Wys&AUI-ane", "Kopie robocze", "Archiwum"),
        )

    @Test fun portuguese() =
        assertEquals(
            listOf("trash", "junk", "sent", "drafts", "archive"),
            roles("Lixeira", "Lixo Eletr&APQ-nico", "Itens enviados", "Rascunhos", "Arquivo"),
        )

    @Test fun russian() =
        assertEquals(
            listOf("trash", "junk", "sent", "drafts", "archive"),
            roles(
                "&BBoEPgRABDcEOAQ9BDA-", // Корзина
                "&BCEEPwQwBDw-", // Спам
                "&BB4EQgQ,BEAEMAQyBDsENQQ9BD0ESwQ1-", // Отправленные
                "&BCcENQRABD0EPgQyBDgEOgQ4-", // Черновики
                "&BBAEQARFBDgEMg-", // Архив
            ),
        )

    /**
     * The English table as it stood before the other eight locales were added to it.
     *
     * One folder per listing, because these are SYNONYMS: no server carries "Trash" and "Deleted
     * Items" at once, and a single listing holding both would be measuring the anti-duplicate
     * guard instead of the table.
     */
    @Test fun theEnglishTableStillAnswersExactlyWhatItAnsweredBefore() {
        val names = listOf(
            "INBOX", "Sent", "Sent Mail", "Sent Items", "Drafts",
            "Trash", "Deleted", "Deleted Items", "Junk", "Spam", "Archive", "Archives",
        )
        assertEquals(
            listOf("inbox", "sent", "sent", "sent", "drafts", "trash", "trash", "trash", "junk", "junk", "archive", "archive"),
            names.map { roles(it).single() },
        )
    }

    /** A name in no table is nobody's special folder, and MUST keep a null role: that is what
     *  keeps it searchable and renameable (`SearchableFoldersTest`). */
    @Test fun anOrdinaryFolderKeepsNoRole() =
        assertEquals(listOf(null, null), roles("Facturen", "&BB8EQgQ4BEcEOgQ4-")) // Птички

    // ─────────────────────────── the two list-wide guards ───────────────────────────

    /**
     * A role the SERVER stated cannot be stolen by a role GUESSED from a name — whichever order
     * they were listed in. The user folder "Архив" arriving first must not take "archive" from
     * the mailbox the server itself marked `\Archive`, or archiving files mail into a folder the
     * server does not consider an archive.
     */
    @Test fun aStatedAttributeBeatsAGuessedNameWhicheverComesFirst() {
        assertEquals(
            listOf(null, "archive"),
            folders(
                "* LIST (\\HasNoChildren) \".\" \"&BBAEQARFBDgEMg-\"\r\n", // Архив, a user folder
                "* LIST (\\Archive \\HasNoChildren) \".\" \"Archief\"\r\n",
            ).map { it.role },
        )
        assertEquals(
            listOf("archive", null),
            folders(
                "* LIST (\\Archive \\HasNoChildren) \".\" \"Archief\"\r\n",
                "* LIST (\\HasNoChildren) \".\" \"&BBAEQARFBDgEMg-\"\r\n", // Архив
            ).map { it.role },
        )
    }

    /**
     * Two candidates for the same role: ONE carries it. Two folders with role "trash" put two
     * Trashes in the drawer and let `roleMailboxId` pick either — the deleted message lands in
     * whichever the query returned first, i.e. not the one the user emptied.
     */
    @Test fun asecondCandidateForATakenRoleGetsNothing() {
        assertEquals(listOf("trash", null), roles("Trash", "&BBoEPgRABDcEOAQ9BDA-")) // Trash, Корзина
        assertEquals(listOf("trash", null), roles("&BBoEPgRABDcEOAQ9BDA-", "Trash")) // Корзина, Trash
    }

    /**
     * Same for a role stated twice: the drawer needs ONE trash, not the last one listed.
     *
     * The loser is deliberately named "Archiv" — a name the table reads as ANOTHER role. A folder
     * the server itself labelled `\Trash` must not be re-read as the archive because the trash
     * was already taken: the app would then file archived mail into a folder that gets purged.
     * With a loser named "Old Trash" this case is blind, since that name guesses nothing anyway.
     */
    @Test fun aRoleStatedTwiceIsCarriedOnceAndTheLoserIsNotRenamedByItsName() {
        assertEquals(
            listOf("trash", null),
            folders(
                "* LIST (\\Trash) \".\" \"Trash\"\r\n",
                "* LIST (\\Trash) \".\" \"Archiv\"\r\n",
            ).map { it.role },
        )
    }

    /**
     * The same two guards on the decision itself, with the listing spelled out as arguments
     * instead of wire lines: one call, one literal answer, so a reader can see exactly what was
     * handed in and what came back. Attributes and names are deliberately mixed and out of order.
     */
    @Test fun theDecisionTakesTheWholeListAndAnswersOneRolePerFolder() {
        assertEquals(
            listOf(null, "trash", null, "archive", "junk", null),
            assignRoles(sixFolderListing()).map { it.elected },
        )
    }

    // ─────────────────────── the claim that lost, reported and not thrown away ───────────────────
    //
    // A folder that lost a role is an ordinary folder in the drawer, and that is right. But a
    // SECOND TRASH is not ordinary mail: an account with a personal "Trash" and a shared
    // "Shared/…/Trash" has two, the loser was left role-less, and a role-less folder is searched
    // and crawled into the local index — thrown-away mail came back in every search result and
    // survived a cache purge. So the decision reports WHAT was claimed and lost; `:core:data` is
    // what does something with it. Nothing here knows which roles a search skips.

    @Test fun theSameListingSaysWhichClaimEachLoserLost() {
        assertEquals(
            listOf("trash", null, "archive", null, null, "junk"),
            assignRoles(sixFolderListing()).map { it.unelected },
        )
    }

    @Test fun aSecondStatedTrashReportsTheTrashItClaimed() {
        // The server itself marked both `\Trash`. The second is elected to nothing, and what it
        // claimed is the trash — not the "archive" its name would have guessed (rule 1 stands).
        val loser = folders(
            "* LIST (\\Trash) \".\" \"Trash\"\r\n",
            "* LIST (\\Trash) \".\" \"Archiv\"\r\n",
        )[1]
        assertEquals(null, loser.role)
        assertEquals("trash", loser.unelectedRole)
    }

    @Test fun aSecondStatedJunkReportsTheJunkItClaimed() {
        // The same branch, on the OTHER role a lost claim is acted on. A server that states
        // SPECIAL-USE on a personal `Junk` and on the junk of a shared mailbox: restrict the
        // reporting of a stated-but-unelected claim to the trash and this is what stops being
        // signalled — the shared spam folder goes back to being searched and crawled into the
        // index, where a cache purge cannot clear it.
        val listed = folders(
            "* LIST (\\Junk) \".\" \"Junk\"\r\n",
            "* LIST (\\Junk) \".\" \"Shared/team/Junk\"\r\n",
        )
        assertEquals(listOf("junk", null), listed.map { it.role })
        assertEquals(listOf(null, "junk"), listed.map { it.unelectedRole })
    }

    @Test fun aSecondTrashGuessedFromItsNameReportsTheTrashItClaimed() {
        // A server mute on SPECIAL-USE: both are read by the name table, and only the first is
        // elected. German, because that is where the name table earns its keep.
        val listed = mute("Papierkorb", "Papierkorb")
        assertEquals(listOf("trash", null), listed.map { it.role })
        assertEquals(listOf(null, "trash"), listed.map { it.unelectedRole })
    }

    @Test fun aSecondJunkReportsTheJunkItClaimed() {
        val listed = mute("Junk", "Spam")
        assertEquals(listOf("junk", null), listed.map { it.role })
        assertEquals(listOf(null, "junk"), listed.map { it.unelectedRole })
    }

    @Test fun aFolderThatWonItsRoleOrClaimedNothingReportsNoLostClaim() {
        // The witness that keeps the case above from passing on a field that is simply always set.
        assertEquals(
            listOf(null, null, null),
            mute("Trash", "Papierkorb-Archiv 2019", "Projekte").map { it.unelectedRole },
        )
        assertEquals(listOf("trash", null, null), mute("Trash", "Papierkorb-Archiv 2019", "Projekte").map { it.role })
    }

    /** The listing of [theDecisionTakesTheWholeListAndAnswersOneRolePerFolder], read twice: once
     *  for the role each folder was elected to, once for the claim each loser lost. */
    private fun sixFolderListing() = listOf(
        listing("Корзина"), // guessed trash, but the server states one below
        listing("Vieux courrier", "\\Trash"),
        listing("Archiwum"), // guessed archive, but the server states one below
        listing("Oude post", "\\Archive", "\\HasNoChildren"),
        listing("Wiadomości-śmieci"), // nothing states a junk: the name gets it
        listing("Spam"), // second junk candidate, and junk is taken
    )

    private fun listing(name: String, vararg attrs: String) =
        ListedFolder(name = name, path = name, delimiter = ".", attrs = attrs.toList())

    // ──────────────────────────── the name that reaches the table ────────────────────────────

    /**
     * What the table reads is the LEAF, never the whole path. Nothing in this file is flat by
     * accident: Dovecot's default namespace prefixes every mailbox with "INBOX.", Courier and
     * Gmail nest too, and on those servers a table fed with the path matches nothing at all —
     * no trash role, and the delete swipe is back to destroying mail.
     */
    @Test fun theLeafDecidesTheRoleNotTheWholePath() {
        val dovecot = folders("* LIST (\\HasNoChildren) \".\" \"INBOX.&BBoEPgRABDcEOAQ9BDA-\"\r\n") // INBOX.Корзина
        assertEquals(listOf("trash"), dovecot.map { it.role })
        assertEquals(listOf("Корзина"), dovecot.map { it.name })
        assertEquals(listOf("INBOX.Корзина"), dovecot.map { it.path }) // the identifier keeps the prefix

        assertEquals(
            listOf("trash"),
            folders("* LIST (\\HasNoChildren) \"/\" \"[Gmail]/Papierkorb\"\r\n").map { it.role },
        )
    }

    /**
     * The Cyrillic name has to survive [stripBidiAndControls] character for character, in both
     * forms a server sends it: conforming modified UTF-7, and the raw UTF-8 an older client wrote
     * into the mailbox name. A single character eaten on the way and no table would ever match.
     */
    @Test fun aCyrillicNameArrivesIntact() {
        assertEquals(listOf("Корзина", "Спам"), mute("&BBoEPgRABDcEOAQ9BDA-", "&BCEEPwQwBDw-").map { it.name })
        assertEquals(listOf("Корзина"), mute("Корзина").map { it.name })
        assertEquals(listOf("trash"), roles("Корзина")) // the raw-UTF-8 path reaches the table too
    }

    /**
     * The comparison must not depend on the phone's language. `lowercase()` is locale-independent
     * by contract; `toLowerCase()` was not, and under a Turkish default locale it maps 'I' to 'ı',
     * so "INBOX" would stop being the inbox on a Turkish phone talking to an English server.
     */
    @Test fun theTableDoesNotReadThePhonesLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals(listOf("inbox", "trash", "junk"), roles("INBOX", "TRASH", "JUNK"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
