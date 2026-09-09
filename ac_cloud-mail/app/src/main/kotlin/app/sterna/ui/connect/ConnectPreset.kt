package app.sterna.ui.connect

import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.util.isValidEmail

/** What the add-account form decides on its own, kept out of the composable. One value rather than
 *  four pieces of screen state that could disagree (#105). */

/** A known mail provider's IMAP/SMTP settings, applied by the quick-setup chips. */
internal data class MailProvider(
    val name: String,
    val imapHost: String,
    val imapPort: String,
    val imapSecurity: ConnectionSecurity,
    val smtpHost: String,
    val smtpPort: String,
    val smtpSecurity: ConnectionSecurity,
    /** When true the chip starts the OAuth sign-in flow instead of filling host/port. */
    val oauth: Boolean = false,
    /** Page where the user creates an app-specific password (their normal one is refused). */
    val appPasswordUrl: String? = null,
)

internal val MAIL_PROVIDERS = listOf(
    MailProvider("Gmail", "imap.gmail.com", "993", ConnectionSecurity.TLS, "smtp.gmail.com", "465", ConnectionSecurity.TLS, appPasswordUrl = "https://myaccount.google.com/apppasswords"),
    // Outlook authenticates over IMAP/SMTP with OAuth (XOAUTH2) — Microsoft has disabled
    // password IMAP — so this chip launches the OAuth flow rather than filling host/port.
    MailProvider("Outlook", "outlook.office365.com", "993", ConnectionSecurity.TLS, "smtp.office365.com", "587", ConnectionSecurity.STARTTLS, oauth = true),
    MailProvider("Yahoo", "imap.mail.yahoo.com", "993", ConnectionSecurity.TLS, "smtp.mail.yahoo.com", "465", ConnectionSecurity.TLS),
    MailProvider("iCloud", "imap.mail.me.com", "993", ConnectionSecurity.TLS, "smtp.mail.me.com", "587", ConnectionSecurity.STARTTLS),
    MailProvider("Fastmail", "imap.fastmail.com", "993", ConnectionSecurity.TLS, "smtp.fastmail.com", "465", ConnectionSecurity.TLS),
    // Yandex and Mail.ru are plain IMAP behind an app-specific password (#105). Values from the
    // providers' own documentation, not by analogy with each other:
    //   yandex.com/support/mail/mail-clients/others.html ; help.mail.ru/mail/login/mailer/
    MailProvider("Yandex", "imap.yandex.com", "993", ConnectionSecurity.TLS, "smtp.yandex.com", "465", ConnectionSecurity.TLS, appPasswordUrl = "https://id.yandex.com/security/app-passwords"),
    MailProvider("Mail.ru", "imap.mail.ru", "993", ConnectionSecurity.TLS, "smtp.mail.ru", "465", ConnectionSecurity.TLS, appPasswordUrl = "https://account.mail.ru/user/2-step-auth/passwords/"),
    MailProvider("Proton Bridge", "127.0.0.1", "1143", ConnectionSecurity.STARTTLS, "127.0.0.1", "1025", ConnectionSecurity.STARTTLS),
)

/** The quick-setup state of the form: the selected provider (by name — chips are brand labels,
 *  never translated) plus the values it put in the server fields. */
internal data class PresetForm(
    val selected: String? = null,
    /** Sign-in goes through the browser (Microsoft device flow) instead of host/port + password. */
    val oauth: Boolean = false,
    val imapHost: String = "",
    val imapPort: String = "993",
    val imapSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val smtpSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
    val appPasswordUrl: String? = null,
    /** The address a probe filled these host fields for, or null when they are the user's own. The
     *  form survives process death and the verdict behind it does not, so without this "a probe put
     *  imap.x.tld here" and "she typed it" are the same non-blank string ([presetForAddress]). */
    val discoveredFor: String? = null,
) {
    /** Whether the manual server block is on screen. Hidden under OAuth on purpose — host, port and
     *  security mean nothing to XOAUTH2 — which was only safe once the chip could be disarmed. */
    val serverFieldsVisible: Boolean get() = !oauth

    companion object {
        val NONE = PresetForm()
    }
}

/** The values [provider] puts in the form, built fresh so no field survives from a previous pick. */
private fun MailProvider.asForm(): PresetForm = if (oauth) {
    // The host/port fields stay empty and hidden, and no app-password link is offered: Microsoft
    // refuses password IMAP, so such a link would send the user to a dead end.
    PresetForm(selected = name, oauth = true)
} else {
    PresetForm(
        selected = name,
        oauth = false,
        imapHost = imapHost,
        imapPort = imapPort,
        imapSecurity = imapSecurity,
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        smtpSecurity = smtpSecurity,
        appPasswordUrl = appPasswordUrl,
    )
}

/** What a tap on a quick-setup chip yields. Tapping the chip already selected clears the selection,
 *  which is the whole fix for #105: the OAuth chip is no longer a one-way door. */
internal fun presetChipTapped(current: PresetForm, tapped: MailProvider): PresetForm =
    if (current.selected == tapped.name) PresetForm.NONE else tapped.asForm()

/** Choosing a protocol disarms an OAuth preset: the Microsoft device flow is IMAP-side (#105).
 *  Non-OAuth presets survive, so a round trip through the protocol chips keeps a typed host. */
internal fun presetForProtocol(preset: PresetForm, protocol: MailProtocol): PresetForm =
    if (protocol == MailProtocol.JMAP && preset.oauth) PresetForm.NONE else preset

/** Where the Connect button sends the form. */
internal enum class ConnectRoute {
    /** Microsoft device flow (XOAUTH2 over IMAP/SMTP), reachable only from the Outlook chip. */
    OUTLOOK_OAUTH,

    /** JMAP with a server-issued API token (Bearer) typed in the secret field. */
    JMAP_TOKEN,

    /** JMAP password sign-in; the server is discovered from the address' domain. */
    JMAP_AUTODISCOVER,

    /** JMAP password sign-in against the server typed under Advanced. */
    JMAP_SERVER,

    IMAP_PASSWORD,
}

/** Which sign-in the Connect button runs. The OAuth branch is gated on the protocol as well as the
 *  flag, so a preset that somehow stayed armed cannot claim a JMAP form (belt to
 *  [presetForProtocol]'s braces). */
internal fun connectRoute(
    preset: PresetForm,
    protocol: MailProtocol,
    useApiToken: Boolean,
    server: String,
): ConnectRoute = when {
    protocol == MailProtocol.IMAP && preset.oauth -> ConnectRoute.OUTLOOK_OAUTH
    protocol == MailProtocol.IMAP -> ConnectRoute.IMAP_PASSWORD
    useApiToken -> ConnectRoute.JMAP_TOKEN
    server.isBlank() -> ConnectRoute.JMAP_AUTODISCOVER
    else -> ConnectRoute.JMAP_SERVER
}

/** Whether Connect has enough to run the [route] it would take. Ports come straight from the text
 *  fields, so a non-numeric one counts as missing. */
internal fun connectReady(
    route: ConnectRoute,
    username: String,
    password: String,
    imapHost: String,
    imapPort: String,
    smtpHost: String,
    smtpPort: String,
): Boolean = when (route) {
    ConnectRoute.OUTLOOK_OAUTH -> username.isNotBlank()
    ConnectRoute.IMAP_PASSWORD -> username.isNotBlank() && password.isNotBlank() &&
        imapHost.isNotBlank() && imapPort.toIntOrNull() != null &&
        smtpHost.isNotBlank() && smtpPort.toIntOrNull() != null
    else -> username.isNotBlank() && password.isNotBlank()
}

// ---- The three steps of adding an account ------------------------------------------------------

/** Which page of the add-account walk is on screen: the address, then what discovery decided, then
 *  the manual form if it found nothing. */
internal enum class ConnectStep {
    ADDRESS,

    /** What discovery decided, plus the secret it needs. Renders only with a verdict in hand. */
    CREDENTIALS,

    /** The prefilled fallback, reached only when discovery found nothing. */
    MANUAL,
}

/** Whether the address step may be left: the app's shared [isValidEmail] rule and nothing else. A
 *  second rule here would drift from the one the address field flags errors with, and from the one
 *  [shouldDiscoverImapSettings] gates the cascade on. */
internal fun canLeaveAddressStep(email: String): Boolean = isValidEmail(email)

/** Where the address step lands once discovery has its verdict. It takes both verdicts, since one
 *  pass runs both probes: a domain over JMAP + OAuth publishing no Thunderbird document (#55) has
 *  a good answer in [oauth]. [oauthOpensCredentials], not "an OAuth answer exists". */
internal fun stepAfterAddress(
    discovery: MailAutoconfigResult,
    oauth: OAuthDiscovery?,
    email: String,
): ConnectStep =
    if (discovery is MailAutoconfigResult.Found || oauthOpensCredentials(oauth, email)) {
        ConnectStep.CREDENTIALS
    } else {
        ConnectStep.MANUAL
    }

/** The step actually rendered, given the [remembered] one and the verdict in hand. The step index
 *  survives process death (`rememberSaveable`); the answers do not, and what must never render is
 *  a password field for a server nobody discovered. An OAuth answer counts as a `Found` (#55). */
internal fun stepToRender(
    remembered: ConnectStep,
    discovered: MailAutoconfigResult.Found?,
    oauth: OAuthDiscovery?,
    email: String,
): ConnectStep =
    if (remembered == ConnectStep.CREDENTIALS &&
        discovered == null &&
        !oauthOpensCredentials(oauth, email)
    ) {
        ConnectStep.ADDRESS
    } else {
        remembered
    }

/** Where Back goes from [current]: the later steps return to the address, and Back from the address
 *  leaves the screen — `null`, said here rather than guessed at the call site. */
internal fun stepBack(current: ConnectStep): ConnectStep? =
    if (current == ConnectStep.ADDRESS) null else ConnectStep.ADDRESS

/** Whether the autoconfig cascade may set out for [email]. True only on a form the verdict can
 *  serve, with no chip armed, both host fields untouched and the device [online]. The route
 *  condition is lifted at the address step only, where no protocol has been chosen; elsewhere
 *  JMAP leaves no fields to fill. */
internal fun shouldDiscoverImapSettings(
    step: ConnectStep,
    route: ConnectRoute,
    preset: PresetForm,
    email: String,
    online: Boolean,
): Boolean = (step == ConnectStep.ADDRESS || route == ConnectRoute.IMAP_PASSWORD) &&
    preset.selected == null &&
    !preset.oauth &&
    preset.imapHost.isBlank() &&
    preset.smtpHost.isBlank() &&
    isValidEmail(email) &&
    online

/** The discovered settings written into the form — per side, atomically, so a hand-typed host is
 *  never overwritten and never has a discovered port slid under it. The verdict's username is
 *  applied nowhere: the address field keeps what she typed (WYSIWYG). */
internal fun presetFilledWith(
    preset: PresetForm,
    found: MailAutoconfigResult.Found,
    email: String,
): PresetForm {
    var filled = preset
    // [PresetForm.discoveredFor] is stamped only by the function that writes discovered values, and
    // only on a side it really wrote: provenance cannot be forgotten at a call site.
    if (preset.imapHost.isBlank()) {
        filled = filled.copy(
            imapHost = found.incoming.host,
            imapPort = found.incoming.port.toString(),
            imapSecurity = found.incoming.security,
            discoveredFor = email.trim(),
        )
    }
    if (preset.smtpHost.isBlank()) {
        filled = filled.copy(
            smtpHost = found.outgoing.host,
            smtpPort = found.outgoing.port.toString(),
            smtpSecurity = found.outgoing.security,
            discoveredFor = email.trim(),
        )
    }
    return filled
}
