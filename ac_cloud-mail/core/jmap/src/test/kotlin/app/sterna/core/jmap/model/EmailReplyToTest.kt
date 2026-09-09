package app.sterna.core.jmap.model

import app.sterna.core.jmap.JmapClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Email.replyTo` (RFC 5322 §3.6.2 / RFC 8621 §4.1.2) exists on the model and costs nothing to a
 */
class EmailReplyToTest {
    private val json = JmapClient.DefaultJson

    @Test fun anEmailWithoutThePropertyStillParsesAndHasNoReplyTo() {
        val e = json.decodeFromString(
            Email.serializer(),
            """{"id":"e1","subject":"Hi","from":[{"name":"Alex","email":"alex@example.org"}]}""",
        )

        assertEquals(emptyList<EmailAddress>(), e.replyTo)
        assertEquals("alex@example.org", e.from.single().email)
    }

    @Test fun anExplicitNullIsCoercedToNoReplyTo() {
        // JMAP types these fields `EmailAddress[]|null`, so the wire really does carry nulls.
        val e = json.decodeFromString(Email.serializer(), """{"id":"e1","replyTo":null}""")

        assertEquals(emptyList<EmailAddress>(), e.replyTo)
    }

    @Test fun theAddressesAreParsedInOrderWhenPresent() {
        val e = json.decodeFromString(
            Email.serializer(),
            """{"id":"e1","from":[{"email":"no-reply@example.org"}],""" +
                """"replyTo":[{"name":"Sterna list","email":"list@lists.example.org"},""" +
                """{"email":"support@example.org"}]}""",
        )

        assertEquals(
            listOf("list@lists.example.org", "support@example.org"),
            e.replyTo.map { it.email },
        )
        assertEquals("Sterna list", e.replyTo.first().name)
        assertEquals("no-reply@example.org", e.from.single().email)
    }

    @Test fun replyToRoundTripsThroughTheEncoder() {
        val original = Email(
            id = "e1",
            replyTo = listOf(EmailAddress(name = "Support", email = "support@example.org")),
        )

        val back = json.decodeFromString(Email.serializer(), json.encodeToString(Email.serializer(), original))

        assertEquals(original.replyTo, back.replyTo)
    }
}
