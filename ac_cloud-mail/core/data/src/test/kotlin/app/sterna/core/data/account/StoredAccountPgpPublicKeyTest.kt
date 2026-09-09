package app.sterna.core.data.account

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The public-key cache must cost existing installs nothing: accounts are kotlinx JSON in a prefs
 */
class StoredAccountPgpPublicKeyTest {

    /** The parser `AccountStore` itself uses (AccountStore). Anything else proves nothing
     *  about what the app will do with an old blob. */
    private val json = Json { ignoreUnknownKeys = true }

    /** An account exactly as 1.5.3 wrote it: PGP set up, no `pgpPublicKey` key anywhere. */
    private val legacy = """
        {"id":"acc-1","server":"https://mail.example.test","username":"iris@example.test",
         "accountName":"Iris (work)","inboxName":"INBOX","unread":7,"color":-16711936,
         "syncWindow":"DAYS_30","watchedFolders":["Archive"],"uploadSentCopy":false,
         "identities":[{"id":"i1","name":"Iris","email":"iris@example.test","signature":"Iris"}],
         "defaultIdentityId":"i1","protocol":"IMAP","imapHost":"imap.example.test","imapPort":993,
         "smtpHost":"smtp.example.test","smtpPort":587,
         "pgpEnabled":true,"pgpSignKeyId":1234605616436508552,"pgpEncryptByDefault":true}
    """.trimIndent()

    @Test fun `an account stored before the field reads back with an empty cache`() {
        val account = json.decodeFromString(StoredAccount.serializer(), legacy)
        assertEquals(
            "an old account decoded to something other than an empty cache — either the field " +
                "lost its default, or it is not the empty string, and every account configured " +
                "before this version would announce those bytes.",
            "",
            account.pgpPublicKey,
        )
    }

    @Test fun `and every other field it had is intact`() {
        val account = json.decodeFromString(StoredAccount.serializer(), legacy)
        assertEquals("acc-1", account.id)
        assertEquals("https://mail.example.test", account.server)
        assertEquals("iris@example.test", account.username)
        assertEquals("Iris (work)", account.accountName)
        assertEquals("INBOX", account.inboxName)
        assertEquals(7, account.unread)
        assertEquals(SyncWindow.DAYS_30, account.syncWindow)
        assertEquals(setOf("Archive"), account.watchedFolders)
        assertEquals(false, account.uploadSentCopy)
        assertEquals(-16711936, account.color)
        assertEquals("i1", account.defaultIdentityId)
        assertEquals(listOf("i1"), account.identities.map { it.id })
        assertEquals("Iris", account.identities.single().signature)
        assertEquals(MailProtocol.IMAP, account.protocol)
        assertEquals("imap.example.test", account.imapHost)
        assertEquals(993, account.imapPort)
        assertEquals("smtp.example.test", account.smtpHost)
        assertEquals(587, account.smtpPort)
        assertEquals(true, account.pgpEnabled)
        assertEquals(0x1122334455667788L, account.pgpSignKeyId)
        assertEquals(true, account.pgpEncryptByDefault)
    }

    @Test fun `a list blob written before the field decodes too`() {
        // What is actually stored is a LIST under one prefs key: the gate decodes the whole blob or
        // refuses the lot, so one undecodable account is every account gone.
        val accounts = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(StoredAccount.serializer()),
            "[$legacy]",
        )
        assertEquals(1, accounts.size)
        assertEquals("", accounts.single().pgpPublicKey)
        assertEquals(0x1122334455667788L, accounts.single().pgpSignKeyId)
    }
}
