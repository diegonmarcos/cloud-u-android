package com.diegonmarcos.superapp.perms

import com.diegonmarcos.superapp.wifi.ScanThrottle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1274 the Permissions page is the ONE place that asks: no contract lib requests a permission or starts a permission
 * settings page itself, the Wi-Fi and Bluetooth banners go through PermsLink, the page is in the nav walk, and Wi-Fi Auto
 * mode stays inside Android's scan budget.
 */
class PermsOnePlaceTest {
    private val root = File("../..")
    private val libs = File(root, "ab_cloud-libs-shared/libs")
    private val catalogue = JSONObject(File(libs, "core/src/main/assets/perms-catalogue.json").readText())
    private val app = File(root, "aa_cloud-superapp")

    private val forbidden = listOf(
        "RequestMultiplePermissions", "ActivityResultContracts.RequestPermission", "requestPermissions(",
        "ACTION_APPLICATION_DETAILS_SETTINGS", "APPLICATION_DETAILS_SETTINGS", "MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
        "MANAGE_ALL_FILES_ACCESS_PERMISSION", "ACTION_MANAGE_OVERLAY_PERMISSION", "MANAGE_OVERLAY_PERMISSION",
        "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "IGNORE_BATTERY_OPTIMIZATION_SETTINGS", "USAGE_ACCESS_SETTINGS",
        "NOTIFICATION_LISTENER_SETTINGS", "ACTION_ACCESSIBILITY_SETTINGS", "MANAGE_WRITE_SETTINGS", "MANAGE_UNKNOWN_APP_SOURCES",
        "REQUEST_SCHEDULE_EXACT_ALARM", "NOTIFICATION_POLICY_ACCESS_SETTINGS", "pm grant",
    )
    private val comments = Regex("//[^\\n]*|/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

    /** The forbidden calls in one source text, comments stripped. */
    private fun violations(text: String): List<String> { val code = comments.replace(text, ""); return forbidden.filter { code.contains(it) } }

    private fun contractLibs(): List<String> = catalogue.getJSONArray("contract_libs").let { a -> (0 until a.length()).map { a.getString(it) } }

    @Test fun `no contract lib asks for a permission or opens a permission settings page`() {
        val libsList = contractLibs()
        assertTrue(libsList.containsAll(listOf("wifi", "bluetooth", "nfc", "mounts")))
        val hits = ArrayList<String>()
        var scanned = 0
        for (l in libsList) {
            val dir = File(libs, "$l/src/main"); if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { f ->
                scanned++
                violations(f.readText()).forEach { hits += "${f.relativeTo(libs)}: $it" }
            }
        }
        assertTrue("guard scanned nothing", scanned > 10)
        assertEquals("feature libs must go through PermsNeeds + PermsLink", emptyList<String>(), hits)
    }

    @Test fun `the guard sees each kind of violation (planted)`() {
        for (f in forbidden) assertEquals(f, listOf(f).take(1), violations("fun x() { val a = \"$f\" }").filter { it == f }.take(1))
        assertTrue(violations("// requestPermissions( in a comment\n/* pm grant */").isEmpty())
        assertFalse(violations("ask.launch(x); rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions())").isEmpty())
    }

    @Test fun `wifi and bluetooth show the banner that deep-links to the Permissions page`() {
        val wifi = File(libs, "wifi/src/main/java/com/diegonmarcos/superapp/wifi/WifiUi.kt").readText()
        assertTrue(wifi.contains("PermsNeeds.missing(ctx, \"wifi\")") && wifi.contains("PermsLink.open(") && wifi.contains("wifi:perms-banner"))
        assertFalse(wifi.contains("wifi:permissions"))
        val bt = File(libs, "bluetooth/src/main/java/com/diegonmarcos/superapp/bluetooth/BluetoothUi.kt").readText()
        assertTrue(bt.contains("PermsLink.open(") && bt.contains("bluetooth:perms-banner"))
    }

    @Test fun `the shell hosts the page and the nav walk reaches it`() {
        val shell = File(app, "app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt").readText()
        assertTrue(shell.contains("core.perms.PermsHost") && shell.contains("override fun openPermsPage(): Boolean { openSectionPage(\"config\", \"perms\")"))
        val pages = File(app, "app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt").readText()
        assertTrue(Regex("pageId == \"perms\"\\s*->\\s*\\n?\\s*com.diegonmarcos.superapp.configs.PermissionsFragment").containsMatchIn(pages))
        // The nav walk drives every ui.sections[].pages id of build.json (cloud-android-nav-walk.py pages_of).
        val ui = JSONObject(File(app, "build.json").readText()).getJSONObject("ui")
        fun has(o: Any?): Boolean = when (o) {
            is JSONObject -> o.keys().asSequence().any { k -> (k == "pages" && o.get(k).toString().contains("\"perms\"")) || has(o.get(k)) }
            is org.json.JSONArray -> (0 until o.length()).any { has(o.get(it)) }
            else -> false
        }
        assertTrue("config/perms is not a declared page, the nav walk would skip it", has(ui.opt("sections")))
        val frag = File(app, "app/src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt").readText()
        assertTrue(frag.contains("CatalogueHead()") && frag.contains("PermsLink.consume()") && frag.contains("AutoRequestPlanner.batches"))
    }

    // ---- Wi-Fi scan budget ----

    @Test fun `auto mode never drains the 4-per-2-minute budget`() {
        val t = ScanThrottle(4, 120_000)
        var last = 0L; var scans = 0
        for (now in 0L until 600_000L step 1_000L) {
            if (t.autoDue(now, last, 5_000)) { t.record(now); last = now; scans++ }
            assertTrue("auto left no scan for the button at $now", t.remaining(now) >= 1)
        }
        assertTrue(scans in 15..20)   // one slot every 30 s, minus the reserve
    }

    @Test fun `a refused scan is not counted and backs off one slot`() {
        val t = ScanThrottle(4, 120_000)
        t.refuse(1_000)
        assertEquals(4, t.remaining(1_000))
        assertFalse(t.canScan(20_000)); assertTrue(t.canScan(31_000))
        assertEquals(31_000, t.nextAllowedAt(2_000))
    }

    @Test fun `budget 0 blocks every scan until the window frees a slot`() {
        val t = ScanThrottle(4, 120_000)
        for (i in 0 until 4) t.record(i * 1_000L)
        assertFalse(t.canScan(10_000)); assertFalse(t.autoDue(10_000, 0, 5_000))
        assertTrue(t.canScan(120_000))
        val ctl = File(libs, "wifi/src/main/java/com/diegonmarcos/superapp/wifi/WifiController.kt").readText()
        assertTrue(ctl.contains("throttle.autoDue(") && ctl.contains("if (ok) throttle.record(now) else throttle.refuse(now)"))
        assertTrue("no startScan while a permission is missing", ctl.indexOf("s.missing.isNotEmpty() || !s.locationOn || !s.enabled") in 1 until ctl.indexOf("src.startScan()"))
    }
}
