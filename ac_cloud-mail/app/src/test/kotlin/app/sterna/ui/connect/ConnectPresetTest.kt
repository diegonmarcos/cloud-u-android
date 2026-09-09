package app.sterna.ui.connect

import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.core.jmap.OAuthMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quick setup used to be four pieces of screen state that could disagree: the Outlook chip armed a
 */
class ConnectPresetTest {

    private fun provider(name: String) = MAIL_PROVIDERS.first { it.name == name }

    private val outlook = provider("Outlook")
    private val gmail = provider("Gmail")
    private val yandex = provider("Yandex")
    private val mailru = provider("Mail.ru")

    // --- The chip is a door that opens both ways -------------------------------------------------

    @Test fun tappingTheArmedChipAgainReleasesIt() {
        val armed = presetChipTapped(PresetForm.NONE, outlook)
        assertEquals("Outlook", armed.selected)
        assertTrue(armed.oauth)
        assertFalse("the server fields go away under OAuth", armed.serverFieldsVisible)

        val released = presetChipTapped(armed, outlook)
        assertNull(released.selected)
        assertFalse(released.oauth)
        assertTrue("and the second tap must bring them back", released.serverFieldsVisible)
        assertEquals(PresetForm.NONE, released)
    }

    @Test fun aPasswordPresetIsAlsoReleasedByASecondTap() {
        val armed = presetChipTapped(PresetForm.NONE, gmail)
        assertEquals("Gmail", armed.selected)
        assertEquals(PresetForm.NONE, presetChipTapped(armed, gmail))
    }

    @Test fun tappingAnotherChipSwitchesRatherThanReleases() {
        val armed = presetChipTapped(PresetForm.NONE, outlook)
        val switched = presetChipTapped(armed, yandex)
        assertEquals("Yandex", switched.selected)
        assertFalse(switched.oauth)
        assertEquals("imap.yandex.com", switched.imapHost)
    }

    // --- No preset inherits from the one before it -----------------------------------------------

    @Test fun eachPresetReplacesEveryFieldOfTheLast() {
        // Proton Bridge is the only entry on non-standard ports and STARTTLS on both sides, so any
        // field left over from it would be visible in the next pick.
        val bridge = presetChipTapped(PresetForm.NONE, provider("Proton Bridge"))
        val next = presetChipTapped(bridge, mailru)
        assertEquals("imap.mail.ru", next.imapHost)
        assertEquals("993", next.imapPort)
        assertEquals(ConnectionSecurity.TLS, next.imapSecurity)
        assertEquals("smtp.mail.ru", next.smtpHost)
        assertEquals("465", next.smtpPort)
        assertEquals(ConnectionSecurity.TLS, next.smtpSecurity)
    }

    @Test fun theOauthPresetCarriesNoServerValuesAndNoAppPasswordLink() {
        // Selecting Outlook after Gmail must not leave Gmail's hosts hidden behind the OAuth
        // screen, and Microsoft refuses password IMAP, so an app-password link would be a dead end.
        val armed = presetChipTapped(presetChipTapped(PresetForm.NONE, gmail), outlook)
        assertEquals("", armed.imapHost)
        assertEquals("", armed.smtpHost)
        assertNull(armed.appPasswordUrl)
    }

    @Test fun releasingAPresetClearsTheFieldsItFilled() {
        val released = presetChipTapped(presetChipTapped(PresetForm.NONE, yandex), yandex)
        assertEquals("", released.imapHost)
        assertEquals("", released.smtpHost)
        assertNull(released.appPasswordUrl)
    }

    // --- Switching protocol cannot leave OAuth armed ---------------------------------------------

    @Test fun choosingJmapDisarmsTheOauthPreset() {
        val armed = presetChipTapped(PresetForm.NONE, outlook)
        val afterJmap = presetForProtocol(armed, MailProtocol.JMAP)
        assertFalse(afterJmap.oauth)
        assertNull(afterJmap.selected)
    }

    @Test fun choosingJmapKeepsAPasswordPresetIntact() {
        val armed = presetChipTapped(PresetForm.NONE, yandex)
        assertEquals(armed, presetForProtocol(armed, MailProtocol.JMAP))
        assertEquals(armed, presetForProtocol(armed, MailProtocol.IMAP))
    }

    @Test fun stayingOnImapKeepsTheOauthPreset() {
        val armed = presetChipTapped(PresetForm.NONE, outlook)
        assertEquals(armed, presetForProtocol(armed, MailProtocol.IMAP))
    }

    // --- Where Connect goes ----------------------------------------------------------------------

    @Test fun outlookThenJmapDoesNotSignInToMicrosoft() {
        val armed = presetChipTapped(PresetForm.NONE, outlook)
        // Through the screen's own path: the protocol chip disarms it first.
        val afterJmap = presetForProtocol(armed, MailProtocol.JMAP)
        assertEquals(
            ConnectRoute.JMAP_AUTODISCOVER,
            connectRoute(afterJmap, MailProtocol.JMAP, useApiToken = false, server = ""),
        )
        // And the second guard: even a preset that stayed armed cannot claim a JMAP form.
        assertEquals(
            ConnectRoute.JMAP_AUTODISCOVER,
            connectRoute(armed, MailProtocol.JMAP, useApiToken = false, server = ""),
        )
        assertEquals(
            ConnectRoute.JMAP_TOKEN,
            connectRoute(armed, MailProtocol.JMAP, useApiToken = true, server = ""),
        )
    }

    @Test fun theOutlookChipStillReachesTheMicrosoftFlow() {
        val armed = presetChipTapped(PresetForm.NONE, outlook)
        assertEquals(
            ConnectRoute.OUTLOOK_OAUTH,
            connectRoute(armed, MailProtocol.IMAP, useApiToken = false, server = ""),
        )
    }

    @Test fun aReleasedOutlookChipGoesBackToTheManualImapPath() {
        val released = presetChipTapped(presetChipTapped(PresetForm.NONE, outlook), outlook)
        assertEquals(
            ConnectRoute.IMAP_PASSWORD,
            connectRoute(released, MailProtocol.IMAP, useApiToken = false, server = ""),
        )
    }

    @Test fun aTypedJmapServerOverridesAutodiscovery() {
        assertEquals(
            ConnectRoute.JMAP_SERVER,
            connectRoute(PresetForm.NONE, MailProtocol.JMAP, useApiToken = false, server = "jmap.example.org"),
        )
    }

    // --- What Connect needs before it will run ---------------------------------------------------

    @Test fun oauthNeedsOnlyTheAddress() {
        assertTrue(
            connectReady(
                ConnectRoute.OUTLOOK_OAUTH, "alex@outlook.com", password = "",
                imapHost = "", imapPort = "", smtpHost = "", smtpPort = "",
            ),
        )
        assertFalse(
            connectReady(
                ConnectRoute.OUTLOOK_OAUTH, "", password = "",
                imapHost = "", imapPort = "", smtpHost = "", smtpPort = "",
            ),
        )
    }

    @Test fun theImapPathNeedsTheFourServerValuesItWillDial() {
        val filled = presetChipTapped(PresetForm.NONE, mailru)
        assertTrue(
            connectReady(
                ConnectRoute.IMAP_PASSWORD, "alex@mail.ru", "app-password",
                filled.imapHost, filled.imapPort, filled.smtpHost, filled.smtpPort,
            ),
        )
        // A port the user cleared or mistyped counts as missing, not as a crash at connect time.
        assertFalse(
            connectReady(
                ConnectRoute.IMAP_PASSWORD, "alex@mail.ru", "app-password",
                filled.imapHost, "", filled.smtpHost, filled.smtpPort,
            ),
        )
    }

    @Test fun theJmapPathsNeedTheSecretButNoServerValues() {
        assertTrue(
            connectReady(
                ConnectRoute.JMAP_AUTODISCOVER, "alex@example.org", "secret",
                imapHost = "", imapPort = "", smtpHost = "", smtpPort = "",
            ),
        )
        assertFalse(
            connectReady(
                ConnectRoute.JMAP_TOKEN, "alex@example.org", "",
                imapHost = "", imapPort = "", smtpHost = "", smtpPort = "",
            ),
        )
    }

    // --- The walk: which of the three steps is on screen -----------------------------------------

    private val walkEmail = "user@example.org"

    private val found = MailAutoconfigResult.Found(
        incoming = MailEndpoint("imap.example.org", 143, ConnectionSecurity.STARTTLS),
        outgoing = MailEndpoint("smtp.example.org", 587, ConnectionSecurity.STARTTLS),
        username = "user@example.org",
    )

    /** The OAuth search's answer for [walkEmail]: a server this app can sign into by device flow. */
    private val oauthUsable = OAuthDiscovery(
        walkEmail,
        "mail.example.org" to OAuthMetadata(
            issuer = "https://example.org",
            tokenEndpoint = "https://example.org/token",
            deviceAuthorizationEndpoint = "https://example.org/device",
        ),
    )

    /** OAuth advertised, and no grant this app can drive: a password field with nothing behind it. */
    private val oauthUndrivable = OAuthDiscovery(
        walkEmail,
        "mail.example.org" to OAuthMetadata(
            issuer = "https://example.org",
            tokenEndpoint = "https://example.org/token",
        ),
    )

    /** A drivable answer, about the domain she walked back and replaced. */
    private val oauthElsewhere = OAuthDiscovery(
        "someone@autre.tld",
        "mail.autre.tld" to OAuthMetadata(
            issuer = "https://autre.tld",
            tokenEndpoint = "https://autre.tld/token",
            deviceAuthorizationEndpoint = "https://autre.tld/device",
        ),
    )

    @Test fun onlyAWellFormedAddressLeavesTheFirstStep() {
        assertTrue(canLeaveAddressStep("alex@example.org"))
        assertTrue("the shared rule trims; so must this one", canLeaveAddressStep("  alex@example.org  "))
        assertFalse("nothing typed yet", canLeaveAddressStep(""))
        assertFalse("no @ — there is no domain to probe", canLeaveAddressStep("alex"))
        assertFalse(
            "a one-label domain: the shared isValidEmail rejects it, and a rule written a second " +
                "time here would drift from the one the address field flags errors with.",
            canLeaveAddressStep("alex@example"),
        )
    }

    @Test fun aDiscoveryThatLearnedSomethingLeadsToTheCredentials() {
        assertEquals(
            "the whole point of the walk: discovery answered, so the user is asked for her " +
                "password and never for a protocol.",
            ConnectStep.CREDENTIALS,
            stepAfterAddress(found, null, walkEmail),
        )
    }

    @Test fun aDiscoveryThatFoundNothingLeadsToTheManualForm() {
        assertEquals(
            "manual configuration appears on FAILURE and only on failure — offered next to a " +
                "working verdict it would put the protocol question back on screen.",
            ConnectStep.MANUAL,
            stepAfterAddress(MailAutoconfigResult.NotFound, null, walkEmail),
        )
    }

    @Test fun anOAuthAnswerAloneAlsoLeadsToTheCredentials() {
        assertEquals(
            "⛔ #55. The domain publishes no Thunderbird autoconfig, so the cascade answers " +
                "NotFound — and the OAuth search that ran in the same pass found the server that " +
                "signs this account in. Reading the cascade alone throws that answer away and " +
                "asks the reader for hostnames the app already holds.",
            ConnectStep.CREDENTIALS,
            stepAfterAddress(MailAutoconfigResult.NotFound, oauthUsable, walkEmail),
        )
        assertEquals(
            "and the answer is about ONE address: not the domain she walked back and replaced.",
            ConnectStep.MANUAL,
            stepAfterAddress(MailAutoconfigResult.NotFound, oauthUsable, "someone@autre.tld"),
        )
    }

    @Test fun theCredentialsStepHoldsOnAnOAuthAnswerToo() {
        assertEquals(
            "⛔ WITHOUT THIS THE WALK LOOPS. The step reached over the OAuth answer alone has no " +
                "settings verdict behind it and never will, so reading `discovered` on its own " +
                "sends it back to the address step the very frame it arrives.",
            ConnectStep.CREDENTIALS,
            stepToRender(ConnectStep.CREDENTIALS, null, oauthUsable, walkEmail),
        )
        assertEquals(
            "and the witness: process death empties the ViewModel of BOTH answers while the step " +
                "index survives, so the walk still falls back and both probes run again.",
            ConnectStep.ADDRESS,
            stepToRender(ConnectStep.CREDENTIALS, null, null, walkEmail),
        )
        assertEquals(
            "⛔ AND IT IS THE SAME QUESTION [stepAfterAddress] ASKED, not `oauth != null`. Written " +
                "that way this function holds the credentials step for an answer that leads " +
                "nowhere — no grant this app can drive means a password field with no endpoints " +
                "behind it, exactly the dead end oauthOpensCredentials exists to refuse — and " +
                "every other test here stays green, because they only ever feed it a drivable " +
                "answer or none.",
            ConnectStep.ADDRESS,
            stepToRender(ConnectStep.CREDENTIALS, null, oauthUndrivable, walkEmail),
        )
        assertEquals(
            "and the second half of the same guard: an answer about the address she walked back " +
                "and replaced is no answer about this one, here as everywhere else on this walk.",
            ConnectStep.ADDRESS,
            stepToRender(ConnectStep.CREDENTIALS, null, oauthElsewhere, walkEmail),
        )
    }

    @Test fun theCredentialsStepDoesNotComeBackWithoutItsVerdict() {
        // Process death: the step index is rememberSaveable and comes back CREDENTIALS; the
        // ViewModel that held the verdict has no SavedStateHandle and comes back empty.
        assertEquals(
            "a password field for a server nobody discovered is a state that is not true, and " +
                "its button leads nowhere: without a verdict the screen renders the address step.",
            ConnectStep.ADDRESS,
            stepToRender(ConnectStep.CREDENTIALS, null, null, walkEmail),
        )
    }

    @Test fun theCredentialsStepHoldsWhileItsVerdictIsThere() {
        // The verdict is in hand (after a rotation, once the ViewModel keeps a durable copy —
        // today's one-shot flow is nulled on consumption, see the function's KDoc).
        assertEquals(
            "a verdict in hand is not process death; sending this case back to the address step " +
                "would throw away the password being typed.",
            ConnectStep.CREDENTIALS,
            stepToRender(ConnectStep.CREDENTIALS, found, null, walkEmail),
        )
    }

    @Test fun theOtherTwoStepsRenderVerdictOrNot() {
        assertEquals(ConnectStep.ADDRESS, stepToRender(ConnectStep.ADDRESS, null, null, walkEmail))
        assertEquals(ConnectStep.ADDRESS, stepToRender(ConnectStep.ADDRESS, found, null, walkEmail))
        assertEquals(
            "the manual form shows nothing but the form's own saved state, so it survives a " +
                "process death with no verdict at all.",
            ConnectStep.MANUAL,
            stepToRender(ConnectStep.MANUAL, null, null, walkEmail),
        )
        assertEquals(ConnectStep.MANUAL, stepToRender(ConnectStep.MANUAL, found, null, walkEmail))
    }

    @Test fun backReturnsToTheAddressAndThenLeavesTheScreen() {
        assertEquals(ConnectStep.ADDRESS, stepBack(ConnectStep.CREDENTIALS))
        assertEquals(ConnectStep.ADDRESS, stepBack(ConnectStep.MANUAL))
        assertNull(
            "back from the first step leaves the add-account screen — said here rather than " +
                "guessed by the caller.",
            stepBack(ConnectStep.ADDRESS),
        )
    }

    // --- The table itself ------------------------------------------------------------------------

    @Test fun yandexAndMailRuAreAppPasswordImapPresets() {
        // Both are plain IMAP with an app-specific password: the chip must fill the servers AND
        // offer the page where that password is created, or the user hits a login failure they
        // cannot diagnose. Values are the providers' own documented ones.
        assertEquals("imap.yandex.com", yandex.imapHost)
        assertEquals("993", yandex.imapPort)
        assertEquals(ConnectionSecurity.TLS, yandex.imapSecurity)
        assertEquals("smtp.yandex.com", yandex.smtpHost)
        assertEquals("465", yandex.smtpPort)
        assertEquals(ConnectionSecurity.TLS, yandex.smtpSecurity)

        assertEquals("imap.mail.ru", mailru.imapHost)
        assertEquals("993", mailru.imapPort)
        assertEquals(ConnectionSecurity.TLS, mailru.imapSecurity)
        assertEquals("smtp.mail.ru", mailru.smtpHost)
        assertEquals("465", mailru.smtpPort)
        assertEquals(ConnectionSecurity.TLS, mailru.smtpSecurity)

        listOf(yandex, mailru).forEach {
            assertFalse("${it.name} is password IMAP, not OAuth", it.oauth)
            assertNotNull("${it.name} needs its app-password page", it.appPasswordUrl)
        }
    }

    @Test fun everyEntryIsUsableAndOnlyOneSignsInByOauth() {
        MAIL_PROVIDERS.forEach {
            assertTrue("${it.name} host", it.imapHost.isNotBlank() && it.smtpHost.isNotBlank())
            assertNotNull("${it.name} imap port", it.imapPort.toIntOrNull())
            assertNotNull("${it.name} smtp port", it.smtpPort.toIntOrNull())
        }
        assertEquals(listOf("Outlook"), MAIL_PROVIDERS.filter { it.oauth }.map { it.name })
        // Names double as the chips' identity in [PresetForm.selected]: a duplicate would make two
        // chips light up together and share one selection.
        assertEquals(MAIL_PROVIDERS.size, MAIL_PROVIDERS.map { it.name }.toSet().size)
    }
}
