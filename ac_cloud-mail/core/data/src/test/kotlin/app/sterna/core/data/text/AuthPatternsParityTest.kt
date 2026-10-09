package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's G0 _ Auth classifier is built from the declared pattern set (vendored as `auth-patterns.json`, the
 * fleet's source of truth being cloud-u-containers `_shared/mail-auth-patterns.json`, from which the mail
 * server's G0 _ AUTH rules are derived). Neither side restates a phrase: this holds the generated constants to
 * the vendored file, and the classifier's behaviour to the constants.
 */
class AuthPatternsParityTest {
    private val json = File("auth-patterns.json").readText()

    private fun listOf(key: String): List<String> =
        Regex("\"$key\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(json)!!.groupValues[1]
            .let { Regex("\"([^\"]*)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }

    @Test fun `the generated constants are the vendored pattern set, in order`() {
        assertEquals(listOf("link_phrases"), AuthPatterns.LINK_PHRASES)
        assertEquals(listOf("url_tokens"), AuthPatterns.URL_TOKENS)
        assertEquals(listOf("code_subject_phrases"), AuthPatterns.CODE_SUBJECT_PHRASES)
    }

    @Test fun `the rule and class names are the declared ones`() {
        assertTrue(json.contains("\"rule\": \"G0 _ Auth\""))
        assertEquals("G0 _ Auth", AuthClass.RULE)
        assertTrue(json.contains("\"name\": \"${AuthClass.CODE.label}\""))
        assertTrue(json.contains("\"name\": \"${AuthClass.LINK.label}\""))
        assertTrue(json.contains("\"name\": \"${AuthClass.NONE.label}\""))
    }

    @Test fun `every declared link phrase classifies a message that has no code as Gb`() {
        AuthPatterns.LINK_PHRASES.forEach { phrase ->
            assertEquals(phrase, AuthClass.LINK, AuthClassifier.classify("Please $phrase", "Thanks"))
        }
    }

    @Test fun `every declared url token marks a link as Gb`() {
        AuthPatterns.URL_TOKENS.forEach { token ->
            assertEquals(
                token, AuthClass.LINK,
                AuthClassifier.classify("Hello", "Open https://example.com/x/${token.trim('/')}/page now"),
            )
        }
    }

    @Test fun `a code phrase subject with a code is Ga whatever the link phrases say`() {
        assertEquals(
            AuthClass.CODE,
            AuthClassifier.classify("Re: Login", "Hello,\n\nYour verification code is 482913.\n\nOr reset your password."),
        )
    }
}
