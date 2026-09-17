package cld.camera.analyzer

import com.google.zxing.Result
import com.google.zxing.client.result.AddressBookParsedResult
import com.google.zxing.client.result.CalendarParsedResult
import com.google.zxing.client.result.ParsedResult
import com.google.zxing.client.result.ParsedResultType
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.TextParsedResult
import com.google.zxing.client.result.URIParsedResult
import com.google.zxing.client.result.WifiParsedResult
import java.net.URI

/**
 * A decoded barcode is never shown as a raw string: it is turned into one
 * [ScannedPayload] and the UI offers the action that matches. This is the
 * seam that stops a scanned QR code from being dumped as text.
 *
 * The classification reuses ZXing's own [ResultParser] — the same decoder the
 * scanner already runs — so there is exactly one decoding engine and no second,
 * private copy of a parser (the defect shape of #170 and #261). Every ZXing
 * parsed type maps to a payload of its own; everything else becomes [PlainText]
 * so the user can still copy it.
 *
 * Accessors are invoked as explicit Java getters (getURI, isHidden, getSsid,
 * ...) rather than Kotlin properties, because property synthesis is not
 * deterministic for ZXing's upper-case acronym and boolean accessors and this
 * class must not depend on which spelling the compiler resolves.
 */
sealed class ScannedPayload {

    data class Url(val address: String) : ScannedPayload()

    data class Wifi(
        val ssid: String,
        val passwordType: String,
        val password: String,
        val hidden: Boolean,
    ) : ScannedPayload()

    data class Contact(
        val names: Array<String>,
        val phoneNumbers: Array<String>,
        val emails: Array<String>,
    ) : ScannedPayload()

    data class CalendarEvent(
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val location: String,
        val description: String,
    ) : ScannedPayload()

    data class PlainText(val text: String) : ScannedPayload()

    companion object {
        /** Classify a raw ZXing [Result] into the typed payload it carries. */
        fun fromResult(result: Result): ScannedPayload {
            val parsed: ParsedResult = ResultParser.parseResult(result)
            return when (parsed.type) {
                ParsedResultType.URI -> {
                    val address = (parsed as URIParsedResult).getURI()
                    // The fleet browser only promises http/https (its manifest
                    // declares exactly those two schemes), so a non-web URI is
                    // surfaced as copyable text instead of a dead-ended "open".
                    if (usesWebScheme(address)) Url(address)
                    else PlainText(address)
                }

                ParsedResultType.WIFI -> {
                    val w = parsed as WifiParsedResult
                    Wifi(
                        ssid = w.getSsid(),
                        passwordType = w.getNetworkEncryption(),
                        password = w.getPassword(),
                        hidden = w.isHidden(),
                    )
                }

                ParsedResultType.ADDRESSBOOK -> {
                    val a = parsed as AddressBookParsedResult
                    Contact(
                        names = a.getNames(),
                        phoneNumbers = a.getPhoneNumbers() ?: emptyArray(),
                        emails = a.getEmails() ?: emptyArray(),
                    )
                }

                ParsedResultType.CALENDAR -> {
                    val c = parsed as CalendarParsedResult
                    CalendarEvent(
                        title = c.getSummary(),
                        startMillis = c.getStartTimestamp(),
                        endMillis = c.getEndTimestamp(),
                        location = c.getLocation() ?: "",
                        description = c.getDescription() ?: "",
                    )
                }

                ParsedResultType.TEXT -> {
                    val t = parsed as TextParsedResult
                    if (t.getText().isBlank()) PlainText(result.text)
                    else PlainText(t.getText())
                }

                // Any other ZXing type (product, geo, tel, sms, ...) is still
                // selectable as plain text so the payload is never lost.
                else -> PlainText(parsed.getDisplayResult().ifBlank { result.text })
            }
        }

        /** Classify an arbitrary [rawText] where no [Result] exists (defensive). */
        fun fromText(rawText: String): ScannedPayload {
            if (usesWebScheme(rawText)) return Url(rawText)
            return PlainText(rawText)
        }

        /**
         * A human-readable value for a contact payload, used as the dialog body
         * so the action is announced instead of a raw vCard dump.
         */
        fun describeContact(contact: Contact): String {
            val name = contact.names.joinToString(" ")
            val phone = contact.phoneNumbers.firstOrNull().orEmpty()
            val email = contact.emails.firstOrNull().orEmpty()
            return listOf(name, phone, email).filter { it.isNotBlank() }.joinToString("\n")
        }

        /** A human-readable value for a calendar event payload. */
        fun describeCalendar(event: CalendarEvent): String {
            val parts = buildList {
                if (event.title.isNotBlank()) add(event.title)
                if (event.location.isNotBlank()) add(event.location)
                if (event.description.isNotBlank()) add(event.description)
            }
            return parts.joinToString("\n")
        }

        /**
         * Whether [value] is a web URL (http/https). Non-web URIs such as `mailto:`
         * or `tel:` must not be handed to the fleet browser; [ResultParser] keeps
         * them as URIs but the fleet browser only promises http/https (its manifest
         * declares exactly those two schemes), so a non-web URI falls through to the
         * plain-text/copy path rather than dead-ending against an unfulfillable open.
         */
        fun usesWebScheme(value: String): Boolean {
            val scheme = runCatching { URI(value).scheme }.getOrNull() ?: return false
            return scheme.equals("http", ignoreCase = true) ||
                scheme.equals("https", ignoreCase = true)
        }
    }
}