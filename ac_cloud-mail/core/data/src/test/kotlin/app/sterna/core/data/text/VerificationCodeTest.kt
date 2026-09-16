package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The "Copy Code" extractor's promise is table-shaped: for a real-shaped message it must return
 * exactly the code a person would copy, and for the look-alikes it must return null rather than a
 * guess. The table lives HERE, in the repository — not in a commit message — so the same rows run
 * on every test execution.
 */
class VerificationCodeTest {

    private data class Case(
        val name: String,
        val subject: String?,
        val body: String,
        val html: String? = null,
        val expected: String?,
    )

    private fun run(subject: String?, body: String, html: String? = null): String? =
        extractVerificationCode(subject, body, html)

    @Test
    fun positiveCasesReturnTheCode() {
        val cases = listOf(
            Case(
                "six-digit code introduced by a phrase",
                "Re: Login",
                "Hello,\n\nYour verification code is 482913.\n\nIt expires in ten minutes.",
                expected = "482913",
            ),
            Case(
                "four-digit code introduced by a phrase",
                null,
                "Your code is 1234.",
                expected = "1234",
            ),
            Case(
                "five-digit code introduced by a phrase",
                null,
                "Security code: 76543",
                expected = "76543",
            ),
            Case(
                "seven-digit code introduced by a phrase",
                null,
                "Enter 7654321\n\nThis is your sign-in code.",
                expected = "7654321",
            ),
            Case(
                "eight-digit code introduced by a phrase",
                null,
                "Your code is 12345678.",
                expected = "12345678",
            ),
            Case(
                "alphanumeric code",
                null,
                "Your code is A1B2C3",
                expected = "A1B2C3",
            ),
            Case(
                "code split by a space",
                null,
                "Your code is 123 456",
                expected = "123456",
            ),
            Case(
                "code split by a hyphen with a letter prefix",
                null,
                "Your verification code is AB-1234",
                expected = "AB1234",
            ),
            Case(
                "code in the subject line",
                "Your code: 482913",
                "Please verify within an hour.",
                expected = "482913",
            ),
            Case(
                "code alone on its own line",
                "Re: Login",
                "Hi,\n\n482913\n\nThanks!",
                expected = "482913",
            ),
            Case(
                "code alone in a large-font table cell",
                null,
                "Some body text without a code.",
                html = """<table><tr><td style="font-size:26px">482913</td></tr></table>""",
                expected = "482913",
            ),
            Case(
                "code alone in a large-font div, wrapped in markup",
                null,
                "Some body text without a code.",
                html = """<div style="font-size:24px"><b>123 456</b></div>""",
                expected = "123456",
            ),
            Case(
                "large-font cell holding the introducing phrase as well",
                null,
                "Some body text without a code.",
                html = """<div style="font-size:22px">Your code: 482913</div>""",
                expected = "482913",
            ),
            Case(
                "Spanish introduction",
                null,
                "Tu código de verificación es 482913",
                expected = "482913",
            ),
            Case(
                "French introduction",
                null,
                "Votre code de vérification est 482913",
                expected = "482913",
            ),
            Case(
                "OTP and 2FA shorthands",
                null,
                "Your OTP is 482913 and 2FA confirmation needs it too.",
                expected = "482913",
            ),
            Case(
                "password-reset code",
                null,
                "Your password reset code is 482913.",
                expected = "482913",
            ),
            Case(
                "letters-only code directly after a strong introduction",
                null,
                "Your verification code is ABCDEF",
                expected = "ABCDEF",
            ),
            Case(
                "the phrase-introduced candidate wins over a distracting number",
                null,
                "Total: 482913. Your verification code is 123456.",
                expected = "123456",
            ),
            Case(
                "an invoice reference does not steal the code's row",
                null,
                "Invoice 882233\nYour security code is A1B2C3",
                expected = "A1B2C3",
            ),
            Case(
                "code after 'for your account' phrasing",
                null,
                "2FA verification for your account: code 482913",
                expected = "482913",
            ),
        )
        cases.forEach { c ->
            assertEquals(c.name, c.expected, run(c.subject, c.body, c.html))
        }
    }

    @Test
    fun negativeCasesReturnNull() {
        val cases = listOf(
            Case(
                "a year is not a code",
                null,
                "We were founded in 2024 and again in 1900.",
                expected = null,
            ),
            Case(
                "a year is not a code even right after an introducing phrase",
                null,
                "Your code is 2024",
                expected = null,
            ),
            Case(
                "a price is not a code",
                null,
                "Total: \$48.29\nPrice: 482913 dollars",
                expected = null,
            ),
            Case(
                "an order number is not a code",
                null,
                "Your order number is 482913. It ships tomorrow.",
                expected = null,
            ),
            Case(
                "an invoice number is not a code",
                null,
                "Invoice #482913 is attached as a PDF.",
                expected = null,
            ),
            Case(
                "a phone number is not a code",
                null,
                "Call us at 555-0199 or +1 415 555 4829.",
                expected = null,
            ),
            Case(
                "a tracking number is not a code",
                null,
                "Tracking number: 1Z999AA10123456784",
                expected = null,
            ),
            Case(
                "a date is not a code",
                null,
                "Expires on 12/31/2024 or 2024-12-31. See you.",
                expected = null,
            ),
            Case(
                "digits inside a URL or query string are not a code",
                null,
                "Confirm at https://example.com/verify?code=482913 now",
                expected = null,
            ),
            Case(
                "a bare number in a sentence is not confident",
                null,
                "Somewhere they wrote 482913 in the middle of a sentence.",
                expected = null,
            ),
            Case(
                "a promo code is not a one-time code",
                null,
                "Use promo code SAVE20 at checkout",
                expected = null,
            ),
            Case(
                "a percentage is not a code",
                null,
                "Interest rate is 482913%",
                expected = null,
            ),
            Case(
                "a card number is not a code",
                null,
                "Card 4111 1111 1111 1111 was charged.",
                expected = null,
            ),
            Case(
                "a booking reference is not a code",
                "Booking 482913",
                "Your flight leaves at 10:30.",
                expected = null,
            ),
            Case(
                "an empty message has no code",
                null,
                "Thanks for your email.",
                expected = null,
            ),
        )
        cases.forEach { c ->
            assertNull(c.name, run(c.subject, c.body, c.html))
        }
    }

    @Test
    fun subjectDistractorDoesNotOverruleBodyEvidence() {
        // The subject bonus makes a subject token strong on its own, but a phrase-introduced code
        // in the body still wins: both sides are scored, the higher confidence is copied.
        assertEquals("123456", run("Order 882233", "Your verification code is 123456"))
    }
}