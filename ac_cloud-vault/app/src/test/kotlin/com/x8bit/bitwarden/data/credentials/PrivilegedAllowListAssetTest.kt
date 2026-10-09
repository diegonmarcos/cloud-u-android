package com.x8bit.bitwarden.data.credentials

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the privileged-browser allow lists shipped in `assets/`. Source of the Google list and
 * how to refresh it: `scripts/refresh-fido2-privileged-google.sh`.
 */
class PrivilegedAllowListAssetTest {

    private fun androidApps(fileName: String): Map<String, List<String>> =
        Json.parseToJsonElement(File("src/main/assets/$fileName").readText())
            .jsonObject
            .getValue("apps")
            .jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "android" }
            .associate { app ->
                val info = app.getValue("info").jsonObject
                info.getValue("package_name").jsonPrimitive.content to
                    (info.getValue("signatures") as JsonArray).map {
                        (it as JsonObject).getValue("cert_fingerprint_sha256").jsonPrimitive.content
                    }
            }

    @Test
    fun `Google allow list parses and every entry has a SHA-256 certificate fingerprint`() {
        val apps = androidApps("fido2_privileged_google.json")

        assertTrue(apps.size > 50)
        apps.forEach { (pkg, fingerprints) ->
            assertTrue(fingerprints.isNotEmpty(), "$pkg has no signatures")
            fingerprints.forEach {
                assertTrue(
                    Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(it),
                    "$pkg has a malformed fingerprint $it",
                )
            }
        }
    }

    @Test
    fun `Google allow list covers the mainstream browsers`() {
        val apps = androidApps("fido2_privileged_google.json")

        listOf(
            "com.android.chrome",
            "com.brave.browser",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.duckduckgo.mobile.android",
        ).forEach { assertTrue(apps.containsKey(it), "$it missing from the allow list") }
    }

    @Test
    fun `Brave release certificate is the one Google lists`() {
        val apps = androidApps("fido2_privileged_google.json")

        assertEquals(
            listOf(BRAVE_RELEASE_CERT),
            apps.getValue("com.brave.browser"),
        )
    }

    @Test
    fun `Community allow list pins our own apps to the fleet release certificate`() {
        val apps = androidApps("fido2_privileged_community.json")

        listOf("com.diegonmarcos.cloudbrowser", "com.diegonmarcos.cloudvault").forEach {
            assertEquals(listOf(FLEET_RELEASE_CERT), apps[it], it)
        }
    }

    @Test
    fun `Community allow list parses`() {
        assertTrue(androidApps("fido2_privileged_community.json").isNotEmpty())
    }
}

private const val BRAVE_RELEASE_CERT =
    "9C:2D:B7:05:13:51:5F:DB:FB:BC:58:5B:3E:DF:3D:71:23:D4:DC:67:C9:4F:FD:30:63:61:C1:D7:9B:BF:18:AC"
private const val FLEET_RELEASE_CERT =
    "50:7E:56:A3:5B:0E:0D:7E:0A:CE:55:16:F4:94:96:E6:2F:ED:A7:21:ED:6C:17:6D:DF:B3:34:12:9C:EE:18:99"
