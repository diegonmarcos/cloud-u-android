package app.sterna.core.data.account

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared-mailbox memory, EXECUTED: [reconciledAccounts] is the whole list transformation
 * `AccountStore.reconcileLinkedAccounts` performs, so these tests replay the real decision rather
 * than re-stating it. The diff itself comes from the shipped [diffLinkedAccounts] — nothing here
 * recomputes what should be pruned or added.
 *
 * The store cannot be built in this module (a `Context` and the Keystore, no Robolectric), which is
 * why the store's own wiring is pinned separately by [LinkedAccountMemoryStoreWiringTest]. That one
 * reads text; this one runs the code.
 */
class LinkedAccountMemoryTest {

    // ---- the witness: a share pruned, then re-granted ----

    @Test fun `a share pruned then re-granted comes back with the settings the user chose`() {
        val list = listOf(LOGIN, SHARED, OTHER_ACCOUNT)

        // Turn 1: the session shrinks back to the login's own account. The share is revoked.
        val gone = diffLinkedAccounts(LOGIN, listOf(SHARED), listOf(PRIMARY), emptyMap())
        assertEquals(
            "the fixture no longer reproduces the revocation this whole test is about",
            listOf("S"),
            gone.prunedIds,
        )
        val turn1 = reconciledAccounts(list, LOGIN, "L", gone, emptyMap()) { "never-minted" }

        assertEquals(
            "the pruned share is still in the account list. Reconcile must remove a revoked " +
                "shared mailbox: leaving it shows the user a mailbox the server refuses.",
            listOf("L", "Z"),
            turn1.accounts.map { it.id },
        )
        assertEquals(
            "the pruned share was not filed under its login. Nothing is remembered, so the next " +
                "re-grant mints a factory-fresh account: the notifications the user turned OFF " +
                "for that share come back on their own, and its colour, watched folders and " +
                "folded tree are gone.",
            listOf("shared"),
            turn1.memory["L"]?.map { it.jmapAccountId },
        )

        // Turn 2: the server exposes the share again.
        val back = diffLinkedAccounts(
            LOGIN,
            turn1.accounts.filter { it.loginId == "L" },
            listOf(PRIMARY, DiscoveredMailAccount("shared", "Team inbox (renamed)")),
            emptyMap(),
        )
        assertEquals(
            "the fixture no longer re-grants the share this whole test is about",
            listOf("shared"),
            back.toAdd.map { it.jmapAccountId },
        )
        val turn2 = reconciledAccounts(turn1.accounts, LOGIN, "L", back, turn1.memory) { "minted-1" }
        val restored = turn2.accounts.single { it.id == "minted-1" }

        assertEquals(
            "the re-granted share notifies again. The user had turned notifications OFF for this " +
                "shared mailbox; a server-side hiccup that dropped it for one session must not " +
                "turn them back on behind her back.",
            false,
            restored.notificationsEnabled,
        )
        assertEquals(
            "the re-granted share lost its accent colour, so it is no longer the account the " +
                "user recognises at a glance in the drawer and the message list.",
            GREEN,
            restored.color,
        )
        assertEquals(
            "the re-granted share lost its watched folders. That is not cosmetic: those folders " +
                "are what push and new-mail notifications follow, so mail silently stops arriving " +
                "for them.",
            setOf("f1"),
            restored.watchedFolders,
        )
        assertEquals(
            "the re-granted share lost the fold state of its drawer tree, so folders the user had " +
                "opened by hand fold themselves again.",
            mapOf("f2" to true),
            restored.collapsedFolders,
        )
        assertNotEquals(
            "the restored share reused the OLD record id. Its five caches were purged under that " +
                "id when it was pruned; reusing it hands the account a cache that no longer " +
                "matches it.",
            "S",
            restored.id,
        )
        assertEquals(
            "the restored share kept its stale name instead of the one the server has just " +
                "announced, so a mailbox renamed while it was away shows the old label forever.",
            "Team inbox (renamed)",
            restored.accountName,
        )
        assertEquals(
            "the restored share kept a stale unread count, so the drawer badges mail that may no " +
                "longer exist until the first sync lands.",
            0,
            restored.unread,
        )
        assertNull(
            "the restored share kept a stale inbox id. That id names a mailbox on a server that " +
                "re-created the share; resolving it again is the only safe move.",
            restored.inboxId,
        )
        assertEquals(
            "the restored share kept stale server-discovered identities, so the composer's From " +
                "picker can offer an address the server no longer grants.",
            emptyList<StoredIdentity>(),
            restored.serverIdentities,
        )
        assertEquals(
            "the memory was not consumed on recall. A stale entry left behind restores settings " +
                "the user has since changed on the live account, the next time the share blinks.",
            emptyMap<String, List<StoredAccount>>(),
            turn2.memory,
        )
    }

    @Test fun `a restored share is appended at the end, never back in its old slot`() {
        val gone = diffLinkedAccounts(LOGIN, listOf(SHARED), listOf(PRIMARY), emptyMap())
        val turn1 = reconciledAccounts(listOf(LOGIN, SHARED, OTHER_ACCOUNT), LOGIN, "L", gone, emptyMap()) { "x" }
        val back = diffLinkedAccounts(LOGIN, emptyList(), listOf(PRIMARY, SHARE), emptyMap())
        val turn2 = reconciledAccounts(turn1.accounts, LOGIN, "L", back, turn1.memory) { "minted-1" }

        assertEquals(
            "the restored share was given back its old seniority instead of being appended. List " +
                "order IS age order: the duplicate self-healing in diffLinkedAccounts keeps the " +
                "OLDEST record tracking a server account, so a restored record that outranks a " +
                "live one gets the LIVE one pruned — with its caches.",
            listOf("L", "Z", "minted-1"),
            turn2.accounts.map { it.id },
        )
    }

    // ---- an explicit removal beats the memory ----

    @Test fun `removing the share by hand forgets it, and a later re-grant is factory fresh`() {
        val gone = diffLinkedAccounts(LOGIN, listOf(SHARED), listOf(PRIMARY), emptyMap())
        val turn1 = reconciledAccounts(listOf(LOGIN, SHARED), LOGIN, "L", gone, emptyMap()) { "x" }

        // What AccountStore.removeCascading hands it: the records it is about to drop.
        val afterRemoval = forgetRemovedLinked(turn1.memory, listOf(SHARED))
        assertEquals(
            "removing a shared mailbox by hand left its settings filed. Deleting an account IS " +
                "the user's decision; a memory that survives it resurrects the deleted account's " +
                "settings the next time the server offers the share.",
            emptyMap<String, List<StoredAccount>>(),
            afterRemoval,
        )

        val back = diffLinkedAccounts(LOGIN, emptyList(), listOf(PRIMARY, SHARE), emptyMap())
        val turn2 = reconciledAccounts(turn1.accounts, LOGIN, "L", back, afterRemoval) { "minted-1" }
        val minted = turn2.accounts.single { it.id == "minted-1" }

        assertEquals(
            "a share the user had DELETED came back still muted. After a deliberate removal the " +
                "account must be born with the app's defaults, like any account added today.",
            true,
            minted.notificationsEnabled,
        )
        assertNull(
            "a share the user had DELETED came back wearing its old colour.",
            minted.color,
        )
        assertEquals(
            "a share the user had DELETED came back watching its old folders, so push resumes on " +
                "folders nobody asked for.",
            emptySet<String>(),
            minted.watchedFolders,
        )
    }

    @Test fun `removing the login forgets every share filed under it`() {
        val memory = mapOf(
            "L" to listOf(SHARED, SHARED.copy(id = "S2", jmapAccountId = "shared-2")),
            "M" to listOf(SHARED.copy(id = "S3", loginId = "M", jmapAccountId = "shared-3")),
        )

        assertEquals(
            "removing a LOGIN left its shares' settings filed. Those shares borrow the login's " +
                "credential and cannot outlive it; the entries would sit in prefs for good, and " +
                "re-adding the same login would revive settings from an account the user deleted.",
            mapOf("M" to listOf(SHARED.copy(id = "S3", loginId = "M", jmapAccountId = "shared-3"))),
            forgetRemovedLinked(memory, listOf(LOGIN)),
        )
    }

    @Test fun `a second login is neither touched nor forgotten when the first prunes a share`() {
        val otherLogin = StoredAccount(id = "M", server = SERVER, username = "mia@example.test", jmapAccountId = "m-primary")
        val otherMemory = SHARED.copy(id = "S3", loginId = "M", jmapAccountId = "shared-3")
        val gone = diffLinkedAccounts(LOGIN, listOf(SHARED), listOf(PRIMARY), emptyMap())

        val result = reconciledAccounts(
            listOf(LOGIN, SHARED, otherLogin),
            LOGIN,
            "L",
            gone,
            mapOf("M" to listOf(otherMemory)),
        ) { "x" }

        assertEquals(
            "reconciling ONE login rewrote another login's record. Every mutator here is a " +
                "read-modify-write over the single account blob; touching a neighbour is how a " +
                "whole account is lost.",
            listOf(LOGIN, otherLogin),
            result.accounts,
        )
        assertEquals(
            "another login's filed shares were wiped by a prune that had nothing to do with it, " +
                "so ITS re-granted shares come back factory fresh.",
            listOf(otherMemory),
            result.memory["M"],
        )
        assertEquals(
            "the pruned share was not filed under the login that owns it.",
            listOf(SHARED),
            result.memory["L"],
        )
    }

    // ---- the individual rules ----

    @Test fun `rememberPrunedLinked ignores a record with no login or no server account`() {
        val noLogin = SHARED.copy(id = "A", loginId = null)
        val noServerAccount = SHARED.copy(id = "B", jmapAccountId = null)

        assertEquals(
            "a record naming no login, or no server account to match a re-grant against, was " +
                "filed anyway. There is nothing to restore it ONTO, so the entry can only ever " +
                "be dead weight in prefs or land on the wrong account.",
            emptyMap<String, List<StoredAccount>>(),
            rememberPrunedLinked(emptyMap(), listOf(noLogin, noServerAccount)),
        )
    }

    @Test fun `filing the same share twice replaces the entry instead of stacking it`() {
        val newer = SHARED.copy(color = null, notificationsEnabled = true)

        assertEquals(
            "filing a share twice stacked two entries, and recall answers the OLDEST — so the " +
                "user gets back settings she changed two revocations ago.",
            listOf(newer),
            rememberPrunedLinked(rememberPrunedLinked(emptyMap(), listOf(SHARED)), listOf(newer))["L"],
        )
    }

    @Test fun `forgetLinked drops the entry and leaves no ghost key behind`() {
        val memory = rememberPrunedLinked(emptyMap(), listOf(SHARED))

        assertEquals(
            "forgetting the last share under a login left the login as an empty key, so the " +
                "memory grows for every login that ever had a share and never shrinks.",
            emptyMap<String, List<StoredAccount>>(),
            forgetLinked(memory, "L", "shared"),
        )
        assertEquals(
            "forgetting one share dropped its siblings too, so their settings are lost.",
            listOf("shared-2"),
            forgetLinked(
                rememberPrunedLinked(memory, listOf(SHARED.copy(id = "S2", jmapAccountId = "shared-2"))),
                "L",
                "shared",
            )["L"]?.map { it.jmapAccountId },
        )
    }

    @Test fun `recallLinked answers null for a pair it never filed`() {
        val memory = rememberPrunedLinked(emptyMap(), listOf(SHARED))

        assertNull(
            "recall answered something for a share that was never filed, so an account is minted " +
                "wearing another mailbox's settings.",
            recallLinked(memory, "L", "never-seen"),
        )
        assertNull(
            "recall answered across logins: a share filed under one login was handed to another.",
            recallLinked(memory, "M", "shared"),
        )
        assertEquals(
            "recall no longer finds the share that WAS filed, which makes the whole memory inert.",
            SHARED,
            recallLinked(memory, "L", "shared"),
        )
    }

    @Test fun `mintLinkedAccount with nothing remembered is exactly the record shipped today`() {
        assertEquals(
            "minting a share with an EMPTY memory no longer produces the record this app has " +
                "always produced. The memory is comfort state: when it is missing or unreadable " +
                "the behaviour must be today's, to the field.",
            StoredAccount(
                id = "n1",
                server = SERVER,
                username = "leo@example.test",
                accountName = "Team inbox",
                loginId = "L",
                jmapAccountId = "shared",
                protocol = MailProtocol.JMAP,
                authType = AuthType.BASIC,
            ),
            mintLinkedAccount(LOGIN, SHARE, "L", null, "n1"),
        )
    }

    @Test fun `mintLinkedAccount takes the login's wiring as it is today, not as it was filed`() {
        val movedLogin = LOGIN.copy(server = "https://new.example.test", username = "leo2@example.test")
        val minted = mintLinkedAccount(movedLogin, SHARE, "L", SHARED, "n1")

        assertEquals(
            "the restored share kept the OLD server address. It borrows the login's credential, " +
                "so a login that moved leaves its shares pointing at a host that will refuse them.",
            "https://new.example.test",
            minted.server,
        )
        assertEquals(
            "the restored share kept the OLD login username, so it authenticates as somebody the " +
                "server no longer knows.",
            "leo2@example.test",
            minted.username,
        )
        assertTrue(
            "the restored share is no longer linked to the login it was restored under, which " +
                "orphans it: it has no credential of its own and cannot be cascaded away.",
            minted.isLinked,
        )
    }

    /**
     * The blob actually survives storage. `AccountStore.saveLinkedMemory` / `linkedMemory` encode
     * and decode this map with the store's own `Json`, and both are wrapped in a `runCatching`
     * whose whole purpose is to fail QUIETLY. So a map shape kotlinx cannot round-trip would not
     * crash, would not log, and would not fail a single other test: the memory would simply be
     * empty on every read, every re-granted share would come back factory-fresh, and the feature
     * would be inert with nothing on screen saying so.
     *
     * What it does NOT prove: the store's `json` is private, so this builds its own with the
     * same configuration (`ignoreUnknownKeys = true`, `AccountStore`). Reconfigure the
     * store's parser and this test stays green. What it does prove is the one thing that would
     * otherwise fail in total silence: kotlinx can round-trip THIS shape at all. Run on a map
     * carrying the collection-valued settings — the collection-valued ones
     * are the shapes worth pinning, and the nested `StoredIdentity` list is the deepest of them.
     */
    /**
     * The reset list, pinned WHOLE — every field of the minted record, against one literal.
     *
     * The `copy` in [mintLinkedAccount] names seventeen fields, and the assertions above reach
     * seven of them. Deleting any of the other ten — `importPending`, `inboxName`, the OAuth
     * four, `protocol`, `authType`, `loginId`, `jmapAccountId` — kept every other test in this
     * file green. `importPending` alone is a real screen defect: a restored share carrying a
     * stale "needs sign-in" flag is filtered out of the accounts list AND out of the sign-in
     * list (it has a valid credential through its login), so the shared mailbox has no row
     * anywhere. Its KDoc promises "forgetting one must be the loud choice"; this is what makes
     * that true, for all seventeen at once.
     *
     * [DIRTY] carries a non-default value in EVERY field, so a reset that stops covering one
     * shows up here as that field's dirty value surviving.
     */
    @Test fun `the whole minted record is pinned, field by field, against the reset list`() {
        assertEquals(
            "mintLinkedAccount no longer produces this exact record. Something left the reset " +
                "list — a field the SERVER or the login owns is now carried over from the " +
                "remembered record — or something entered it, silently dropping a setting the " +
                "user chose. Read the diff against the literal below before touching this test.",
            StoredAccount(
                // --- reset: identity, the login's wiring, the name the server just announced ---
                id = "n1",
                server = SERVER,
                username = "leo@example.test",
                accountName = "Team inbox",
                loginId = "L",
                jmapAccountId = "shared",
                protocol = MailProtocol.JMAP,
                authType = AuthType.BASIC,
                // --- reset: stale device / sync state, and the short-lived OAuth material ---
                inboxId = null,
                inboxName = "Inbox",
                unread = 0,
                serverIdentities = emptyList(),
                importPending = false,
                oauthAccessToken = "",
                oauthAccessExpiresAt = 0,
                oauthTokenEndpoint = "",
                oauthClientId = "",
                // --- kept: everything the USER chose ---
                syncWindow = SyncWindow.YEAR_1,
                notificationsEnabled = false,
                uploadSentCopy = false,
                showOnlySubscribedFolders = true,
                watchedFolders = setOf("f1", "f2"),
                collapsedFolders = mapOf("f3" to true, "f4" to false),
                color = 0xFF0000FF.toInt(),
                signature = "-- Leo",
                identities = listOf(StoredIdentity(id = "mine", name = "Leo", email = "leo@example.test")),
                defaultIdentityId = "mine",
                imapHost = "imap.example.test",
                imapPort = 1993,
                imapSecurity = ConnectionSecurity.STARTTLS,
                smtpHost = "smtp.example.test",
                smtpPort = 1587,
                smtpSecurity = ConnectionSecurity.NONE,
                pgpEnabled = true,
                pgpSignKeyId = 42L,
                pgpEncryptByDefault = true,
            ),
            mintLinkedAccount(LOGIN, SHARE, "L", DIRTY, "n1"),
        )
    }

    /**
     * The `pinPrimaryId` branch, EXECUTED. It is the function's only write by index, it was moved
     * into [reconciledAccounts] by this change, and nothing else in this file reaches it — every
     * other fixture login already carries its `jmapAccountId`. Pinning the login's own account id
     * on first discovery is what later reconciles use to tell the login apart from its shares: get
     * it wrong and every real share looks revoked, which prunes them all with their caches.
     */
    @Test fun `the login's own account id is pinned in place on first discovery`() {
        val unpinned = LOGIN.copy(jmapAccountId = null)
        val list = listOf(unpinned, OTHER_ACCOUNT)
        val diff = diffLinkedAccounts(unpinned, emptyList(), listOf(PRIMARY), emptyMap())
        var minted = 0
        val turn = reconciledAccounts(list, unpinned, "L", diff, emptyMap()) { "n${++minted}" }

        assertEquals(
            "the login is no longer pinned IN PLACE with the account id the session announced. " +
                "Without it, later reconciles cannot tell the login from its shares and prune " +
                "every real shared mailbox, with the five tables of cache behind each one.",
            listOf(unpinned.copy(jmapAccountId = "primary"), OTHER_ACCOUNT),
            turn.accounts,
        )
        assertEquals(
            "pinning the login now files something in the memory. Nothing was pruned, so nothing " +
                "may be remembered: a blob is written on a device that has never seen a share.",
            emptyMap<String, List<StoredAccount>>(),
            turn.memory,
        )
    }

    @Test fun `the memory round-trips through the store's own Json`() {
        val json = Json { ignoreUnknownKeys = true }
        val memory: Map<String, List<StoredAccount>> =
            mapOf("L" to listOf(SHARED), "L2" to listOf(SECOND_SHARE))
        val encoded = json.encodeToString(memory)

        assertEquals(
            "the memory no longer survives its own encode/decode. Both sides swallow the failure " +
                "on purpose, so this comes back EMPTY at runtime with nothing said: every " +
                "re-granted share is minted factory-fresh and the whole fix is inert.",
            memory,
            json.decodeFromString<Map<String, List<StoredAccount>>>(encoded),
        )
    }

    private companion object {
        const val SERVER = "https://mail.example.test"
        val GREEN: Int? = 0xFF00FF00.toInt()

        val PRIMARY = DiscoveredMailAccount("primary", "Leo")
        val SHARE = DiscoveredMailAccount("shared", "Team inbox")

        val LOGIN = StoredAccount(
            id = "L",
            server = SERVER,
            username = "leo@example.test",
            accountName = "Leo",
            jmapAccountId = "primary",
        )

        /** The share as the user had configured it before the server dropped it. */
        val SHARED = StoredAccount(
            id = "S",
            server = SERVER,
            username = "leo@example.test",
            accountName = "Team inbox (old name)",
            loginId = "L",
            jmapAccountId = "shared",
            inboxId = "stale-inbox",
            unread = 7,
            notificationsEnabled = false,
            color = 0xFF00FF00.toInt(),
            watchedFolders = setOf("f1"),
            collapsedFolders = mapOf("f2" to true),
            serverIdentities = listOf(StoredIdentity(id = "stale", name = "Team", email = "team@example.test")),
        )

        /**
         * A remembered share with a NON-DEFAULT value in every field, so the whole-record
         * assertion sees any field that quietly leaves the reset list.
         */
        val DIRTY = StoredAccount(
            id = "old-id",
            server = "https://old.example.test",
            username = "old@example.test",
            jmapAccountId = "old-jmap",
            loginId = "old-login",
            accountName = "Old name",
            inboxId = "old-inbox",
            inboxName = "Boite",
            unread = 9,
            syncWindow = SyncWindow.YEAR_1,
            notificationsEnabled = false,
            uploadSentCopy = false,
            showOnlySubscribedFolders = true,
            watchedFolders = setOf("f1", "f2"),
            collapsedFolders = mapOf("f3" to true, "f4" to false),
            color = 0xFF0000FF.toInt(),
            signature = "-- Leo",
            identities = listOf(StoredIdentity(id = "mine", name = "Leo", email = "leo@example.test")),
            serverIdentities = listOf(StoredIdentity(id = "old", name = "Team", email = "team@example.test")),
            defaultIdentityId = "mine",
            importPending = true,
            protocol = MailProtocol.IMAP,
            authType = AuthType.OAUTH,
            oauthAccessToken = "stale-token",
            oauthAccessExpiresAt = 1234L,
            oauthTokenEndpoint = "https://old.example.test/token",
            oauthClientId = "old-client",
            imapHost = "imap.example.test",
            imapPort = 1993,
            imapSecurity = ConnectionSecurity.STARTTLS,
            smtpHost = "smtp.example.test",
            smtpPort = 1587,
            smtpSecurity = ConnectionSecurity.NONE,
            pgpEnabled = true,
            pgpSignKeyId = 42L,
            pgpEncryptByDefault = true,
        )

        /** A share under ANOTHER login, so the round-trip carries more than one key. */
        val SECOND_SHARE = StoredAccount(
            id = "S2",
            server = SERVER,
            username = "iris@example.test",
            accountName = "Archive",
            loginId = "L2",
            jmapAccountId = "archive",
            syncWindow = SyncWindow.DAYS_30,
            showOnlySubscribedFolders = true,
            pgpEnabled = true,
            pgpSignKeyId = 42L,
        )

        /** A standalone account that must sit undisturbed through both turns. */
        val OTHER_ACCOUNT = StoredAccount(id = "Z", server = SERVER, username = "zoe@example.test")
    }
}
