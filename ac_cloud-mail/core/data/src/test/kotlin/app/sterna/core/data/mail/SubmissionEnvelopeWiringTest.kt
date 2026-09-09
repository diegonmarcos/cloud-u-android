package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, exactly as [DraftAddressingWiringTest] does:
 */
class SubmissionEnvelopeWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped, so prose can neither satisfy
     *  a rule nor break one. Whole lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    @Test fun theEnvelopeNamesEveryRecipientTheMessageHas() {
        assertEquals(
            "the envelope's rcptTo must carry to + cc + bcc, like the SMTP path's RCPT TO " +
                "(SmtpClient.kt:334): an amputated list does not fall back to the message's own " +
                "recipients, it silently delivers to nobody but the To (#172)",
            listOf("rcptTo = (recipients + ccAddrs + bccAddrs).map { a -> a.email },"),
            codeLinesNaming(bodyOf("performDelivery"), "rcptTo ="),
        )
    }

    @Test fun bothSubmissionPathsAreHandedTheEnvelope() {
        assertEquals(
            "both submissions must carry it — importAndSendEmail for a PGP/MIME message and " +
                "sendEmail for an ordinary one; a path left without it sends under the identity " +
                "the server picked, which is the defect",
            listOf("envelope = envelope,", "envelope = envelope,"),
            codeLinesNaming(bodyOf("performDelivery"), "envelope = envelope"),
        )
    }

    @Test fun onlyTheAbsenceOfAnEnvelopeAddressCanWithholdTheEnvelope() {
        assertEquals(
            "the envelope must be built whenever choose named an address, on that condition and " +
                "no other: any narrowing here (a ?.takeIf, an extra flag) both brings #172 back " +
                "for ordinary accounts and can pose an envelope on the delegated path (#31), " +
                "while every executable test stays green",
            listOf("val envelope = choice.envelopeMailFrom?.let {"),
            codeLinesNaming(bodyOf("performDelivery"), "choice.envelopeMailFrom"),
        )
    }

    @Test fun theChoiceIsPutToTheFunctionATestCanRun() {
        assertEquals(
            "performDelivery must ask SubmissionIdentity.choose — the pure function a test can " +
                "execute — and hand it the chosen address, the server identities and onBehalf, " +
                "not re-decide it inline where nothing reaches it",
            listOf("val choice = SubmissionIdentity.choose(fromEmail, serverIdentities, onBehalf)"),
            codeLinesNaming(bodyOf("performDelivery"), "SubmissionIdentity.choose("),
        )
    }
}
