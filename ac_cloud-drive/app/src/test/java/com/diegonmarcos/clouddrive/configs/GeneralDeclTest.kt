package com.diegonmarcos.clouddrive.configs

import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.configs.GeneralDecl.Status
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1333 build.json::ui.general (the anchor row and the Perms list of Configs > General), parsed by the SAME parser the phone
 * runs, against THIS app's build.json and AndroidManifest.xml. Pure JVM: no Android class is touched.
 */
class GeneralDeclTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val general: GeneralDecl.General = GeneralDecl.parse(
        Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["general"].toString(),
    )
    private val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()

    /** `${terminalPackage}` in the manifest is `{terminalPackage}` in the declaration. */
    private val manifestPerms: Set<String> =
        Regex("<uses-permission\\s+android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1].replace("\${", "{") }.toSet()
    private fun entry(id: String) = general.perms.first { it.id == id }
    private fun e(check: String, min: Int? = null, max: Int? = null, oem: String = "", pkg: String = "") =
        GeneralDecl.PermEntry("x", "X", "why", emptyList(), check, "settings", emptyList(), minSdk = min, maxSdk = max, oem = oem, requiresPackage = pkg)

    @Test fun everyManifestPermissionIsDeclaredAndEveryDeclaredOneIsInTheManifest() {
        val declared = general.perms.flatMap { it.permissions }.toSet()
        assertTrue("the manifest has no uses-permission lines?", manifestPerms.isNotEmpty())
        assertEquals("in the manifest but not in ui.general.perms", emptySet<String>(), manifestPerms - declared)
        assertEquals("in ui.general.perms but missing from the manifest", emptySet<String>(), declared - manifestPerms)
    }

    @Test fun anchorsAreUniqueIconedAndEndWithPermsBeforeDevice() {
        val ids = general.anchors.map { it.id }
        assertEquals(listOf("explorer", "manage", "agents", "perms", "device"), ids)
        assertEquals(ids.size, ids.toSet().size)
        general.anchors.forEach {
            assertTrue("${it.id} has no label", it.label.isNotBlank())
            assertTrue("anchor icon '${it.icon}' is not in IconCatalog", IconCatalog.knows(it.icon))
        }
    }

    @Test fun anchorsMatchTheSectionsGeneralPageDrawsInOrder() {
        val page = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/configs/GeneralPage.kt").readText()
        val drawn = Regex("^\\s+\"([a-z_]+)\" ->", RegexOption.MULTILINE).findAll(page.substringAfter("when (a.id)").substringBefore("\n    }\n}")).map { it.groupValues[1] }.toList()
        assertEquals(general.anchors.map { it.id }, drawn)
    }

    @Test fun theBatteryOptimisationEntryExistsWithItsIntent() {
        val b = entry("battery_opt")
        assertTrue("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in b.permissions)
        assertEquals("battery_opt", b.check)
        assertEquals("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", b.intents.first().action)
        assertTrue("the request names the package", b.intents.first().packageUri)
        assertTrue("a fallback screen exists when the request is refused", b.intents.size >= 2)
    }

    @Test fun theSamsungEntryTriesTheNeverSleepingComponentFirstAndFallsBackToAppInfo() {
        val s = entry("oem_samsung")
        assertEquals("samsung", s.oem)
        assertTrue(s.intents.first().component.startsWith("com.samsung.android.lool/"))
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", s.intents.last().action)
    }

    @Test fun everyEntryIsWellFormed() {
        assertEquals(general.perms.size, general.perms.map { it.id }.toSet().size)
        general.perms.forEach {
            assertTrue("${it.id} check", it.check in setOf("permission", "all_files", "battery_opt", "install_unknown", "appop", "declared", "oem", "engine_all_files"))
            assertTrue("${it.id} grant", it.grant in setOf("runtime", "settings", "none"))
            assertTrue("${it.id} has a reason", it.why.length > 20)
            if (it.grant != "none") assertTrue("${it.id} grants nothing without an intent", it.intents.isNotEmpty() || it.grant == "runtime")
            if (it.check == "appop") assertTrue("${it.id} names its appop", it.appop.isNotBlank())
        }
        listOf("notifications", "all_files", "termux_run_command", "install_unknown", "fgs", "network").forEach { assertNotNull(entry(it)) }
    }

    @Test fun statusMapping() {
        assertEquals(Status.GRANTED, GeneralDecl.status(e("permission"), 34, "samsung", true))
        assertEquals(Status.DENIED, GeneralDecl.status(e("permission"), 34, "samsung", false))
        assertEquals(Status.DENIED, GeneralDecl.status(e("permission"), 34, "samsung", null))
        assertEquals(Status.NOT_APPLICABLE, GeneralDecl.status(e("permission", min = 33), 32, "samsung", false))
        assertEquals(Status.GRANTED, GeneralDecl.status(e("permission", min = 33), 33, "samsung", true))
        assertEquals(Status.NOT_APPLICABLE, GeneralDecl.status(e("permission", max = 29), 30, "samsung", true))
        assertEquals(Status.NOT_APPLICABLE, GeneralDecl.status(e("oem", oem = "samsung"), 34, "Google", null))
        assertEquals(Status.INFO, GeneralDecl.status(e("oem", oem = "samsung"), 34, "samsung", null))
        assertEquals(Status.INFO, GeneralDecl.status(e("declared"), 34, "samsung", true))
        assertEquals(Status.NOT_APPLICABLE, GeneralDecl.status(e("permission", pkg = "{terminalPackage}"), 34, "samsung", false, requiredPackageInstalled = false))
        assertEquals(Status.DENIED, GeneralDecl.status(e("permission", pkg = "{terminalPackage}"), 34, "samsung", false, requiredPackageInstalled = true))
    }

    @Test fun placeholdersResolveAndUnknownOnesStay() {
        assertEquals("com.t.permission.RUN_COMMAND", GeneralDecl.resolve("{terminalPackage}.permission.RUN_COMMAND", mapOf("terminalPackage" to "com.t")))
        assertEquals("{nope}.x", GeneralDecl.resolve("{nope}.x", emptyMap()))
        assertFalse(GeneralDecl.parse("not json").anchors.isNotEmpty())
    }
}
