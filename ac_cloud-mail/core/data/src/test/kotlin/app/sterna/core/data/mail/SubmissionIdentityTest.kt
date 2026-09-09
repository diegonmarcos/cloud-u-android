package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SubmissionIdentity.choose] — the rule EXECUTED, with its arguments pinned.
 */
class SubmissionIdentityTest {

    private val house = Identity(id = "i-house", name = "Théo", email = "theo@tb.pro")
    private val other = Identity(id = "i-other", name = "Théo B", email = "contact@tb.pro")

    /** The ordinary case: the chosen address IS a server identity, so the server derives it all. */
    @Test fun `a chosen address the server knows submits under it and names no envelope`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "contact@tb.pro",
            serverIdentities = listOf(house, other),
            onBehalf = false,
        )!!

        assertEquals("i-other", choice.identity.id)
        assertNull("the server derives the envelope from the matched identity", choice.envelopeMailFrom)
    }

    /**
     * THE defect. The typed address has no server identity: the submission still goes out under
     */
    @Test fun `a chosen address the server does not know names it in the envelope`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "theo@mydomain.com",
            serverIdentities = listOf(house, other),
            onBehalf = false,
        )!!

        assertEquals("i-house", choice.identity.id)
        assertEquals("theo@mydomain.com", choice.envelopeMailFrom)
    }

    /**
     * A delegated sub-account (issue #31) is the case where From and envelope DIFFER on purpose,
     * verified against Stalwart. An envelope here would break a send that works today.
     */
    @Test fun `a delegated send never names an envelope`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "theo@mydomain.com",
            serverIdentities = listOf(house, other),
            onBehalf = true,
        )!!

        assertEquals("i-house", choice.identity.id)
        assertNull("issue #31: the login's identity supplies the envelope on purpose", choice.envelopeMailFrom)
    }

    /** No chosen address: the default identity, exactly as before, and nothing named. */
    @Test fun `no chosen address falls back to the first identity with no envelope`() {
        val fromNull = SubmissionIdentity.choose(null, listOf(house, other), onBehalf = false)!!
        assertEquals("i-house", fromNull.identity.id)
        assertNull(fromNull.envelopeMailFrom)

        val fromBlank = SubmissionIdentity.choose("   ", listOf(house, other), onBehalf = false)!!
        assertEquals("i-house", fromBlank.identity.id)
        assertNull("a blank address chose nothing; it must not become a mailFrom", fromBlank.envelopeMailFrom)
    }

    /** An account with no server identity at all: the caller raises its own error. */
    @Test fun `an account with no server identity yields null`() {
        assertNull(SubmissionIdentity.choose("theo@tb.pro", emptyList(), onBehalf = false))
        assertNull(SubmissionIdentity.choose(null, emptyList(), onBehalf = false))
    }

    /**
     * Case and surrounding spaces are not a different address. `StoredAccount` dedupes
     */
    @Test fun `matching trims both sides and ignores case`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = " Ann@Example.ORG ",
            serverIdentities = listOf(house, Identity(id = "i-ann", email = "ann@example.org")),
            onBehalf = false,
        )!!

        assertEquals("i-ann", choice.identity.id)
        assertNull(choice.envelopeMailFrom)
    }

    /** …and the server's side of the comparison is trimmed too. */
    @Test fun `a server identity stored with spaces still matches`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "ann@example.org",
            serverIdentities = listOf(house, Identity(id = "i-ann", email = " ann@example.org ")),
            onBehalf = false,
        )!!

        assertEquals("i-ann", choice.identity.id)
        assertNull(choice.envelopeMailFrom)
    }

    /** The chosen address reaches the envelope trimmed — a `MAIL FROM:< a@b >` is not an address. */
    @Test fun `the envelope address is trimmed`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "  theo@mydomain.com  ",
            serverIdentities = listOf(house),
            onBehalf = false,
        )!!

        assertEquals("theo@mydomain.com", choice.envelopeMailFrom)
    }
}
