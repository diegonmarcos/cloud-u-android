package app.sterna.core.data.mail

import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.filter.SourceText
import app.sterna.core.jmap.model.Identity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #172, second half: an identity the app has just created on the server must appear WITHOUT
 */
class IdentityRefreshAfterCreateTest {

    private val mailRepository = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"

    // ── The wiring, read as text ─────────────────────────────────────────────────────────────

    @Test fun `a confirmed creation is followed by a re-read, before createIdentity returns`() {
        assertEquals(
            "MailRepository.createIdentity no longer re-reads the server's identities after the " +
                "creation was confirmed. Without that line the new address is stored nowhere the " +
                "screen reads: `connect` hands back its CACHED context, so the next Identity/get " +
                "only happens at the following cold start — the app restart of #172. The order is " +
                "part of the rule: the re-read sits AFTER the client call, which throws on a " +
                "notCreated refusal, so a refused address costs no second round trip and leaves " +
                "the stored list untouched.",
            listOf(
                "val created = client.createIdentity(ctx.session, ctx.accountId, name = name, email = email, auth = ctx.auth)",
                "refreshServerIdentities(ctx.session, ctx.accountId, credentials.id, ctx.auth)",
                "return created",
            ),
            block(mailRepository, "val created = client.createIdentity(", 3),
        )
    }

    @Test fun `createIdentity refreshes once, and only there`() {
        val body = SourceText.functionSource(
            SourceText.read(mailRepository),
            "suspend fun createIdentity(credentials: AccountCredentials, name: String, email: String): String {",
        )

        assertEquals(
            "MailRepository.createIdentity must call refreshServerIdentities exactly once. A " +
                "second call placed BEFORE the client call would leave the block above intact " +
                "while re-reading the whole list on every refusal too — a round trip nobody asked " +
                "for, on the one path where the server has just said no.",
            1,
            Regex("""refreshServerIdentities\(""").findAll(body).count(),
        )
    }

    @Test fun `connect still refreshes, at the same place in its sequence`() {
        assertEquals(
            "the refresh moved or changed arguments inside MailRepository.connect. It must stay " +
                "between the linked-account reconcile and the mailbox fetch, reading with the JMAP " +
                "account id and writing under credentials.id — the two are both String and a swap " +
                "compiles. Codeberg #32 rides on this line: the server is authoritative for the " +
                "addresses the user may send as.",
            listOf(
                "reconcileLinkedAccounts(credentials, session, auth)",
                "refreshServerIdentities(session, accountId, credentials.id, auth)",
                "val mailboxes = client.getMailboxes(session, accountId, auth)",
            ),
            block(mailRepository, "reconcileLinkedAccounts(credentials, session, auth)", 3),
        )
    }

    @Test fun `the refresh reads from the server, writes under the local id, and cannot throw`() {
        assertEquals(
            "MailRepository.refreshServerIdentities changed shape. It must read the list from the " +
                "server (Identity/get) and store THAT, never a list built locally; it must write " +
                "under the local account id and read under the JMAP one; and it must stay inside " +
                "runCatching, because `connect` calls it and a failed read must not break signing " +
                "in.",
            listOf(
                "private suspend fun refreshServerIdentities(",
                "session: JmapSession,",
                "accountId: String,",
                "localAccountId: String,",
                "auth: JmapAuth,",
                ") {",
                "runCatching {",
                "val serverIdentities = storedServerIdentities(client.getIdentities(session, accountId, auth))",
                "accountStore.setServerIdentities(localAccountId, serverIdentities)",
                "}",
                "}",
            ),
            block(mailRepository, "private suspend fun refreshServerIdentities(", 11),
        )
    }

    @Test fun `the caller can read the server's identities, and a failed read THROWS`() {
        assertEquals(
            "MailRepository.serverIdentities changed shape. It is the read #172 was missing: " +
                "`StoredAccount.serverIdentities` is written only by refreshServerIdentities, which " +
                "only `connect` calls, and neither adding an account nor Test connection nor a sync " +
                "opens a connect — so on a freshly added account the stored list stayed empty " +
                "because nobody read it, and identitiesToCreate refused to post against an unread " +
                "list, for good. Three things are pinned: IMAP THROWS (there is no Identity/get " +
                "behind it, and a silent empty answer would make a bypassing caller look like it " +
                "worked); the answer comes from client.getIdentities, never from a locally built " +
                "list; and there is NO runCatching — an empty list must mean 'the server holds no " +
                "identity', so swallowing a failure into one would tell the caller the opposite of " +
                "what happened and post every offline address blind.",
            listOf(
                "suspend fun serverIdentities(credentials: AccountCredentials): List<StoredIdentity> {",
                "if (credentials.protocol == MailProtocol.IMAP) {",
                "throw IllegalStateException(\"Identity/get is JMAP-only; an IMAP alias is local\")",
                "}",
                "val ctx = connect(credentials)",
                "val identities = storedServerIdentities(client.getIdentities(ctx.session, ctx.accountId, ctx.auth))",
                "accountStore.setServerIdentities(credentials.id, identities)",
                "return identities",
                "}",
            ),
            block(mailRepository, "suspend fun serverIdentities(", 9),
        )
    }

    // ── The payload, executed ────────────────────────────────────────────────────────────────

    @Test fun `the re-read list is what the account stores, new address included`() {
        // What the server answers the second time: the identity that was already there, plus the
        // one Identity/set has just created. This is the list the settings screen reads back, and
        // the reason the "your server may refuse this address" hint switches off by itself.
        val answered = listOf(
            Identity(id = "i1", name = "Ana", email = "ana@x.test"),
            Identity(id = "i2", name = "Ana pro", email = "pro@x.test", textSignature = "-- Ana"),
        )

        assertEquals(
            listOf(
                StoredIdentity(id = "i1", name = "Ana", email = "ana@x.test"),
                StoredIdentity(id = "i2", name = "Ana pro", email = "pro@x.test", signature = "-- Ana"),
            ),
            storedServerIdentities(answered),
        )
    }

    @Test fun `an identity the server gives no address for is dropped`() {
        val answered = listOf(
            Identity(id = "i1", name = "Ana", email = "ana@x.test"),
            Identity(id = "i2", name = "nameless", email = ""),
        )

        assertEquals(
            listOf(StoredIdentity(id = "i1", name = "Ana", email = "ana@x.test")),
            storedServerIdentities(answered),
        )
    }

    @Test fun `a legacy signature holding raw HTML is split, not shown as tag soup`() {
        val answered = listOf(
            Identity(id = "i1", email = "ana@x.test", textSignature = "<p>Ana</p>"),
        )

        assertEquals(
            listOf(
                StoredIdentity(
                    id = "i1",
                    name = "",
                    email = "ana@x.test",
                    signature = "Ana",
                    signatureHtml = "<p>Ana</p>",
                ),
            ),
            storedServerIdentities(answered),
        )
    }

    /**
     * The server's OWN html signature field (RFC 8621 §6), which is not the legacy case above: here
     */
    @Test fun `the signature the server holds as HTML survives the re-read`() {
        val answered = listOf(
            Identity(
                id = "i1",
                email = "ana@x.test",
                textSignature = "Ana",
                htmlSignature = "<p><b>Ana</b></p>",
            ),
        )

        assertEquals(
            listOf(
                StoredIdentity(
                    id = "i1",
                    name = "",
                    email = "ana@x.test",
                    signature = "Ana",
                    signatureHtml = "<p><b>Ana</b></p>",
                ),
            ),
            storedServerIdentities(answered),
        )
    }

    /**
     * [count] consecutive CODE lines of [path] from the single one starting with [prefix]. Comments
     */
    private fun block(path: String, prefix: String, count: Int): List<String> {
        val code = SourceText.codeLines(SourceText.read(path))
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(start, minOf(start + count, code.size))
    }
}
