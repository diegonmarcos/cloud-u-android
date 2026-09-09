package app.sterna.core.data.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [oauthHostWasGuessed]: may this OAuth sign-in's host be improved into the one the password path
 */
class OAuthHostWasGuessedTest {

    /** What `Jmap.autodiscoverHosts("alex@masto.top")` yields, written out. */
    private val guessed = listOf("masto.top", "mail.masto.top", "jmap.masto.top", "api.masto.top")

    /** The #55 case itself: the OAuth document came from a host the app made up on its own. */
    @Test fun `a host straight out of the candidate list was guessed`() {
        assertTrue(
            "mail.masto.top is the OAuth candidate list's own guess for this address; refuse to " +
                "improve it and #55 stays whole — one mailbox, two accounts.",
            oauthHostWasGuessed("mail.masto.top", guessed),
        )
    }

    /** The apex, which the OAuth candidate list drops and the password path keeps. */
    @Test fun `the apex is a guessed host too`() {
        assertTrue(
            "The apex is what the password path stores, and it is in the guessed list.",
            oauthHostWasGuessed("masto.top", guessed),
        )
    }

    /**
     * The OAuth routes carry the candidate as a URL where autodiscovery yields a bare host.
     * Compared as raw text, every OAuth add would look reader-dictated and #55 would stay whole.
     */
    @Test fun `the same host as a URL is still a guessed host`() {
        assertTrue(
            "Compared as raw text instead of through normalizeEndpoint, every OAuth host looks " +
                "reader-dictated, nothing is ever resolved, and #55 is not fixed at all.",
            oauthHostWasGuessed("https://mail.masto.top", guessed),
        )
    }

    /**
     * A host of another domain cannot have been guessed for this address: it came from the field
     * the reader filled in on the manual form, and her assertion decides where the account goes.
     */
    @Test fun `a host of another domain was not guessed`() {
        assertFalse(
            "This host was typed into the manual form. Overwrite it and its OAuth button files " +
                "the account elsewhere than its password button, from the same field (#55).",
            oauthHostWasGuessed("mail.example.com", guessed),
        )
    }

    /**
     * And a typed host is protected even when its domain matches: autodiscovery never guesses a
     * path, so `mail.masto.top/jmap/session` is a session URL somebody wrote down.
     */
    @Test fun `a host carrying a path was not guessed`() {
        assertFalse(
            "Autodiscovery never guesses a path, so this session URL was written down by hand — " +
                "and normalizeEndpoint keeps the path precisely so it can be told apart.",
            oauthHostWasGuessed("mail.masto.top/jmap/session", guessed),
        )
    }

    /** Nothing to improve, and nothing to compare: a blank host is nobody's guess. */
    @Test fun `a blank host was not guessed`() {
        assertFalse(
            "A blank host matches nothing and improves nothing.",
            oauthHostWasGuessed("", guessed),
        )
    }

    /**
     * A malformed address gives no candidates at all (`Jmap.autodiscoverHosts` returns empty).
     * Nothing was guessed then, so nothing may be overwritten.
     */
    @Test fun `nothing is a guessed host when there were no candidates`() {
        assertFalse(
            "No candidates means the app guessed nothing, so it may overwrite nothing.",
            oauthHostWasGuessed("mail.masto.top", emptyList()),
        )
    }
}
