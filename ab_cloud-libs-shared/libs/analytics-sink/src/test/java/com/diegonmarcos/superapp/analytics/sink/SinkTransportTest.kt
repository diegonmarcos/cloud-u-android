package com.diegonmarcos.superapp.analytics.sink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire formats the sink engine speaks, EXECUTED: what libs:analytics used to build inside
 * every app (pre-#871 Analytics.sendUmami / sendMatomo) is pinned here byte for byte, so the move
 * to the engine cannot change what Umami and Matomo receive.
 */
class SinkTransportTest {

    private fun request(name: String, props: JSONObject = JSONObject(), base: String = "https://analytics.example.org/umami") =
        JSONObject()
            .put("app", "cloud-drive").put("base", base).put("site", "site-1").put("name", name)
            .put("props", props).put("visitor", "0123456789abcdef").put("ua", "Mozilla/5.0 (Linux; Android 15; Pixel) CloudSuperApp/cloud-drive")

    @Test fun umamiPageviewCarriesTheScreenAndNoEventName() {
        val p = SinkTransport.umami(request("pageview", JSONObject().put("screen", "settings")))
        assertEquals("https://analytics.example.org/umami/api/send", p.url)
        assertEquals("application/json", p.contentType)
        val payload = JSONObject(p.body).getJSONObject("payload")
        assertEquals("event", JSONObject(p.body).getString("type"))
        assertEquals("site-1", payload.getString("website"))
        assertEquals("cloud-drive", payload.getString("hostname"))
        assertEquals("/cloud-drive/settings", payload.getString("url"))
        assertEquals("settings", payload.getString("title"))
        assertTrue("a pageview has no event name", !payload.has("name"))
        assertEquals("settings", payload.getJSONObject("data").getString("screen"))
    }

    @Test fun umamiNamedEventKeepsItsNameAndProperties() {
        val p = SinkTransport.umami(request("export", JSONObject().put("format", "csv")))
        val payload = JSONObject(p.body).getJSONObject("payload")
        assertEquals("export", payload.getString("name"))
        assertEquals("/cloud-drive/export", payload.getString("url"))
        assertEquals("csv", payload.getJSONObject("data").getString("format"))
    }

    @Test fun umamiEventWithoutPropertiesSendsNoDataObject() {
        val payload = JSONObject(SinkTransport.umami(request("tap")).body).getJSONObject("payload")
        assertTrue(!payload.has("data"))
    }

    @Test fun matomoPageviewIsTheRawTrackingApiForm() {
        val p = SinkTransport.matomo(request("pageview", JSONObject().put("screen", "settings"), "https://analytics.example.org/matomo"), rand = 42)
        assertEquals("https://analytics.example.org/matomo/matomo.php", p.url)
        assertEquals("application/x-www-form-urlencoded", p.contentType)
        assertEquals(
            "idsite=site-1&rec=1&apiv=1&_id=0123456789abcdef&rand=42" +
                "&action_name=cloud-drive%2Fsettings&url=app%3A%2F%2Fcloud-drive%2Fsettings",
            p.body)
    }

    @Test fun matomoNamedEventAddsCategoryAndAction() {
        val p = SinkTransport.matomo(request("export a/b"), rand = 7)
        assertTrue(p.body, p.body.endsWith("&e_c=cloud-drive&e_a=export+a%2Fb"))
    }

    @Test fun theHostAppsUserAgentIsWhatIsSent() {
        assertEquals("Mozilla/5.0 (Linux; Android 15; Pixel) CloudSuperApp/cloud-drive", SinkTransport.umami(request("tap")).ua)
    }

    @Test fun onlyHttpsToTheFleetsOwnAnalyticsHostsIsAllowed() {
        val hosts = listOf("analytics.example.org")
        assertNull(SinkTransport.refusal("https://analytics.example.org/umami/api/send", hosts))
        assertNotNull("plain http", SinkTransport.refusal("http://analytics.example.org/umami/api/send", hosts))
        assertNotNull("another host", SinkTransport.refusal("https://evil.example.net/api/send", hosts))
        assertNotNull("a host that merely starts with ours", SinkTransport.refusal("https://analytics.example.org.evil.net/x", hosts))
        assertNotNull("not a url", SinkTransport.refusal("not a url", hosts))
    }
}
