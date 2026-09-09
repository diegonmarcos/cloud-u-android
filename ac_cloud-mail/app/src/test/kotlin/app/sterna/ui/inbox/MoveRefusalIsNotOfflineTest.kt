package app.sterna.ui.inbox

import app.sterna.R
import app.sterna.core.data.mail.ImapNumberingUnconfirmed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A move the server would not confirm the numbering of is not an outage, and the list must never
 */
class MoveRefusalIsNotOfflineTest {

    @Test fun `a refusal gets the generic sentence, whatever the device answers`() {
        assertEquals(
            "a refused move on a device with a live link must say what every other failed action " +
                "of this list says",
            R.string.status_action_failed,
            actionFailureMessage(REFUSAL, online = true),
        )
        assertEquals(
            "and the same on a device with no link at all: a server that will not state a " +
                "numbering has nothing to do with the radio, and the swipe reached it to be told " +
                "so — telling that user they are offline is #65 over again",
            R.string.status_action_failed,
            actionFailureMessage(REFUSAL, online = false),
        )
    }

    /**
     * The structural half of the same claim, and the reason the one above cannot be got round from
     */
    @Test fun `no constructor lets the refusal carry a cause for the walk to find`() {
        val carriers = ImapNumberingUnconfirmed::class.java.constructors.filter { constructor ->
            constructor.parameterTypes.any { Throwable::class.java.isAssignableFrom(it) }
        }
        assertEquals(
            "ImapNumberingUnconfirmed can now be given a cause: " + carriers.joinToString { it.toString() } +
                ". Whatever a throw site puts there is walked by isNetworkFailure, and a transport " +
                "failure underneath turns every refused move into \"you are offline\" on a phone " +
                "whose link is fine — while this file's other assertions stay green, because they " +
                "build the refusal without one.",
            emptyList<Any>(),
            carriers,
        )
        assertNull("and the refusal as the service raises it carries nothing either", REFUSAL.cause)
    }

    /**
     * The vacuity guard: were the two sentences one resource, everything above would hold whatever
     * the decision did.
     */
    @Test fun `the generic sentence is not the offline one`() {
        assertNotEquals(R.string.status_action_failed, R.string.status_action_offline)
    }

    private companion object {
        /** The refusal exactly as `ImapMailService` raises it for a swiped archive of an INBOX
         *  whose server stated no numbering: message "The server stated no UIDVALIDITY for INBOX;
         *  a destroy frozen under null was refused". */
        val REFUSAL = ImapNumberingUnconfirmed("INBOX", null)
    }
}
