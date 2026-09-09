package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT. The two `withReadTimeout` wrappers — one plain, one suspending — cannot call each
 */
class ReadTimeoutTwinsTest {

    @Test fun `the two read-timeout wrappers are the same wrapper`() {
        val plain = ImapSource.functionBody("ImapClient", "withReadTimeout")
        val suspending = ImapSource.functionBody("ImapClient", "withReadTimeoutSuspending")

        assertEquals(
            "withReadTimeout and withReadTimeoutSuspending have drifted apart. Every pooled IMAP " +
                "call goes through the SUSPENDING one and no behaviour test reaches it, so " +
                "whatever is only in the other is unguarded:\nplain:\n" + plain.joinToString("\n") +
                "\nsuspending:\n" + suspending.joinToString("\n"),
            plain,
            suspending,
        )
    }

    @Test fun `neither of them arms the socket itself`() {
        // The rule behind the rule. As long as the assignment lives in `armReadTimeout`, the
        // budget tests that go through the plain wrapper are also testing the suspending one. A
        // wrapper that set `soTimeout` on its own would be back to being a copy nobody executes.
        listOf("withReadTimeout", "withReadTimeoutSuspending").forEach { wrapper ->
            val body = ImapSource.functionBody("ImapClient", wrapper)
            assertEquals(
                "$wrapper touches socket.soTimeout directly again:\n" + body.joinToString("\n"),
                emptyList<String>(),
                body.filter { "soTimeout" in it },
            )
            assertTrue(
                "$wrapper no longer arms the read timeout at all:\n" + body.joinToString("\n"),
                body.any { it == "val previous = armReadTimeout(millis)" },
            )
            assertTrue(
                "$wrapper no longer restores the previous timeout on the way out:\n" + body.joinToString("\n"),
                body.any { it == "disarmReadTimeout(previous)" },
            )
        }
    }

    @Test fun `the shared arming is what a budget actually sets`() {
        // The one place, pinned whole: a `coerceAtLeast` gone means a negative budget reaches the
        // socket; a missing assignment means no bound at all, on every call the app makes.
        assertEquals(
            "ImapSession.armReadTimeout is no longer, line for line, what this was written against",
            listOf(
                "{",
                "val previous = socket.soTimeout",
                "socket.soTimeout = millis.coerceAtLeast(0)",
                "return previous",
                "}",
            ),
            ImapSource.functionBody("ImapClient", "armReadTimeout"),
        )
    }
}
