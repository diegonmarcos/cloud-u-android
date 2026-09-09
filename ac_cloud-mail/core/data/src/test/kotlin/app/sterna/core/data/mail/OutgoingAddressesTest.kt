package app.sterna.core.data.mail

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The source-side refusal behind the SMTP envelope/header CR/LF filter: a send whose
 */
class OutgoingAddressesTest {

    @Test fun plainAddressesAreAccepted() {
        requireSingleLineAddresses(listOf("alice@example.com", "Bob <bob@example.com>", ""))
    }

    @Test fun crlfInARecipientIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSingleLineAddresses(
                listOf("alice@example.com", "bob@example.com\r\nRCPT TO:<victim@evil.com>"),
            )
        }
    }

    @Test fun bareCrOrLfIsRejectedToo() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSingleLineAddresses(listOf("bob@example.com\nBcc: victim@evil.com"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireSingleLineAddresses(listOf("bob@example.com\rX"))
        }
    }
}
