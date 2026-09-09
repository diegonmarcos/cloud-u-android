package app.sterna.core.jmap.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushMessagePayloadTest {

    @Test fun parsesPushVerification() {
        val payload = PushMessagePayload.parse(
            """{"@type":"PushVerification","pushSubscriptionId":"ps1","verificationCode":"c42"}""",
        )
        val verification = payload as PushMessagePayload.Verification
        assertEquals("ps1", verification.pushSubscriptionId)
        assertEquals("c42", verification.verificationCode)
    }

    @Test fun parsesStateChange() {
        val payload = PushMessagePayload.parse(
            """{"@type":"StateChange","changed":{"acc1":{"Email":"s1","Mailbox":"s2"}}}""",
        )
        val change = payload as PushMessagePayload.Change
        assertTrue(change.stateChange.emailChanged("acc1"))
        assertEquals("s1", change.stateChange.changed["acc1"]?.get("Email"))
    }

    // A login-level StateChange can carry several JMAP account ids (issue #31): each
    // watched id must match independently so the delivery fan-out reaches sub-accounts.
    @Test fun parsesStateChange_multipleAccounts() {
        val payload = PushMessagePayload.parse(
            """{"@type":"StateChange","changed":{"s":{"Email":"e1","Mailbox":"m1"},"u":{"Email":"e2"}}}""",
        )
        val change = (payload as PushMessagePayload.Change).stateChange
        assertTrue(change.emailChanged("s"))
        assertTrue(change.emailChanged("u"))
        assertFalse(change.emailChanged("unknown"))
        assertEquals("e2", change.changed["u"]?.get("Email"))
    }

    // A change touching only non-Email types (or only other accounts) must not read as
    // new mail for the probed account.
    @Test fun stateChange_withoutEmailForAccount_isNotMail() {
        val payload = PushMessagePayload.parse(
            """{"@type":"StateChange","changed":{"s":{"Email":"e1"},"u":{"Mailbox":"m1"}}}""",
        )
        val change = (payload as PushMessagePayload.Change).stateChange
        assertFalse(change.emailChanged("u"))
        assertTrue(change.emailChanged("s"))
    }

    @Test fun unknownTypeGarbageOrPartial_isNull() {
        assertNull(PushMessagePayload.parse("""{"@type":"SomethingElse"}"""))
        assertNull(PushMessagePayload.parse("not json at all"))
        assertNull(PushMessagePayload.parse("""{"@type":"PushVerification","pushSubscriptionId":"ps1"}"""))
        assertNull(PushMessagePayload.parse("""{"no":"type"}"""))
    }
}
