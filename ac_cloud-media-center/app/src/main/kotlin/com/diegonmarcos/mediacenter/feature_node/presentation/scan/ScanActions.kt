/*
 * Task #460 — typed actions for a decoded barcode payload. A scan never dumps
 * the raw string at the user: each payload kind opens the matching surface
 * (fleet browser for URLs, system Wi-Fi suggestion, contact insert, calendar
 * insert, dialer, mail composer, map) and falls back to copy for anything the
 * parser did not classify. Every function returns null on success or a
 * user-facing reason it could not run — the sheet shows that reason, never a
 * silent empty box.
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.diegonmarcos.superapp.image.mlkit.BarcodePayload

/** Context extension surface for the typed actions. */
object ScanActions {

    /**
     * The fleet's own browser surface (#156). URL opens are pinned to this
     * package so a scanned link stays inside the constellation instead of
     * being handed to an arbitrary ACTION_VIEW handler that leaves the app.
     * Mirrors cloud-camera's openScannedUrl contract.
     */
    private const val FLEET_BROWSER_PACKAGE = "com.diegonmarcos.cloudbrowser"

    /** Opens [payload] through the surface its type names. Returns null on success. */
    fun perform(context: Context, payload: BarcodePayload): String? = when (payload) {
        is BarcodePayload.Url -> openUrl(context, payload.url)
        is BarcodePayload.Wifi -> joinWifi(context, payload.ssid, payload.password)
        is BarcodePayload.Contact -> insertContact(context, payload.vcard, payload.name)
        is BarcodePayload.Calendar -> insertCalendarEvent(
            context,
            payload.summary.orEmpty(),
            payload.location.orEmpty(),
            payload.startTimeEpochMillis,
            payload.endTimeEpochMillis
        )
        is BarcodePayload.Phone -> dialNumber(context, payload.number)
        is BarcodePayload.Email -> composeEmail(context, payload.address, payload.subject, payload.body)
        is BarcodePayload.Geo -> openGeo(context, payload.latitude, payload.longitude)
        is BarcodePayload.Plain -> null // no action surface; the sheet keeps Copy
    }

    fun openUrl(context: Context, url: String): String? {
        val parsed = try {
            Uri.parse(url.trim())
        } catch (error: Exception) {
            return "the payload is not a usable address"
        }
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            return "only web addresses open here"
        }
        val intent = Intent(Intent.ACTION_VIEW, parsed).apply {
            setPackage(FLEET_BROWSER_PACKAGE)
        }
        return try {
            if (context.packageManager.resolveActivity(intent, 0) == null) {
                "the fleet browser is not installed — the link stays copyable"
            } else {
                context.startActivity(intent)
                null
            }
        } catch (error: Exception) {
            "could not open the link"
        }
    }

    /**
     * Joins a Wi-Fi network from a WIFI: payload. Android refuses to let an
     * app add a network silently, so this adds a SUGGESTION and the user
     * approves it in the system panel — the same interaction every other
     * scanner app has. minSdk 29 is Android 10, so the legacy addNetwork path
     * never needs to exist here.
     */
    fun joinWifi(context: Context, ssid: String, password: String): String? {
        if (ssid.isBlank()) return "the Wi-Fi payload carries no network name"
        return try {
            val manager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val builder = android.net.wifi.WifiNetworkSuggestion.Builder().setSsid(ssid)
            if (password.isNotEmpty()) builder.setWpa2Passphrase(password)
            when (manager.addNetworkSuggestions(listOf(builder.build()))) {
                WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
                    "Android is asking you to approve the network"
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
                    "this network is already suggested to Android"
                else -> "Android declined the Wi-Fi suggestion"
            }
        } catch (error: SecurityException) {
            "Wi-Fi permission is refused — grant location or Wi-Fi access in system settings"
        } catch (error: Exception) {
            "cannot join the network"
        }
    }

    /** Opens the system contact-insert screen with the card's name and first TEL. */
    fun insertContact(context: Context, vcard: String, name: String?): String? {
        // ContactsContract.Intents.Insert is a CLASS holding static fields, not
        // a value — binding it to a local and reading insert.ACTION fails with
        // "classifier does not have a companion object". Reference the
        // statics through the type name directly.
        val intent = Intent(ContactsContract.Intents.Insert.ACTION)
        if (!name.isNullOrBlank()) intent.putExtra(ContactsContract.Intents.Insert.NAME, name)
        val telephone = vcard.lineSequence()
            .firstOrNull { it.trim().startsWith("TEL", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        if (!telephone.isNullOrEmpty()) intent.putExtra(ContactsContract.Intents.Insert.PHONE, telephone)
        return try {
            context.startActivity(intent)
            null
        } catch (error: Exception) {
            "no contacts app opened"
        }
    }

    /** Opens the system calendar's new-event screen pre-filled from a VEVENT payload. */
    fun insertCalendarEvent(
        context: Context,
        summary: String,
        location: String,
        startMillis: Long?,
        endMillis: Long?
    ): String? {
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        if (summary.isNotBlank()) intent.putExtra(CalendarContract.Events.TITLE, summary)
        if (location.isNotBlank()) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        val start = startMillis ?: 0L
        val end = endMillis ?: 0L
        if (start > 0L) {
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            if (end > start) intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (error: Exception) {
            "no calendar app opened"
        }
    }

    /** The dialer, pre-filled with a tel: payload. */
    fun dialNumber(context: Context, number: String): String? = try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        null
    } catch (error: Exception) {
        "no dialer opened"
    }

    /** The mail app, pre-filled from a mailto: payload. */
    fun composeEmail(context: Context, address: String, subject: String?, body: String?): String? =
        try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address)))
            if (!subject.isNullOrBlank()) intent.putExtra(Intent.EXTRA_SUBJECT, subject)
            if (!body.isNullOrBlank()) intent.putExtra(Intent.EXTRA_TEXT, body)
            context.startActivity(intent)
            null
        } catch (error: Exception) {
            "no mail app opened"
        }

    /** The map app, centred on a geo: payload's coordinates. */
    fun openGeo(context: Context, latitude: Double, longitude: Double): String? = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude")))
        null
    } catch (error: Exception) {
        "no map app opened"
    }

    /** Copies text into the system clipboard; returns false when the system refused. */
    fun copyText(context: Context, text: String): Boolean = try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cloud-media-center", text))
        true
    } catch (error: Exception) {
        false
    }
}