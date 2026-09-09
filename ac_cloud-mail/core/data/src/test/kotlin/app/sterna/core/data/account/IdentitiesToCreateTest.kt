package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [identitiesToCreate] and [mayCreateIdentities] EXECUTED, never re-derived: every case below pins
 */
class IdentitiesToCreateTest {

    private fun identity(email: String, name: String = "", id: String = email) =
        StoredIdentity(id = id, name = name, email = email)

    /**
     * [storedServerIdentities] is the account's own [StoredAccount.serverIdentities] field: written
     */
    private fun account(
        protocol: MailProtocol = MailProtocol.JMAP,
        loginId: String? = null,
        identities: List<StoredIdentity> = emptyList(),
        username: String = "a@x.test",
        storedServerIdentities: List<StoredIdentity> = listOf(identity("stored.only@x.test")),
    ) = StoredAccount(
        id = "acc1",
        server = "mail.example.test",
        username = username,
        loginId = loginId,
        protocol = protocol,
        identities = identities,
        serverIdentities = storedServerIdentities,
    )

    private fun emails(list: List<StoredIdentity>) = list.map { it.email }

    @Test fun `only the address the server does not already know is created`() {
        val before = account()

        val out = identitiesToCreate(
            before,
            listOf(identity("a@x.test"), identity("b@x.test")),
            serverIdentities = listOf(identity("a@x.test")),
        )

        assertEquals(listOf("b@x.test"), emails(out))
    }

    /**
     * THE case #172 died on, measured on the S7 on 2026-08-31: an account added minutes ago, its
     */
    @Test fun `a brand new address leaves when the read server list holds nothing`() {
        val before = account()

        val out = identitiesToCreate(before, listOf(identity("brand.new@x.test")), serverIdentities = emptyList())

        assertEquals(listOf("brand.new@x.test"), emails(out))
    }

    /**
     * The read FAILED (offline, dead DNS, a server that answered an error): null, and not one
     */
    @Test fun `a server list that could not be read creates nothing`() {
        val before = account()

        val out = identitiesToCreate(before, listOf(identity("brand.new@x.test")), serverIdentities = null)

        assertEquals(emptyList<String>(), emails(out))
    }

    /**
     * And the stored field cannot stand in for the failed read. It is stale by construction (only
     */
    @Test fun `a stored server list is no fallback for a read that failed`() {
        val before = account(storedServerIdentities = listOf(identity("stored.only@x.test")))

        val out = identitiesToCreate(before, listOf(identity("brand.new@x.test")), serverIdentities = null)

        assertEquals(emptyList<String>(), emails(out))
    }

    /**
     * The mirror image: an address the FRESH read holds, which the stored field does not. It must
     */
    @Test fun `an address the fresh read holds is not created, though the stored field lacks it`() {
        val before = account(storedServerIdentities = emptyList())

        val out = identitiesToCreate(
            before,
            listOf(identity("known@x.test")),
            serverIdentities = listOf(identity("known@x.test")),
        )

        assertEquals(emptyList<String>(), emails(out))
    }

    @Test fun `a manual identity already stored is never re-created`() {
        val before = account(identities = listOf(identity("c@x.test")))

        val out = identitiesToCreate(before, listOf(identity("c@x.test")), serverIdentities = emptyList())

        assertEquals(emptyList<String>(), emails(out))
    }

    @Test fun `an IMAP account creates nothing`() {
        val before = account(protocol = MailProtocol.IMAP)

        val out = identitiesToCreate(before, listOf(identity("new@x.test")), serverIdentities = emptyList())

        assertEquals(emptyList<String>(), emails(out))
    }

    @Test fun `a linked sub-account creates nothing`() {
        val before = account(loginId = "login1")

        val out = identitiesToCreate(before, listOf(identity("new@x.test")), serverIdentities = emptyList())

        assertEquals(emptyList<String>(), emails(out))
    }

    @Test fun `an implausible address is dropped`() {
        val before = account()

        val out = identitiesToCreate(
            before,
            listOf(
                identity("", id = "blank"),
                identity("noatsign.test", id = "noat"),
                identity("has space@x.test", id = "space"),
                identity("two@@x.test", id = "twoat"),
                identity("@x.test", id = "nolocal"),
                identity("d@x.test", id = "good"),
            ),
            serverIdentities = listOf(identity("a@x.test")),
        )

        assertEquals(listOf("d@x.test"), emails(out))
    }

    @Test fun `the same address twice in one save is created once`() {
        val before = account()

        val out = identitiesToCreate(
            before,
            listOf(identity("Dup@x.test", id = "r1"), identity("dup@x.test ", id = "r2")),
            serverIdentities = listOf(identity("a@x.test")),
        )

        assertEquals(listOf("Dup@x.test"), emails(out))
    }

    @Test fun `retyping a stored row to a new address creates the new one`() {
        val before = account(identities = listOf(identity("old@x.test", id = "row1")))

        val out = identitiesToCreate(
            before,
            listOf(identity("new@x.test", id = "row1")),
            serverIdentities = listOf(identity("a@x.test")),
        )

        assertEquals(listOf("new@x.test"), emails(out))
    }

    /**
     * Case and surrounding blanks are folded on BOTH sides of the comparison. Reading the addresses
     */
    @Test fun `a known address differing only in case or blanks is not re-created`() {
        val before = account()

        val out = identitiesToCreate(
            before,
            listOf(identity("  alex@x.test  ")),
            serverIdentities = listOf(identity("Alex@x.test")),
        )

        assertEquals(emptyList<String>(), emails(out))
    }

    @Test fun `the name typed for a new identity travels with it`() {
        val before = account()

        val out = identitiesToCreate(
            before,
            listOf(identity("e@x.test", name = "Iris Work")),
            serverIdentities = listOf(identity("a@x.test")),
        )

        assertEquals(listOf("Iris Work"), out.map { it.name })
    }

    // ── the row the EDITOR makes up on a freshly added account ─────────────────────────

    /**
     * THE case this guard exists for. On an account with no manual identity stored, the account
     */
    @Test fun `the row the editor makes up on a brand new account is not created`() {
        val before = account()

        val out = identitiesToCreate(before, listOf(identity(before.username)), serverIdentities = emptyList())

        assertEquals(emptyList<String>(), emails(out))
    }

    /**
     * ...and #172's own gesture still works on that same account: the user keeps the fabricated row
     * and adds her alias under it. Only the alias leaves.
     */
    @Test fun `an alias added beside the fabricated row still leaves`() {
        val before = account()

        val out = identitiesToCreate(
            before,
            listOf(identity(before.username), identity("alias@x.test")),
            serverIdentities = emptyList(),
        )

        assertEquals(listOf("alias@x.test"), emails(out))
    }

    /**
     * The damage, case 1: the server DOES hold identities, but not under the login address (login
     */
    @Test fun `the fabricated row is not created even when the server list lacks the login address`() {
        val before = account()

        val out = identitiesToCreate(
            before,
            listOf(identity(before.username)),
            serverIdentities = listOf(identity("other@x.test")),
        )

        assertEquals(emptyList<String>(), emails(out))
    }

    /**
     * The other side of the same condition, and the reason it is `before.identities.isEmpty()` and
     */
    @Test fun `the login address typed on an account that already has an identity is created`() {
        val before = account(identities = listOf(identity("c@x.test")))

        val out = identitiesToCreate(
            before,
            listOf(identity("c@x.test"), identity(before.username)),
            serverIdentities = emptyList(),
        )

        assertEquals(listOf("a@x.test"), emails(out))
    }

    /**
     * ...and the guard on it is `before.identities.isEmpty()` ALONE. Widening it with
     */
    @Test fun `the typed login address leaves even when the frozen server field is empty`() {
        val before = account(identities = listOf(identity("c@x.test")), storedServerIdentities = emptyList())

        val out = identitiesToCreate(
            before,
            listOf(identity("c@x.test"), identity(before.username)),
            serverIdentities = emptyList(),
        )

        assertEquals(listOf("a@x.test"), emails(out))
    }

    /** The login address is folded for case and blanks, like every other side of the comparison. */
    @Test fun `the login address is matched whatever the case`() {
        val storedUpper = account(username = "Iris@X.test")
        val storedLower = account(username = "iris@x.test")

        assertEquals(
            emptyList<String>(),
            emails(identitiesToCreate(storedUpper, listOf(identity("iris@x.test")), emptyList())),
        )
        assertEquals(
            emptyList<String>(),
            emails(identitiesToCreate(storedLower, listOf(identity("  IRIS@X.test  ")), emptyList())),
        )
    }

    // ── the offline pre-filter: may this Save create anything, decided with no request ──────────

    @Test fun `a new plausible address on a JMAP account is worth a look at the server`() {
        assertTrue(mayCreateIdentities(account(), listOf(identity("brand.new@x.test"))))
    }

    @Test fun `an IMAP account is never worth a request`() {
        assertFalse(
            "an IMAP Save must open no JMAP session at all: MailRepository.serverIdentities throws " +
                "on IMAP credentials, so a true here turns every IMAP Save into a caught exception " +
                "and a refusal message about an address the user never asked to publish.",
            mayCreateIdentities(account(protocol = MailProtocol.IMAP), listOf(identity("brand.new@x.test"))),
        )
    }

    @Test fun `a linked sub-account is never worth a request`() {
        assertFalse(
            mayCreateIdentities(account(loginId = "login1"), listOf(identity("brand.new@x.test"))),
        )
    }

    @Test fun `a Save that adds no address is not worth a request`() {
        val before = account(identities = listOf(identity("c@x.test")))

        assertFalse(
            "renaming a display name, changing a password or a sync window must cost no round trip.",
            mayCreateIdentities(before, listOf(identity("c@x.test"))),
        )
    }

    /**
     * And the pre-filter says no on the fabricated row, so a Save that only renamed a display name
     */
    @Test fun `the fabricated row alone is not worth a request, an alias beside it is`() {
        val before = account()

        assertFalse(mayCreateIdentities(before, listOf(identity(before.username))))
        assertTrue(mayCreateIdentities(before, listOf(identity(before.username), identity("alias@x.test"))))
    }

    @Test fun `a half-typed address is not worth a request`() {
        assertFalse(mayCreateIdentities(account(), listOf(identity("not-an-address"))))
    }

    /**
     * The pre-filter asks "if the server held NOTHING, would anything leave?" — never "does the
     */
    @Test fun `the pre-filter does not believe the stored server list`() {
        val before = account(storedServerIdentities = listOf(identity("brand.new@x.test")))

        assertTrue(mayCreateIdentities(before, listOf(identity("brand.new@x.test"))))
    }

    /**
     * The pre-filter is the SUPER-SET of the candidates: it answers "if the server held nothing,
     */
    @Test fun `whatever the real decision would create, the pre-filter lets through`() {
        val before = account(identities = listOf(identity("old@x.test")))
        val edited = listOf(identity("old@x.test"), identity("brand.new@x.test"))
        val server = listOf(identity("a@x.test"))

        assertEquals(listOf("brand.new@x.test"), emails(identitiesToCreate(before, edited, server)))
        assertTrue(mayCreateIdentities(before, edited))
    }
}
