package com.diegonmarcos.superapp.fleetendpoints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1093 THE GUARD: no app talks to a fleet host without going through libs:fleet-endpoints. Every main-source file of the
 * fleet (apps, shared libs) that names a fleet host (a `*.diegonmarcos.com` host or an `http(s)://*.app` mesh URL) in code
 * must use the transport (FleetHttp, FleetTransport, the interceptors, the AuthHttp seam) or be listed in
 * `fleet-host-baseline.txt` with its reason. The list is a ratchet: it may only shrink. A listed file that no longer names a
 * fleet host or now uses the transport fails until its line is removed, and a new file that names one fails until it routes.
 * The scan logic is [FleetHostScan]; the cases below prove it flags what it must and passes what it must.
 */
class FleetHostGuardTest {
    private fun repoRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "ab_cloud-libs-shared").isDirectory && File(d, "ac_cloud-account").isDirectory) return d
            d = d.parentFile
        }
        error("the repository root (ab_cloud-libs-shared next to ac_cloud-account) was not found above ${System.getProperty("user.dir")}")
    }

    private fun baseline(root: File): Map<String, String> =
        File(root, "ab_cloud-libs-shared/libs/fleet-endpoints/fleet-host-baseline.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { l -> l.substringBefore('\t') to l.substringAfter('\t', "").trim() }

    @Test fun `every file naming a fleet host goes through the transport or is listed with its reason`() {
        val root = repoRoot()
        val listed = baseline(root)
        val found = FleetHostScan.scan(root)
        val direct = found.filter { !it.usesTransport }.map { it.path }.toSet()
        val unlisted = direct - listed.keys
        val stale = listed.keys - direct
        assertTrue("these files name a fleet host and do not go through libs:fleet-endpoints (route them, or list them with a reason in fleet-host-baseline.txt): $unlisted", unlisted.isEmpty())
        assertTrue("these baseline lines no longer apply (the file routes or names no fleet host): remove them: $stale", stale.isEmpty())
        assertTrue("every baseline line carries a reason", listed.values.all { it.length >= 10 })
        assertTrue("the scan saw the fleet's sources (not an empty tree)", found.size >= 5)
    }

    @Test fun `the scan flags a fleet host in code and ignores comments, emails and package names`() {
        val s = FleetHostScan.analyse("val u = \"https://git.diegonmarcos.com/api\"")
        assertEquals(listOf("git.diegonmarcos.com"), s.hosts); assertFalse(s.usesTransport)
        assertEquals(listOf("http://git-proxy-api.app"), FleetHostScan.analyse("const val B = \"http://git-proxy-api.app:8123\"").hosts)
        assertTrue(FleetHostScan.analyse("// see https://git.diegonmarcos.com\n/* auth.diegonmarcos.com */ val a = 1").hosts.isEmpty())
        assertTrue(FleetHostScan.analyse("val m = \"me@diegonmarcos.com\"; package com.diegonmarcos.comms.mail").hosts.isEmpty())
        assertTrue(FleetHostScan.analyse("val p = \"com.example.app\"").hosts.isEmpty())
    }

    @Test fun `a file that uses the transport passes even when it names a host`() {
        assertTrue(FleetHostScan.analyse("import com.diegonmarcos.superapp.fleetendpoints.FleetHttp\nval u = \"https://git.diegonmarcos.com\"").usesTransport)
        assertTrue(FleetHostScan.analyse("AuthHttp.transport = null; val u = \"https://auth.diegonmarcos.com\"").usesTransport)
    }
}

/** The scan the guard runs: pure over a file's text, so its rules are tested without a tree. */
object FleetHostScan {
    class Hit(val path: String, val hosts: List<String>, val usesTransport: Boolean)
    class Analysis(val hosts: List<String>, val usesTransport: Boolean)

    private val host = Regex("""[A-Za-z0-9-]+\.diegonmarcos\.com\b|https?://[A-Za-z0-9-]+\.app\b""")
    private val uses = Regex("""fleetendpoints|FleetHttp|AuthHttp|FleetTransport""")

    fun analyse(source: String): Analysis {
        val code = source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)^\\s*//.*$|\\s//\\s.*$"), "")
        return Analysis(host.findAll(code).map { it.value }.distinct().sorted().toList(), uses.containsMatchIn(code))
    }

    /** Main-source files of the apps (`ac_*`, `aa_*`) and the shared libs, except the transport's own tree. */
    fun scan(root: File): List<Hit> {
        val tops = listOf(File(root, "ab_cloud-libs-shared/libs")) + (root.listFiles().orEmpty().filter { it.isDirectory && (it.name.startsWith("ac_") || it.name.startsWith("aa_")) })
        val out = mutableListOf<Hit>()
        for (top in tops) top.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }.forEach { f ->
            if (!f.isFile || !(f.name.endsWith(".kt") || f.name.endsWith(".java"))) return@forEach
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if ("/src/main/" !in rel || rel.startsWith("ab_cloud-libs-shared/libs/fleet-endpoints/")) return@forEach
            val a = analyse(f.readText())
            if (a.hosts.isNotEmpty()) out += Hit(rel, a.hosts, a.usesTransport)
        }
        return out
    }
}
