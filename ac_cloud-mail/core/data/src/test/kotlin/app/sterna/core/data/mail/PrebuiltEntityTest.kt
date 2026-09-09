package app.sterna.core.data.mail

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pre-built entity seam, decoupled from PGP.
 */
class PrebuiltEntityTest {

    private val entity = "Content-Type: multipart/report; report-type=disposition-notification;\r\n"

    // ---- what queueing writes -----------------------------------------------------------------

    /**
     * THE DECOUPLING. An entity with NO PGP mode at all is stored, exactly as one with a mode is.
     */
    @Test fun `an entity with no pgp mode is written beside the row`() {
        assertEquals(
            OutboxPayload(entityFile = entity, redactBody = false),
            outboxPayload(entity, pgpMode = null),
        )
        assertEquals(
            "PgpMode.OFF is 'no PGP', not 'no entity'",
            OutboxPayload(entityFile = entity, redactBody = false),
            outboxPayload(entity, PgpMode.OFF),
        )
    }

    /** And what the relaxation must not break: the two PGP shapes are unchanged. */
    @Test fun `the pgp paths keep the behaviour they had`() {
        assertEquals(
            "a signed message keeps its body in the row: it is public, and the Outbox shows it",
            OutboxPayload(entityFile = entity, redactBody = false),
            outboxPayload(entity, PgpMode.SIGN),
        )
        assertEquals(
            "an encrypted message must keep NO plaintext at rest",
            OutboxPayload(entityFile = entity, redactBody = true),
            outboxPayload(entity, PgpMode.ENCRYPT),
        )
    }

    /**
     * The mode that encrypts WITHOUT signing blanks the row exactly like the signed one. The
     */
    @Test fun `an unsigned encrypted message keeps no plaintext at rest either`() {
        assertEquals(
            "an unsigned encrypted row kept its plaintext body at rest: the encryption is " +
                "undone for anyone reading the database.",
            OutboxPayload(entityFile = entity, redactBody = true),
            outboxPayload(entity, PgpMode.ENCRYPT_UNSIGNED),
        )
    }

    /**
     * Blanking the row still needs BOTH the mode and the entity. A row marked ENCRYPT whose
     */
    @Test fun `a mode without an entity blanks nothing and writes nothing`() {
        assertEquals(
            OutboxPayload(entityFile = null, redactBody = false),
            outboxPayload(prebuiltEntity = null, pgpMode = PgpMode.ENCRYPT),
        )
        assertEquals(
            OutboxPayload(entityFile = null, redactBody = false),
            outboxPayload(prebuiltEntity = null, pgpMode = null),
        )
    }

    // ---- what delivery reads back --------------------------------------------------------------

    /** A row with no entity file delivers normally: body, HTML and attachments from the row. */
    @Test fun `a row naming no file has no entity`() {
        assertNull(prebuiltEntityAt(null) { error("must not read anything") })
    }

    /** A row naming a file is delivered from that file, verbatim — whatever the PGP mode says. */
    @Test fun `a row naming a file is delivered from it, byte for byte`() {
        assertEquals(entity, prebuiltEntityAt("/outbox/7/prebuilt-entity.mime") { entity })
    }

    /**
     * An unreadable file FAILS the delivery. It must never fall back to the row's own body: that
     */
    @Test fun `a file that cannot be read fails the delivery instead of sending a shell`() {
        val thrown = runCatching { prebuiltEntityAt("/outbox/7/gone.mime") { null } }.exceptionOrNull()

        assertTrue("a missing payload must throw, not return null", thrown is IllegalStateException)
        assertEquals(
            "the message no longer mentions encryption: a receipt is neither encrypted nor signed",
            "The prepared message payload is missing.",
            thrown?.message,
        )
    }

    /** Read ONCE, above the protocol fork, so both branches see the same value. */
    @Test fun `the file is read exactly once`() {
        var reads = 0
        prebuiltEntityAt("/outbox/7/prebuilt-entity.mime") { reads++; entity }

        assertEquals(1, reads)
    }

    /** The file name changed with the field; old rows keep the absolute path they were given. */
    @Test fun `the entity file is no longer named after pgp`() {
        assertEquals("prebuilt-entity.mime", PREBUILT_ENTITY_FILE)
    }

    // ---- the call sites, read out of the shipped source: a LAST RESORT --------------------------

    /**
     * SOURCE LINT, NOT A BEHAVIOUR TEST — `MailRepository` needs Room, a `Context` and a socket, so
     */
    @Test fun `both protocols route on the entity, never on the pgp mode`() {
        val source = repositorySource()
        val lines = source.lines().map { it.trim() }

        assertTrue(
            "staging must key on the entity alone. Nothing else may be added to the condition.",
            "if (payload.entityFile != null) {" in lines,
        )
        assertTrue(
            "the JMAP import-and-submit route must key on the entity, not on the PGP mode: " +
                "Email/set would re-encode the receipt and it would stop being one",
            "val sentEmailId = if (prebuiltEntity != null) {" in lines,
        )
        assertTrue(
            "the SMTP/IMAP message must carry the entity",
            "prebuiltEntity = prebuiltEntity," in lines,
        )
        assertTrue(
            "the entity must be read once, above the fork, so the two branches agree",
            "val prebuiltEntity = prebuiltEntityAt(item.pgpEntityPath) { path ->" in lines,
        )
    }

    /**
     * SOURCE LINT, on BOTH call sites. `OutboxLogic.canEdit` only refuses a row it is TOLD about:
     */
    @Test fun `the edit guard is told whether the row can be rebuilt, at both call sites`() {
        assertTrue(
            "the guard at the state flip must ask about the row's own mode and entity path",
            "val unreplayable = OutboxLogic.isUnreplayablePrebuiltEntity(item.pgpMode, item.pgpEntityPath)"
                in repositorySource().lines().map { it.trim() },
        )
        assertTrue(
            "…and so must the guard behind the button",
            "OutboxLogic.isUnreplayablePrebuiltEntity(item.pgpMode, item.pgpEntityPath)"
                in locate("app/src/main/kotlin/app/sterna/ui/outbox/OutboxScreen.kt")
                    .readText().lines().map { it.trim() },
        )
        listOf(
            "if (!OutboxLogic.canEdit(item.pgpMode, item.state, unreplayable)) return null" to repositorySource(),
            "if (OutboxLogic.canEdit(item.pgpMode, item.state, unreplayable)) {" to
                locate("app/src/main/kotlin/app/sterna/ui/outbox/OutboxScreen.kt").readText(),
        ).forEach { (line, source) ->
            assertTrue(
                "the whole line '$line' must be there: a literal argument in its place is a guard " +
                    "that answers about nothing",
                line in source.lines().map { it.trim() },
            )
        }
    }

    /**
     * SOURCE LINT. `learnRecipients` guards the contacts list, and its default is `true` — drop the
     * argument at the one call site that needs `false` and nothing anywhere goes red.
     */
    @Test fun `the contacts list is taught through the one decision function`() {
        val lines = repositorySource().lines().map { it.trim() }

        assertTrue(
            "the addresses learned must come from recipientsToLearn, not straight from the row",
            "recipientsToLearn(recipients + ccTrimmed + bccTrimmed, learnRecipients)" in lines,
        )
        assertTrue(
            "nothing is learned when there is nothing to learn",
            "?.let { runCatching { rememberRecipients(it) } }" in lines,
        )
    }

    private fun repositorySource(): String =
        locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
