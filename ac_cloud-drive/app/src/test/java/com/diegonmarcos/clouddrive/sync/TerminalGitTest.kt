package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #642 the cloud-terminal git handoff, EXECUTED — every outcome branch, plus the intent's
 * declared extras, against THIS repository's own build.json and the fleet manifest the build
 * resolves the terminal's package from.
 *
 * THE RISK THIS COVERS is a handoff that looks like it worked: a wrong action, a package that
 * is not there, or a refusal that got swallowed all end as "nothing happened" on screen unless
 * each one is a distinct, named outcome. So every branch of [TerminalGit.decide] is asserted to
 * be REACHABLE and DISTINCT, and the mutations at the bottom prove the two that a typo would
 * cause are actually detected rather than reported as success.
 *
 * NO LITERALS: the package is read out of the constellation fleet manifest by the fleet id the
 * declaration names, which is exactly what app/build.gradle does (and hard-fails on). If that
 * id ever stops resolving, this test fails in the same breath as the build would.
 */
class TerminalGitTest {

    private val root = File(System.getProperty("user.dir")).let {
        if (File(it, "build.json").isFile) it else it.parentFile
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val buildJson: JsonObject =
        json.parseToJsonElement(File(root, "build.json").readText()).jsonObject
    private val gitDecl = buildJson["ui"]!!.jsonObject["sync"]!!.jsonObject["git"]!!.jsonObject

    /** The package app/build.gradle would substitute: the fleet manifest's entry for the
     *  DECLARED fleet id. Resolving it here is also the assertion that it resolves at all. */
    private val terminalPackage: String by lazy {
        val fleetId = gitDecl["terminal"]!!.jsonObject["fleet"]!!.jsonPrimitive.content
        val manifest = File(root, "../aa_cloud-superapp/data/constellation-fleet.json")
        assertTrue("fleet manifest not found at $manifest", manifest.isFile)
        val entry = json.parseToJsonElement(manifest.readText()).jsonObject["apps"]!!.jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["id"]?.jsonPrimitive?.content == fleetId }
        assertNotNull(
            "build.json::ui.sync.git.terminal.fleet '$fleetId' is not in the constellation " +
                "fleet manifest — the build would hard-fail and the handoff would aim at nothing",
            entry,
        )
        entry!!["package"]!!.jsonPrimitive.content
    }

    /** The declaration as the PHONE sees it: every {package} substituted, the way gradle does. */
    private val page: Declarations.GitPageDecl by lazy {
        val baked = gitDecl.toString().replace("{package}", terminalPackage)
        Declarations.parseGitPage(json.parseToJsonElement(baked))
    }

    // A temp path, NOT a device path: #603's rule is that the shared store's absolute location
    // is written down nowhere but SharedStore (test-drive-shared-store.sh enforces it over
    // src/ including this suite). Nothing here depends on where dest is, only on its parent.
    private val dest = File(System.getProperty("java.io.tmpdir"), "cloud-drive-git/cloud-u-android")
    private val url = "https://github.com/diegonmarcos/cloud-u-android.git"

    @Test
    fun `the declaration resolves to the terminal, with no placeholder left`() {
        val t = page.terminal
        assertNotNull("ui.sync.git.terminal is not declared — git would fall back to JGit", t)
        assertTrue("terminal block is incomplete: $t", t!!.declared)
        assertEquals(terminalPackage, t.pkg)
        // Derived from the package, not retyped: this is what proves the substitution ran.
        assertEquals("$terminalPackage.RUN_COMMAND", t.action)
        assertEquals("$terminalPackage.permission.RUN_COMMAND", t.permission)
        assertTrue("command must be the terminal's bin/login: ${t.command}",
            t.command.startsWith("/data/data/$terminalPackage/") && t.command.endsWith("/bin/login"))
        for (extra in listOf(t.extraPath, t.extraArguments, t.extraWorkdir, t.extraBackground)) {
            assertTrue("extra name is not the terminal's: $extra", extra.startsWith("$terminalPackage."))
        }
        assertTrue("a {package} placeholder survived into the baked declaration: $t",
            listOf(t.action, t.permission, t.command, t.extraPath, t.extraArguments,
                t.extraWorkdir, t.extraBackground).none { it.contains("{package}") })
    }

    @Test
    fun `the clone argv is a real git clone of the url into the destination`() {
        val argv = page.terminal!!.argv(Declarations.GIT_OP_CLONE, url, dest.absolutePath)
        assertNotNull("no clone op declared", argv)
        assertEquals(listOf("git", "clone", url, dest.absolutePath), argv)
    }

    @Test
    fun `a repository name that would re-split a shell string stays one argument`() {
        val nasty = File("/store/git/a b\"; rm -rf /")
        val argv = page.terminal!!.argv(Declarations.GIT_OP_CLONE, url, nasty.absolutePath)!!
        assertEquals(4, argv.size)
        assertEquals(nasty.absolutePath, argv[3])
    }

    @Test
    fun `every outcome is reachable and distinct`() {
        val t = page.terminal
        fun decide(installed: Boolean, granted: Boolean, resolves: Boolean, op: String = Declarations.GIT_OP_CLONE) =
            TerminalGit.decide(t, op, installed, granted, resolves, dest)

        assertEquals(TerminalGit.Outcome.NotDeclared,
            TerminalGit.decide(null, Declarations.GIT_OP_CLONE, true, true, true, dest))
        assertEquals(TerminalGit.Outcome.NoSuchOp("push"), decide(true, true, true, op = "push"))
        assertEquals(TerminalGit.Outcome.NotInstalled(t!!.pkg), decide(false, false, false))
        assertEquals(TerminalGit.Outcome.NeedsPermission(t.permission), decide(true, false, false))
        assertEquals(TerminalGit.Outcome.NoService(t.action), decide(true, true, false))
        assertEquals(TerminalGit.Outcome.Sent(dest), decide(true, true, true))

        // Distinct: five reasons that must never collapse into one "nothing happened".
        val all = listOf(
            TerminalGit.decide(null, Declarations.GIT_OP_CLONE, true, true, true, dest),
            decide(true, true, true, op = "push"),
            decide(false, false, false),
            decide(true, false, false),
            decide(true, true, false),
            decide(true, true, true),
        )
        assertEquals("two outcomes are indistinguishable", all.size, all.distinct().size)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `the intent extras carry login, the argv array and the parent workdir`() {
        val t = page.terminal!!
        val argv = t.argv(Declarations.GIT_OP_CLONE, url, dest.absolutePath)!!
        val extras = TerminalGit.extras(t, argv, dest).toMap()
        assertEquals(t.command, extras[t.extraPath])
        assertEquals(argv, (extras[t.extraArguments] as Array<String>).toList())
        // The workdir is the git root, not the clone itself: git clone creates that directory.
        assertEquals(dest.parentFile!!.absolutePath, extras[t.extraWorkdir])
        assertEquals(t.background, extras[t.extraBackground])
    }

    // ── MUTATIONS: the two shapes a typo takes, each proved detected ──────────

    @Test
    fun `mutation - a wrong action is reported as NoService, never as sent`() {
        val t = page.terminal!!.copy(action = t_mutated(page.terminal!!.action))
        // A wrong action resolves no service on the device; decide() must say which.
        val outcome = TerminalGit.decide(t, Declarations.GIT_OP_CLONE,
            installed = true, granted = true, resolvesService = false, dest = dest)
        assertEquals(TerminalGit.Outcome.NoService(t.action), outcome)
        assertTrue("a wrong action must not be reported as Sent", outcome !is TerminalGit.Outcome.Sent)
    }

    @Test
    fun `mutation - an emptied declaration is NotDeclared, not a silent success`() {
        val gutted = page.terminal!!.copy(ops = emptyMap())
        assertEquals(TerminalGit.Outcome.NotDeclared,
            TerminalGit.decide(gutted, Declarations.GIT_OP_CLONE, true, true, true, dest))
        val noAction = page.terminal!!.copy(action = "")
        assertEquals(TerminalGit.Outcome.NotDeclared,
            TerminalGit.decide(noAction, Declarations.GIT_OP_CLONE, true, true, true, dest))
        // And an undeclared op yields no argv at all rather than a half-built command.
        assertNull(gutted.argv(Declarations.GIT_OP_CLONE, url, dest.absolutePath))
    }

    private fun t_mutated(action: String) = action + ".WRONG"
}
