package com.diegonmarcos.superapp.fleetconfigs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The baked Configs > General / About declaration decodes to what the pages draw; a missing or broken one falls back, never crashes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FleetConfigsTest {
    private val json = """
        {"app":"ac_cloud-x","name":"Cloud X","version":"1.2.0","version_code":7,"sha":"abc123def456","application_id":"com.x",
         "store_pkg":"com.diegonmarcos.cloudstore","superapp_pkg":"com.diegonmarcos.superapp",
         "general":{"sections":[{"id":"perms"},{"id":"ui","note":"n"},{"id":"app","label":"Cloud X"}]},
         "about":{"what":"Does x.","features":["a","b"],"repo":"https://r","license":"MIT","privacy":"p",
                  "backends":[{"id":"jmap","why":"mail","kind":"mail","mesh":"http://stalwart.mesh:8080"}],"crash":"https://c","debug":"https://d"}}
    """.trimIndent()

    @Test fun `a declaration decodes field by field`() {
        val d = FleetConfigs.decode(java.util.Base64.getEncoder().encodeToString(json.toByteArray()))
        assertEquals("Cloud X", d.name)
        assertEquals(7, d.versionCode)
        assertEquals(listOf("perms", "ui", "app"), d.general.map { it.id })
        assertEquals("Perms", d.general[0].label)
        assertEquals("Cloud X", d.general[2].label)
        assertEquals("n", d.general[1].note)
        assertEquals(listOf("a", "b"), d.about.features)
        assertEquals("http://stalwart.mesh:8080", d.about.backends.single().mesh)
        assertEquals("com.diegonmarcos.cloudstore", d.storePkg)
    }

    @Test fun `no declaration still draws the six required sections`() {
        assertEquals(FleetConfigs.REQUIRED_SECTIONS, FleetConfigs.decode("").general.map { it.id })
        assertEquals(FleetConfigs.REQUIRED_SECTIONS, FleetConfigs.decode("not base64!").general.map { it.id })
    }

    @Test fun `the licence reads as words`() {
        assertTrue(FleetConfigs.licenceLabel("LicenseRef-NoLicenseGranted").startsWith("All rights reserved"))
        assertEquals("MIT", FleetConfigs.licenceLabel("MIT"))
    }
}
