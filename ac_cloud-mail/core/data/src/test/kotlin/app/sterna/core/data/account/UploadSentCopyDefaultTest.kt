package app.sterna.core.data.account

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a record ALREADY ON DISK decodes to, once `uploadSentCopy` exists.
 */
class UploadSentCopyDefaultTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** A record as a device that upgraded into this branch has it: no `uploadSentCopy` key. */
    private val preBranch =
        """[{"id":"a1","server":"imap.example.test","username":"demo@example.test",
           |"accountName":"Demo","protocol":"IMAP","imapHost":"imap.example.test",
           |"smtpHost":"smtp.example.test"}]""".trimMargin().replace("\n", "")

    @Test
    fun `a record written before this field still uploads its Sent copy`() {
        val decoded = json.decodeFromString<List<StoredAccount>>(preBranch).single()
        assertTrue(
            "an account stored before 'uploadSentCopy' existed must decode to true — i.e. to the " +
                "behaviour it was written under. Any other default silently stops archiving sent " +
                "mail for every existing account on the update, and nothing on screen says so.",
            decoded.uploadSentCopy,
        )
    }

    @Test
    fun `a record that stored the setting off decodes as off`() {
        val stored = preBranch.replace("\"id\":\"a1\"", "\"id\":\"a1\",\"uploadSentCopy\":false")
        val decoded = json.decodeFromString<List<StoredAccount>>(stored).single()
        assertFalse(
            "the stored value must be read back. If this is true the field is write-only — the " +
                "toggle would answer 'on' again on the next cold start, whatever the user chose.",
            decoded.uploadSentCopy,
        )
    }
}
