package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rule "G0 _ Auth": Ga Code, Gb Link to auth, Gc No Auth - exclusive, code first. */
class AuthClassifierTest {
    private fun cls(subject: String?, body: String, html: String? = null) = AuthClassifier.classify(subject, body, html)

    @Test fun `an OTP mail is Ga Code`() {
        assertEquals(AuthClass.CODE, cls("Re: Login", "Hello,\n\nYour verification code is 482913.\n\nIt expires in ten minutes."))
    }

    @Test fun `a magic-link mail is Gb Link to auth`() {
        assertEquals(
            AuthClass.LINK,
            cls("Sign in to Acme", "Click the button below to sign in. https://acme.io/auth/magic?token=magictoken This link expires soon."),
        )
    }

    @Test fun `a password-reset mail is Gb Link to auth`() {
        assertEquals(
            AuthClass.LINK,
            cls("Reset your password", "We received a request to reset your password. https://acme.io/account/reset?t=resettoken"),
        )
    }

    @Test fun `a confirm-email mail is Gb even with only the words, as a preview has`() {
        assertEquals(AuthClass.LINK, cls("Welcome!", "Please confirm your email address to finish signing up."))
    }

    @Test fun `a newsletter is Gc No Auth`() {
        assertEquals(
            AuthClass.NONE,
            cls(
                "This week in gardening",
                "Ten tips for spring. Read more at https://blog.example.com/spring-tips?utm_source=news Unsubscribe: https://example.com/unsubscribe",
            ),
        )
    }

    @Test fun `a host that merely says login is not a sign-in link`() {
        assertEquals(AuthClass.NONE, cls("Offers", "Shop now https://login.shop.example.com/offers/summer"))
    }

    @Test fun `a mail with both a code and a link is Ga`() {
        assertEquals(
            AuthClass.CODE,
            cls("Re: Login", "Hello,\n\nYour verification code is 731942.\n\nOr verify here https://acme.io/verify?token=verifytoken"),
        )
    }

    @Test fun `the html hrefs are read when the markup is at hand`() {
        val html = """<p>Hello</p><a href="https://acme.io/activate/welcome">Go</a>"""
        assertEquals(AuthClass.LINK, cls("Hi", "Hello Go", html))
    }

    @Test fun `the class values and ids are the rule's`() {
        assertEquals(listOf(0, 1, 2), AuthClass.entries.map { it.value })
        assertEquals(listOf("Gc", "Ga", "Gb"), AuthClass.entries.map { it.id })
        assertEquals("G0 _ Auth", AuthClass.RULE)
        assertEquals("Ga Code", AuthClass.CODE.label)
        assertEquals("Gb Link to auth", AuthClass.LINK.label)
        assertEquals("Gc No Auth", AuthClass.NONE.label)
        assertTrue(AuthClass.of(null) == null)
    }
}
