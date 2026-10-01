package com.diegonmarcos.superapp.image.mlkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed-payload contract, tested in the unit phase of EVERY consumer build
 * (cloud-drive and cloud-media-center both compile this module). A regression
 * here would silently turn a scan back into a raw string dump on two apps, so
 * each payload kind gets its own shape assertion, not just a type check.
 */
class BarcodePayloadParserTest {

    @Test fun httpUrl() {
        val payload = BarcodePayloadParser.parse("https://example.com/a?b=1")
        assertEquals(BarcodePayload.Url("https://example.com/a?b=1"), payload)
    }

    @Test fun urlIsNotTel() {
        // "tel:123" is a phone number, never a URL — the parser must not let a
        // scheme test leak into the http/https branch.
        assertTrue(BarcodePayloadParser.parse("tel:123") is BarcodePayload.Phone)
    }

    @Test fun wpaWifi() {
        val payload = BarcodePayloadParser.parse("WIFI:T:WPA;S:home-net;P:secret;H:false;;")
        assertEquals(BarcodePayload.Wifi("home-net", "secret", "WPA", false), payload)
    }

    @Test fun openWifi() {
        val payload = BarcodePayloadParser.parse("WIFI:S:guest;;")
        assertEquals(BarcodePayload.Wifi("guest", "", "", false), payload)
    }

    @Test fun wifiWithoutSsidIsPlain() {
        // No network name means the payload is not actionable; refusing to
        // guess is the honest outcome, not a blank SSID action.
        assertTrue(BarcodePayloadParser.parse("WIFI:T:WPA;P:secret;;") is BarcodePayload.Plain)
    }

    @Test fun mecardContact() {
        val payload = BarcodePayloadParser.parse("MECARD:N:Doe,John;TEL:5551234;;")
        assertEquals("Doe,John", (payload as BarcodePayload.Contact).name)
        assertTrue(payload.vcard.startsWith("MECARD:"))
    }

    @Test fun vcardContact() {
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:Jane Roe\nTEL:5559876\nEND:VCARD"
        val payload = BarcodePayloadParser.parse(vcard)
        assertEquals("Jane Roe", (payload as BarcodePayload.Contact).name)
        assertTrue(payload.vcard.startsWith("BEGIN:VCARD"))
    }

    @Test fun veventCalendar() {
        val vevent = "BEGIN:VEVENT\nDTSTART:20260917T100000Z\nDTEND:20260917T110000Z\n" +
            "SUMMARY:Review\nLOCATION:Room 4\nEND:VEVENT"
        val payload = BarcodePayloadParser.parse(vevent)
        assertEquals("Review", (payload as BarcodePayload.Calendar).summary)
        assertEquals("Room 4", payload.location)
        val start = payload.startTimeEpochMillis
        // 2026-09-17T10:00:00Z / 11:00:00Z, computed as UTC instants.
        assertEquals(1789639200000L, start)
        assertEquals(1789642800000L, payload.endTimeEpochMillis)
    }

    @Test fun telPhone() {
        assertEquals(BarcodePayload.Phone("+15551234567"), BarcodePayloadParser.parse("tel:+15551234567"))
    }

    @Test fun mailtoWithSubjectAndBody() {
        val payload = BarcodePayloadParser.parse("mailto:hi@example.com?subject=Hello&body=How%20are%20you")
        assertEquals(BarcodePayload.Email("hi@example.com", "Hello", "How are you"), payload)
    }

    @Test fun geo() {
        assertEquals(BarcodePayload.Geo(37.422, -122.084), BarcodePayloadParser.parse("geo:37.422,-122.084?z=15"))
    }

    @Test fun unknownIsPlain() {
        val payload = BarcodePayloadParser.parse("SOME-ARBITRARY-STRING")
        assertEquals(BarcodePayload.Plain("SOME-ARBITRARY-STRING"), payload)
    }

    @Test fun blankIsPlain() {
        assertTrue(BarcodePayloadParser.parse("") is BarcodePayload.Plain)
    }

    @Test fun plainHasNoNullText() {
        // The copy fallback reads p.text; a null there would render "null".
        val payload = BarcodePayloadParser.parse("   ")
        assertEquals("", (payload as BarcodePayload.Plain).text)
    }
}