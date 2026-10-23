package com.diegonmarcos.superapp.fleetendpoints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import java.io.File

/**
 * #1093 THE GUARD: no app talks to a fleet host without going through libs:fleet-endpoints. Every main-source file of the
 * fleet (apps, shared libs) that names a fleet host (a `*.diegonmarcos.com` host or an `http(s)` mesh URL on a `.app` host) in code
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

    @Test fun `every app's cleartext mesh allowlist is generated from the declaration and no public host is cleartext`() {
        val root = repoRoot()
        val cfg = org.json.JSONObject(File(root, "1_cicd/src/data/fleet-nsc.json").readText())
        val services = org.json.JSONObject(File(root, cfg.getString("declaration")).readText()).getJSONObject("fleet_endpoints").getJSONArray("services")
        val begin = Regex("<!-- BEGIN fleet-mesh-cleartext[^>]*-->"); val end = "<!-- END fleet-mesh-cleartext -->"
        val listed = mutableSetOf<String>()
        val apps = cfg.getJSONArray("apps")
        for (i in 0 until apps.length()) {
            val a = apps.getJSONObject(i); val path = a.getString("nsc"); listed += path
            val wanted: (String) -> Boolean = if (a.get("services") == "*") { _ -> true } else { id -> (0 until a.getJSONArray("services").length()).any { a.getJSONArray("services").getString(it) == id } }
            val expected = (0 until services.length()).map { services.getJSONObject(it) }
                .filter { it.optString("mesh_scheme", "https") == "http" && wanted(it.getString("id")) }.map { it.getString("mesh_dns") }.toSortedSet()
            val xml = File(root, path).readText()
            val m = begin.find(xml)
            assertTrue("$path has no generated block: run 1_cicd/src/scripts/cloud-android-fleet-nsc.py write", m != null)
            val body = xml.substring(m!!.range.last + 1, xml.indexOf(end, m.range.last))
            val have = Regex("<domain[^>]*>([^<]+)</domain>").findAll(body).map { it.groupValues[1] }.toSortedSet()
            assertEquals("$path: the generated cleartext hosts differ from the declaration (run cloud-android-fleet-nsc.py write)", expected, have)
        }
        // no hand-listed mesh host and no public host in cleartext, in ANY app's config
        val generated = Regex("<!-- BEGIN fleet-mesh-cleartext.*?<!-- END fleet-mesh-cleartext -->", RegexOption.DOT_MATCHES_ALL)
        val handApp = Regex("<domain[^>]*>\\s*[A-Za-z0-9.-]*\\.app\\s*</domain>"); val publicHost = Regex("<domain[^>]*>\\s*[A-Za-z0-9.-]*diegonmarcos\\.com\\s*</domain>")
        root.listFiles().orEmpty().filter { it.isDirectory && (it.name.startsWith("ac_") || it.name.startsWith("aa_")) }.forEach { app ->
            app.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }.filter { it.name == "network_security_config.xml" }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                val xml = f.readText().replace(Regex("<!--(?!\\s*(BEGIN|END) fleet-mesh).*?-->", RegexOption.DOT_MATCHES_ALL), "")
                val outside = generated.replace(xml, "")
                assertFalse("$rel hand-lists a .app mesh host (generate it: add the service to fleet_endpoints and list the app in fleet-nsc.json)", handApp.containsMatchIn(outside))
                // a public fleet host is never cleartext
                for (cfgEl in Regex("<domain-config cleartextTrafficPermitted=\"true\">.*?</domain-config>", RegexOption.DOT_MATCHES_ALL).findAll(xml))
                    assertFalse("$rel permits cleartext to a public fleet host", publicHost.containsMatchIn(cfgEl.value))
            }
        }
    }

    @Test fun `every declared mesh endpoint is plain http on the mesh and every public URL is https`() {
        val root = repoRoot()
        val services = org.json.JSONObject(File(root, "ac_cloud-account/build.json").readText()).getJSONObject("fleet_endpoints").getJSONArray("services")
        for (i in 0 until services.length()) {
            val s = services.getJSONObject(i); val id = s.getString("id")
            assertTrue("$id: mesh names are .app names", s.getString("mesh_dns").endsWith(".app"))
            assertEquals("$id: the mesh is WireGuard-only and WG encrypts, so the mesh endpoint is plain http", "http", s.optString("mesh_scheme", "https"))
            assertEquals("$id: a public URL is https", "https", s.optString("public_scheme", "https"))
            assertTrue("$id: a mesh port is declared", s.optInt("mesh_port", 0) > 0)
        }
    }

    @Test fun `the mail service reaches its mesh endpoint over http - the request is rewritten onto it`() {
        val root = repoRoot()
        val decl = org.json.JSONObject(File(root, "ac_cloud-account/build.json").readText()).getJSONObject("fleet_endpoints").toString()
        val jmap = FleetEndpointsContract.parseDecl(decl).single { it.id == "jmap" }
        assertEquals("http://stalwart.mesh.diegonmarcos.com:${jmap.meshPort}", jmap.meshUrl())
        val router = EndpointRouter(probe = { _, _, _ -> true })
        assertTrue(router.set(jmap.publicUrl(), jmap.meshUrl(), EndpointPolicy.parse(jmap.policy)))
        val url = "${jmap.publicUrl()}/jmap/session".toHttpUrl()
        val (h, routes) = router.plan(url)!!
        assertEquals(listOf(EndpointRoute.MESH, EndpointRoute.PUBLIC), routes)
        assertEquals("the mesh route lands on the cleartext mesh origin", "http://stalwart.mesh.diegonmarcos.com:${jmap.meshPort}/jmap/session", router.rebase(h, EndpointRoute.MESH, url).toString())
        assertEquals("the public route stays https", "${jmap.publicUrl()}/jmap/session", router.rebase(h, EndpointRoute.PUBLIC, url).toString())
    }

    @Test fun `every fleet URL read from build config is classified, and the classification may only shrink`() {
        val root = repoRoot()
        val listed = File(root, "ab_cloud-libs-shared/libs/fleet-endpoints/fleet-url-baseline.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        listed.forEach { assertTrue("a baseline line is <file> TAB <path> TAB <class> TAB <reason>: $it", it.size == 4 && it[2] in setOf("link", "routed", "pending") && it[3].length >= 10) }
        val have = listed.map { it[0] to it[1] }.toSet()
        val found = mutableSetOf<Pair<String, String>>()
        val files = root.listFiles().orEmpty().filter { it.isDirectory && (it.name.startsWith("ac_") || it.name.startsWith("aa_")) }.map { File(it, "build.json") } +
            File(root, "ab_cloud-libs-shared/build.json")
        for (f in files.filter { it.isFile }) FleetHostScan.configUrls(org.json.JSONObject(f.readText())).forEach { found += f.relativeTo(root).invariantSeparatorsPath to it }
        assertTrue("these build.json values name a fleet host and are not classified in fleet-url-baseline.txt (route their consumer through libs:fleet-endpoints, or classify them): ${found - have}", (found - have).isEmpty())
        assertTrue("these baseline lines no longer match a build.json value: remove them: ${have - found}", (have - found).isEmpty())
        assertTrue("the scan saw the apps' build config", found.size >= 20)
    }

    @Test fun `the build config scan reads URL values, not prose, and skips the declaration itself`() {
        val j = org.json.JSONObject("""{"_doc":"see auth.diegonmarcos.com","description":"auth.diegonmarcos.com","fleet_endpoints":{"services":[{"public_domain":"git.diegonmarcos.com"}]},
            "ui":{"pages":[{"url":"https://git.diegonmarcos.com/x"},{"url":"https://example.org"}],"title":"git.diegonmarcos.com"},"mesh":"http://gitea.mesh.diegonmarcos.com:3002/x"}""")
        assertEquals(setOf("ui.pages.*.url", "mesh"), FleetHostScan.configUrls(j))
    }

    @Test fun `the scan flags a fleet host in code and ignores comments, emails and package names`() {
        val s = FleetHostScan.analyse("val u = \"https://git.diegonmarcos.com/api\"")
        assertEquals(listOf("git.diegonmarcos.com"), s.hosts); assertFalse(s.usesTransport)
        assertEquals(listOf("http://git-proxy-api.mesh.diegonmarcos.com"), FleetHostScan.analyse("const val B = \"http://git-proxy-api.mesh.diegonmarcos.com:8123\"").hosts)
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

    private val host = Regex("""[A-Za-z0-9-]+\.diegonmarcos\.com\b|https?://[A-Za-z0-9-]+\.mesh\.diegonmarcos\.com\b""")
    private val uses = Regex("""fleetendpoints|FleetHttp|AuthHttp|FleetTransport|FleetHttpSeam""")

    private val proseKeys = setOf("comment", "description", "subtitle", "title", "label", "website", "source")

    /** Paths (array index = `*`) of the string values of a build.json that name a fleet host; prose keys, `_doc*` and the declaration are skipped. */
    fun configUrls(o: org.json.JSONObject): Set<String> {
        val out = mutableSetOf<String>()
        fun walk(v: Any?, path: List<String>) {
            when (v) {
                is org.json.JSONObject -> v.keys().forEach { k ->
                    if (k.startsWith("_doc") || k in proseKeys || (path.isEmpty() && k == "fleet_endpoints")) return@forEach
                    walk(v.get(k), path + k)
                }
                is org.json.JSONArray -> for (i in 0 until v.length()) walk(v.get(i), path + "*")
                is String -> if (host.containsMatchIn(v)) out += path.joinToString(".")
            }
        }
        walk(o, emptyList())
        return out
    }

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
