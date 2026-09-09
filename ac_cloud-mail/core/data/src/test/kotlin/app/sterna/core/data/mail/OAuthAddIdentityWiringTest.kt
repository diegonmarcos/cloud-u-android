package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. The decision itself — which account is already installed —
 */
class OAuthAddIdentityWiringTest {

    private fun bodyLines(function: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", function)
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }

    /**
     * [expected] must appear in the body of [function] as a CONTIGUOUS run of whole trimmed lines.
     */
    private fun assertBlock(function: String, why: String, expected: List<String>) {
        val start = bodyLines(function).indexOf(expected.first())
        val found = if (start < 0) emptyList() else bodyLines(function).drop(start).take(expected.size)
        assertEquals(why, expected, found)
    }

    private fun assertAsks(function: String, why: String, expected: List<String>) =
        assertBlock(
            function,
            "MailRepository.$function must ask the shared identity, BEFORE the store writes, " +
                "whether this add creates the account — on the very values it then hands to " +
                "addOAuth. $why",
            expected,
        )

    /**
     * JMAP: the endpoint is the host, the username is the address the request carried. Asked
     */
    @Test fun `the JMAP OAuth add asks whether the account is already installed`() {
        assertAsks(
            "addOAuthAccount",
            "Asked after the store, or on the wrong pair, and a re-add of the same mailbox ends " +
                "the way a first sign-in does: nothing said, and one row on a phone where the " +
                "reader believes she just added a second account. Asked on `host` — the host the " +
                "OAuth document came from — it answers about an identity the store is not given: " +
                "stored under the OAuth document's host, the same mailbox added by password and " +
                "then by OAuth makes two accounts (#55). Asked on the resolved host ALONE, it " +
                "answers about only one of the two keys the store now looks under, and an account " +
                "OAuth-added under 1.5.0 or 1.5.1 is announced as newly added.",
            listOf(
                "val created = resolveExistingLoginAmong(",
                "accountStore.accounts(),",
                "listOf(",
                """accountKeyOf(MailProtocol.JMAP, server, "", email),""",
                """accountKeyOf(MailProtocol.JMAP, host, "", email),""",
                "),",
                ") == null",
            ),
        )
    }

    /**
     * IMAP: an IMAP account is keyed on its IMAP host (its `server` is blank), and on the address
     * the `id_token` named — the one the store is given — never the alias the user typed.
     */
    @Test fun `the Microsoft OAuth IMAP add asks on the values the store is given`() {
        assertAsks(
            "addOAuthImapAccount",
            "Keyed on the typed address or on a blank server, this path answers about an account " +
                "that is not the one it is about to write, and the Outlook sign-in toast then " +
                "says the opposite of what happened.",
            listOf(
                "val created = resolveExistingLogin(",
                "accountStore.accounts(),",
                """accountKeyOf(MailProtocol.IMAP, "", provider.imap.host, username),""",
                ") == null",
            ),
        )
    }

    /**
     * And the answer must leave the function. `return created` is the whole reason these two
     */
    @Test fun `both OAuth adds return what they found, not a literal`() {
        val offenders = listOf("addOAuthAccount", "addOAuthImapAccount").filterNot { function ->
            bodyLines(function).contains("return created")
        }
        assertEquals(
            "each OAuth add path must end on `return created`. Returning a literal tells every " +
                "screen above the same story on every sign-in — a first add reported as a re-add, " +
                "or a re-add reported as a creation.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * The host the account is STORED under is not the host the tokens came from. The password
     */
    @Test fun `the JMAP OAuth add resolves the server the password path would have stored`() {
        assertBlock(
            "addOAuthAccount",
            "MailRepository.addOAuthAccount must resolve the server with the shared autodiscovery " +
                "before it stores anything, falling back on the OAuth document's host via " +
                "oauthServerToStore. Stored under the OAuth document's host, the same mailbox " +
                "added by password and then by OAuth makes two accounts (#55). And the block " +
                "starts at the token validation because the ORDER is the point: moved above it, " +
                "three guessed hosts get probed with a token nothing has proved yet. The guard " +
                "line is pinned whole, so inverting it to !oauthHostWasGuessed(…) lands here.",
            listOf(
                """client.fetchSession(Jmap.sessionUrlFor(host), BearerAuth(tokens.accessToken)).mailAccountId()""",
                """?: error("This user has no JMAP mail account.")""",
                "val guessedHost = oauthHostWasGuessed(host, Jmap.autodiscoverHosts(email))",
                "val server = if (guessedHost) {",
                "oauthServerToStore(",
                """discoverJmapServer(email, password = "", token = tokens.accessToken),""",
                "host,",
                ")",
            ),
        )
    }

    /**
     * The other half of the fence. The manual form's OAuth button and its password button read
     */
    @Test fun `the JMAP OAuth add keeps a host the reader dictated verbatim`() {
        assertBlock(
            "addOAuthAccount",
            "MailRepository.addOAuthAccount must resolve ONLY a host autodiscovery guessed; the " +
                "other branch hands the store `host` unchanged. Resolve unconditionally and the " +
                "manual form's OAuth button files the account somewhere else than its own " +
                "password button, from the same typed field (#55).",
            listOf(
                "val server = if (guessedHost) {",
                "oauthServerToStore(",
                """discoverJmapServer(email, password = "", token = tokens.accessToken),""",
                "host,",
                ")",
                "} else {",
                "host",
                "}",
            ),
        )
    }

    /**
     * A resolution that answers nothing writes the account under the old host: #55 reproduced
     */
    @Test fun `a silent resolution leaves a line to read`() {
        assertBlock(
            "addOAuthAccount",
            "MailRepository.addOAuthAccount must log when the guard passed and autodiscovery " +
                "resolved nothing, because the account is then stored under the OAuth document's " +
                "host — #55 as it was, with nothing anywhere to say so.",
            listOf(
                "if (guessedHost && server == host) {",
                """android.util.Log.w("MailRepository", "OAuth add: nothing autodiscovered for ${'$'}host; stored as-is (#55)")""",
                "}",
            ),
        )
    }

    /**
     * And the resolved host must be what the store is GIVEN. Resolving it and then writing
     * `server = host` anyway is the defect in full, with a decoy line above it that reads right.
     */
    @Test fun `the JMAP OAuth add stores the resolved server, not the document host`() {
        assertBlock(
            "addOAuthAccount",
            "MailRepository.addOAuthAccount must hand accountStore.addOAuth the RESOLVED server. " +
                "Stored under the OAuth document's host, the same mailbox added by password and " +
                "then by OAuth makes two accounts (#55).",
            listOf(
                "val id = accountStore.addOAuth(",
                "server = server,",
                "username = email,",
            ),
        )
    }

    /**
     * And the store must be told the OTHER host this route has filed this address under. An
     */
    @Test fun `the JMAP OAuth add tells the store which host 1_5_x filed this account under`() {
        assertBlock(
            "addOAuthAccount",
            "MailRepository.addOAuthAccount must pass accountStore.addOAuth the host the OAuth " +
                "document came from. Without it, an account OAuth-added under 1.5.0 or 1.5.1 " +
                "takes a SECOND line at the first re-add after the update — the fix for #55 " +
                "manufacturing the duplicate of #55.",
            listOf(
                "tokenEndpoint = metadata.tokenEndpoint,",
                "clientId = Jmap.OAUTH_CLIENT_ID,",
                "documentHost = host,",
                ")",
            ),
        )
    }

    /**
     * Sub-account reconciliation runs on a CREATION only — the same rule the two neighbouring
     */
    @Test fun `sub-account reconciliation only runs when the OAuth add created the account`() {
        val gated = "if (created) reconcileLinkedAccountsAfterAdd(id)"
        val offenders = bodyLines("addOAuthAccount")
            .filter { RECONCILE.containsMatchIn(it) && it != gated }
        assertEquals(
            "MailRepository.addOAuthAccount must call reconcileLinkedAccountsAfterAdd behind " +
                "`if (created)`, exactly as the two routes in ConnectViewModel do. Ungated, an " +
                "OAuth re-add that lands on a login carrying SHARED MAILBOXES hands the #129 diff " +
                "a session that may not list the shares: every sub-account is deleted and its " +
                "five tables purged — a real shared mailbox gone, cache included, with nothing to " +
                "restore it from. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    private companion object {
        /** Any call of the #129 reconcile, gated or not — the test compares the WHOLE line. */
        val RECONCILE = Regex("""reconcileLinkedAccountsAfterAdd\(""")
    }
}
