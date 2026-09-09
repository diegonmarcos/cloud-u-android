package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The decision "is this account already there?", EXECUTED. Every assertion below runs the shipped
 */
class AccountIdentityTest {

    private fun jmap(
        id: String,
        server: String,
        username: String,
        authType: AuthType = AuthType.BASIC,
    ) = StoredAccount(
        id = id,
        server = server,
        username = username,
        protocol = MailProtocol.JMAP,
        authType = authType,
    )

    private fun imap(
        id: String,
        imapHost: String,
        username: String,
        authType: AuthType = AuthType.BASIC,
    ) = StoredAccount(
        id = id,
        server = "",
        username = username,
        protocol = MailProtocol.IMAP,
        imapHost = imapHost,
        authType = authType,
    )

    @Test fun `an IMAP account is keyed on its IMAP host, not on its blank server`() {
        assertEquals(
            "imap.example.test",
            accountEndpointOf(MailProtocol.IMAP, server = "", imapHost = "imap.example.test"),
        )
        assertEquals(
            AccountKey(MailProtocol.IMAP, "imap.example.test", "ana@example.test"),
            accountKeyOf(imap("1", "imap.example.test", "ana@example.test")),
        )
    }

    @Test fun `a JMAP account is keyed on its server, and ignores whatever imapHost holds`() {
        assertEquals(
            "mail.example.test",
            accountEndpointOf(
                MailProtocol.JMAP,
                server = "mail.example.test",
                imapHost = "imap.example.test",
            ),
        )
        assertEquals(
            AccountKey(MailProtocol.JMAP, "mail.example.test", "ana@example.test"),
            accountKeyOf(jmap("1", "mail.example.test", "ana@example.test")),
        )
    }

    @Test fun `the browser's raw candidate and the resolved host normalise to one endpoint`() {
        assertEquals("mail.example.test", normalizeEndpoint("  HTTPS://Mail.Example.Test/  "))
        assertEquals("mail.example.test", normalizeEndpoint("mail.example.test"))
        assertEquals("mail.example.test", normalizeEndpoint("http://mail.example.test///"))
        assertEquals(
            accountKeyOf(MailProtocol.JMAP, "mail.example.test", "", "ana@example.test"),
            accountKeyOf(MailProtocol.JMAP, "  HTTPS://Mail.Example.Test/  ", "", "ana@example.test"),
        )
    }

    @Test fun `the username is compared trimmed and lower-cased`() {
        assertEquals(
            accountKeyOf(MailProtocol.JMAP, "mail.example.test", "", "ana@example.test"),
            accountKeyOf(MailProtocol.JMAP, "mail.example.test", "", "  Ana@Example.Test "),
        )
    }

    @Test fun `an OAuth re-add finds the account that was stored with a password`() {
        val stored = jmap("stored-1", "mail.example.test", "ana@example.test", AuthType.BASIC)
        val oauthKey = accountKeyOf(
            MailProtocol.JMAP,
            server = "https://mail.example.test",
            imapHost = "",
            username = "Ana@Example.Test",
        )
        assertSame(stored, resolveExisting(listOf(stored), oauthKey))
    }

    @Test fun `a blank endpoint yields no key, so no route can guard on it`() {
        assertNull(accountKeyOf(MailProtocol.JMAP, "   ", "", "ana@example.test"))
        assertNull(accountKeyOf(imap("1", "", "ana@example.test")))
        assertNull(accountKeyOf(MailProtocol.JMAP, "https://", "", "ana@example.test"))
    }

    @Test fun `a blank username yields no key`() {
        assertNull(accountKeyOf(MailProtocol.JMAP, "mail.example.test", "", "   "))
        assertNull(accountKeyOf(jmap("1", "mail.example.test", "")))
    }

    @Test fun `a null key matches nothing, even among half-filled accounts`() {
        val half = jmap("stored-1", "mail.example.test", "")
        assertNull(resolveExisting(listOf(half), null))
        assertNull(resolveExisting(listOf(half), accountKeyOf(MailProtocol.JMAP, "   ", "", "   ")))
    }

    @Test fun `two people on one server stay two accounts`() {
        val ana = jmap("stored-1", "mail.example.test", "ana@example.test")
        val bo = accountKeyOf(MailProtocol.JMAP, "mail.example.test", "", "bo@example.test")
        assertNotEquals(accountKeyOf(ana), bo)
        assertNull(resolveExisting(listOf(ana), bo))
        assertSame(ana, resolveExisting(listOf(ana), accountKeyOf(ana)))
    }

    @Test fun `the same address over IMAP and over JMAP is two accounts`() {
        val overImap = accountKeyOf(imap("1", "mail.example.test", "ana@example.test"))
        val overJmap = accountKeyOf(jmap("2", "mail.example.test", "ana@example.test"))
        assertNotEquals(overImap, overJmap)
        assertNull(resolveExisting(listOf(imap("1", "mail.example.test", "ana@example.test")), overJmap))
    }

    // ---- the path (the rule normalizeEndpoint keeps, and nothing pinned) -----------------------
    //
    // Literal on both sides throughout: nothing below calls normalizeEndpoint to build what it
    // then expects. Swallowing the path would merge two genuinely different JMAP servers hosted
    // under one name at two paths — the recognised account overwrites the other's connection AND
    // its encrypted secret, which has no second copy. Nothing brings that back.

    @Test fun `a path is part of the endpoint, so one name at two paths is two servers`() {
        assertEquals("mail.example.test", normalizeEndpoint("mail.example.test"))
        assertEquals("mail.example.test/jmap/session", normalizeEndpoint("mail.example.test/jmap/session"))
        assertEquals(
            "the scheme, the case and the trailing slash go; the PATH stays",
            "mail.example.test/jmap/session",
            normalizeEndpoint("  HTTPS://Mail.Example.Test/JMAP/Session/  "),
        )
        assertNotEquals(
            "the bare host and the same host at a path became one endpoint: two different JMAP " +
                "servers under one name now recognise each other, and the add overwrites the " +
                "other's connection and its encrypted secret, which has no second copy",
            "mail.example.test",
            normalizeEndpoint("mail.example.test/jmap/session"),
        )
    }

    @Test fun `two JMAP servers under one name at two paths keep two keys`() {
        val atRoot = jmap("root-1", "https://mail.example.test", "ana@example.test")
        val atPath = jmap("path-1", "https://mail.example.test/jmap/session", "ana@example.test")
        assertEquals(
            AccountKey(MailProtocol.JMAP, "mail.example.test", "ana@example.test"),
            accountKeyOf(atRoot),
        )
        assertEquals(
            AccountKey(MailProtocol.JMAP, "mail.example.test/jmap/session", "ana@example.test"),
            accountKeyOf(atPath),
        )
        assertNull(
            "an add aimed at the server behind /jmap/session recognised the one at the root: it " +
                "would refresh THAT account instead, overwriting a connection and a secret that " +
                "belong to another server",
            resolveExistingLogin(
                listOf(atRoot),
                AccountKey(MailProtocol.JMAP, "mail.example.test/jmap/session", "ana@example.test"),
            ),
        )
        assertNull(
            "an add aimed at the server at the root recognised the one behind /jmap/session",
            resolveExistingLogin(
                listOf(atPath),
                AccountKey(MailProtocol.JMAP, "mail.example.test", "ana@example.test"),
            ),
        )
        assertSame(
            "with both stored, the key carrying the path must land on the account carrying it",
            atPath,
            resolveExistingLogin(
                listOf(atRoot, atPath),
                AccountKey(MailProtocol.JMAP, "mail.example.test/jmap/session", "ana@example.test"),
            ),
        )
    }

    @Test fun `the path is compared whole, so a prefix of it is another endpoint`() {
        assertEquals("mail.example.test/jmap", normalizeEndpoint("mail.example.test/jmap"))
        assertNotEquals(
            "/jmap and /jmap/session became one endpoint: two servers mounted one under the " +
                "other merge, and the deeper one loses its secret to the shallower",
            "mail.example.test/jmap",
            normalizeEndpoint("mail.example.test/jmap/session"),
        )
        assertNull(
            "a key holding only the first path segment recognised the full path",
            resolveExistingLogin(
                listOf(jmap("path-1", "mail.example.test/jmap/session", "ana@example.test")),
                AccountKey(MailProtocol.JMAP, "mail.example.test/jmap", "ana@example.test"),
            ),
        )
    }

    @Test fun `a trailing slash alone is not a path`() {
        // Where the rule STOPS, and deliberately: `mail.example.test/` names no path, so it is the
        // same endpoint as the bare host — that is what trimEnd('/') decides. Pinned so the day
        // someone widens or narrows the path rule, the boundary itself is in the diff.
        assertEquals("mail.example.test", normalizeEndpoint("mail.example.test/"))
        assertEquals("mail.example.test", normalizeEndpoint("https://mail.example.test/"))
        val stored = jmap("stored-1", "https://mail.example.test/", "ana@example.test")
        assertSame(
            "a trailing slash made a second endpoint: the same mailbox typed with and without it " +
                "becomes two accounts — the duplicate of #55, from the other side",
            stored,
            resolveExistingLogin(
                listOf(stored),
                AccountKey(MailProtocol.JMAP, "mail.example.test", "ana@example.test"),
            ),
        )
    }

    // ---- refreshedWith / resolveExistingLogin (the re-add) --------------------------------------

    /** Everything a user has settled on her account over months, and no sign-in form ever proves. */
    private val settled = StoredAccount(
        id = "kept-id",
        server = "mail.example.test",
        username = "ana@example.test",
        jmapAccountId = "srv-account-7",
        accountName = "Ana perso",
        inboxId = "mbx-inbox",
        inboxName = "Courrier",
        unread = 12,
        syncWindow = SyncWindow.YEAR_1,
        notificationsEnabled = false,
        uploadSentCopy = false,
        showOnlySubscribedFolders = true,
        watchedFolders = setOf("mbx-2", "mbx-3"),
        color = 0x33445566,
        signature = "Ana",
        identities = listOf(StoredIdentity(id = "i1", name = "Ana", email = "ana@example.test")),
        serverIdentities = listOf(StoredIdentity(id = "s1", name = "Ana pro", email = "pro@example.test")),
        defaultIdentityId = "i1",
        importPending = true,
        protocol = MailProtocol.JMAP,
        authType = AuthType.OAUTH,
        oauthAccessToken = "old-access",
        oauthAccessExpiresAt = 111L,
        oauthTokenEndpoint = "https://old.example.test/token",
        oauthClientId = "old-client",
        imapHost = "old.imap.example.test",
        imapPort = 143,
        imapSecurity = ConnectionSecurity.NONE,
        smtpHost = "old.smtp.example.test",
        smtpPort = 25,
        smtpSecurity = ConnectionSecurity.NONE,
        pgpEnabled = true,
        pgpSignKeyId = 42L,
        pgpEncryptByDefault = true,
    )

    /** What a fresh password sign-in builds: a new UUID, and only what the connection proved. */
    private val provenPassword = StoredAccount(
        id = "fresh-uuid",
        server = "mail2.example.test",
        username = "ana2@example.test",
        accountName = "",
        importPending = true,
        protocol = MailProtocol.IMAP,
        authType = AuthType.BASIC,
        imapHost = "new.imap.example.test",
        imapPort = 993,
        imapSecurity = ConnectionSecurity.TLS,
        smtpHost = "new.smtp.example.test",
        smtpPort = 465,
        smtpSecurity = ConnectionSecurity.STARTTLS,
    )

    /** The same, from the OAuth route: it also proves four token fields. */
    private val provenOAuth = provenPassword.copy(
        authType = AuthType.OAUTH,
        oauthAccessToken = "new-access",
        oauthAccessExpiresAt = 222L,
        oauthTokenEndpoint = "https://new.example.test/token",
        oauthClientId = "new-client",
    )

    @Test fun `a re-add keeps every setting the sign-in did not prove`() {
        val r = refreshedWith(settled, provenPassword)
        assertEquals("the id moved: the encrypted secret and every reference are filed under it", "kept-id", r.id)
        assertEquals("the name she gave her account is gone", "Ana perso", r.accountName)
        assertEquals("her chosen account colour is gone", 0x33445566, r.color)
        assertNull("the login link is gone: a sub-account would stop borrowing its secret", r.loginId)
        assertEquals("the server-side account id is gone", "srv-account-7", r.jmapAccountId)
        assertEquals("the resolved inbox is gone, so the list reloads from nothing", "mbx-inbox", r.inboxId)
        assertEquals("the inbox name is gone", "Courrier", r.inboxName)
        assertEquals("the unread count is gone", 12, r.unread)
        assertEquals("the sync window fell back to the default", SyncWindow.YEAR_1, r.syncWindow)
        assertEquals("her per-account notification opt-out is gone", false, r.notificationsEnabled)
        assertEquals("her sent-copy choice is gone", false, r.uploadSentCopy)
        // #174: `proven` carries this one OFF (a sign-in form builds the default), so a
        // re-add written as `proven.copy(...)` would silently UNHIDE the folders she chose to hide,
        // and only this direction can tell the two spellings apart.
        assertEquals("her subscribed-folders-only choice is gone", true, r.showOnlySubscribedFolders)
        assertEquals("the folders she watches for new mail are gone", setOf("mbx-2", "mbx-3"), r.watchedFolders)
        assertEquals("her signature is gone", "Ana", r.signature)
        assertEquals("her manual sending identities are gone", settled.identities, r.identities)
        assertEquals("the discovered server identities are gone", settled.serverIdentities, r.serverIdentities)
        assertEquals("her default sending identity is gone", "i1", r.defaultIdentityId)
        assertEquals("PGP was switched off behind her back", true, r.pgpEnabled)
        assertEquals("her signing key is gone", 42L, r.pgpSignKeyId)
        assertEquals("encrypt-by-default was switched off", true, r.pgpEncryptByDefault)
    }

    @Test fun `a re-add takes everything the sign-in did prove`() {
        val r = refreshedWith(settled, provenOAuth)
        assertEquals("mail2.example.test", r.server)
        assertEquals("ana2@example.test", r.username)
        assertEquals(MailProtocol.IMAP, r.protocol)
        assertEquals(AuthType.OAUTH, r.authType)
        assertEquals("new-access", r.oauthAccessToken)
        assertEquals(222L, r.oauthAccessExpiresAt)
        assertEquals("https://new.example.test/token", r.oauthTokenEndpoint)
        assertEquals("new-client", r.oauthClientId)
        assertEquals("new.imap.example.test", r.imapHost)
        assertEquals(993, r.imapPort)
        assertEquals(ConnectionSecurity.TLS, r.imapSecurity)
        assertEquals("new.smtp.example.test", r.smtpHost)
        assertEquals(465, r.smtpPort)
        assertEquals(ConnectionSecurity.STARTTLS, r.smtpSecurity)
    }

    @Test fun `a re-add is a sign-in, so the account stops waiting for one`() {
        assertEquals(
            "importPending survived a completed sign-in: the account stays in the " +
                "\"accounts to sign in\" list it has just left.",
            false,
            refreshedWith(settled, provenPassword.copy(importPending = true)).importPending,
        )
    }

    @Test fun `re-adding an OAuth account with a password drops the tokens it no longer has`() {
        // Deliberate: the password is written into the SAME encrypted slot that held the refresh
        // token, so keeping oauth* would describe a credential the record no longer carries.
        val r = refreshedWith(settled, provenPassword)
        assertEquals(AuthType.BASIC, r.authType)
        assertEquals("", r.oauthAccessToken)
        assertEquals(0L, r.oauthAccessExpiresAt)
        assertEquals("", r.oauthTokenEndpoint)
        assertEquals("", r.oauthClientId)
    }

    @Test fun `an add resolves onto a login, never onto a linked sub-account`() {
        // A sub-account BORROWS its login's secret (loginKey() == loginId ?: id). Resolving an add
        // onto one would file the new secret under an id nothing reads, leaving the account signed
        // in with someone else's credential.
        val shared = jmap("shared-1", "mail.example.test", "ana@example.test")
            .copy(loginId = "login-1")
        val login = jmap("login-1", "mail.example.test", "ana@example.test")
        val key = accountKeyOf(MailProtocol.JMAP, "mail.example.test", "", "ana@example.test")
        assertNull("an add resolved onto a linked sub-account", resolveExistingLogin(listOf(shared), key))
        assertSame(
            "the login was skipped because a linked sub-account with the same key came first",
            login,
            resolveExistingLogin(listOf(shared, login), key),
        )
    }

    @Test fun `resolveExistingLogin answers null for a null key`() {
        val login = jmap("login-1", "mail.example.test", "ana@example.test")
        assertNull(resolveExistingLogin(listOf(login), null))
    }

    // ---- resolveExistingLoginAmong (the row 1.5.0 / 1.5.1 wrote) ---------------------------------
    //
    // Literal on both sides on purpose: `masto.top` is what this version resolves and stores,
    // `mail.masto.top` is what the OAuth path stored in 1.5.0 and 1.5.1. Nothing below re-derives
    // either of them from the shipped rule.

    private val currentKey = accountKeyOf(MailProtocol.JMAP, "masto.top", "", "ana@masto.top")
    private val inheritedKey = accountKeyOf(MailProtocol.JMAP, "mail.masto.top", "", "ana@masto.top")

    @Test fun `an OAuth row written by 1_5_x is recognised by the second key, not the first`() {
        val legacy = jmap("oauth-1", "mail.masto.top", "ana@masto.top", AuthType.OAUTH)
        assertNull(
            "the host this version resolves must not match the 1.5.x row on its own, " +
                "or this test proves nothing",
            resolveExistingLogin(listOf(legacy), currentKey),
        )
        assertSame(
            "an OAuth account added by 1.5.0/1.5.1 is invisible to the host this version " +
                "resolves, so the first re-authentication after the update takes a SECOND line " +
                "— the fix for #55 manufacturing the duplicate of #55",
            legacy,
            resolveExistingLoginAmong(listOf(legacy), listOf(currentKey, inheritedKey)),
        )
    }

    @Test fun `when both rows exist the FIRST key wins, so an add lands on the current one`() {
        // The old row comes first in the list on purpose: an implementation that scanned the
        // ACCOUNTS and took whichever matched any key would answer the 1.5.x row here.
        val legacy = jmap("oauth-old", "mail.masto.top", "ana@masto.top", AuthType.OAUTH)
        val current = jmap("oauth-new", "masto.top", "ana@masto.top", AuthType.OAUTH)
        assertSame(
            "the order of the keys IS the decision: the key this version writes must win, or a " +
                "re-add maintains the row this version stopped writing",
            current,
            resolveExistingLoginAmong(listOf(legacy, current), listOf(currentKey, inheritedKey)),
        )
    }

    @Test fun `neither key recognising anything is still an add`() {
        val elsewhere = jmap("oauth-2", "jmap.other.test", "ana@masto.top", AuthType.OAUTH)
        assertNull(resolveExistingLoginAmong(listOf(elsewhere), listOf(currentKey, inheritedKey)))
        assertNull(resolveExistingLoginAmong(emptyList(), listOf(currentKey, inheritedKey)))
    }

    @Test fun `a null key recognises nothing and does not swallow the key after it`() {
        val legacy = jmap("oauth-1", "mail.masto.top", "ana@masto.top", AuthType.OAUTH)
        assertSame(
            "a key that could not be built must be skipped, not stop the search",
            legacy,
            resolveExistingLoginAmong(listOf(legacy), listOf(null, inheritedKey)),
        )
        assertNull(resolveExistingLoginAmong(listOf(legacy), listOf(null, null)))
        assertNull(resolveExistingLoginAmong(listOf(legacy), emptyList()))
    }

    @Test fun `the second key widens the HOST only, never the address`() {
        // Two mailboxes on one server stay two accounts, whichever host they were filed under.
        val someoneElse = jmap("oauth-3", "mail.masto.top", "jordan@masto.top", AuthType.OAUTH)
        assertNull(
            "the inherited key recognised another address on the same host: two people's " +
                "mailboxes would merge, and writePassword would overwrite a secret with no copy",
            resolveExistingLoginAmong(listOf(someoneElse), listOf(currentKey, inheritedKey)),
        )
    }

    @Test fun `a linked sub-account is no more resolvable through the second key than the first`() {
        val shared = jmap("shared-1", "mail.masto.top", "ana@masto.top", AuthType.OAUTH)
            .copy(loginId = "login-9")
        assertNull(
            "an add resolved onto a linked sub-account files the refresh token under an id " +
                "nothing reads",
            resolveExistingLoginAmong(listOf(shared), listOf(currentKey, inheritedKey)),
        )
    }
}
