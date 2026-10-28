package com.diegonmarcos.clouddrive

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1073 cloud-drive reads ONE repo list: git-repos-master/repos.json, vendored as data/repos.sot.json
 * (build.json storage.repos_source). Held here, by the parser the phone runs: the copy is the pinned one,
 * every Sync > Git repository is listed by the registry and created, visibility agrees, and a
 * pending_creation repository (cloud-webapps, cloud-me-containers) is never offered for clone or seed.
 * (The libs:repos copy lives outside this module's CI checkout; test/test-drive-repos-sot.sh R1 holds it byte-identical.)
 */
@RunWith(RobolectricTestRunner::class)
class RepoSotTest {

    private val data = listOf(File("data"), File("../data"), File("ac_cloud-drive/data")).first { File(it, "repos.sot.json").isFile }.canonicalFile
    private val json = Json { ignoreUnknownKeys = true }
    private val sotText = File(data, "repos.sot.json").readText()
    private val sot: JsonObject = json.parseToJsonElement(sotText).jsonObject

    /** The slim shape app/build.gradle bakes as REPOS_SOT_B64, rebuilt from the copy the same way. */
    private fun baked(): String = JsonArray(sot["repos"]!!.jsonArray.map { e ->
        val r = e.jsonObject
        val github = r["url"]!!.jsonPrimitive.content.substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
        JsonObject(mapOf(
            "name" to r["name"]!!, "github" to JsonPrimitive(github),
            "private" to JsonPrimitive(r["private"]?.jsonPrimitive?.boolean == true),
            "pending" to JsonPrimitive(r["status"]?.jsonPrimitive?.content == "pending_creation"),
            "renamed_from" to (r["renamed_from"] ?: JsonArray(emptyList()))))
    }).toString()

    private val decl = Declarations.parseRepoSot(baked())
    private val family = Declarations.parseGitFamily(File(data, "drive-git-repos.json").readText())

    @Test fun theCopyIsThePinnedOne() {
        val build = json.parseToJsonElement(File(data.parentFile, "build.json").readText()).jsonObject["storage"]!!.jsonObject["repos_source"]!!.jsonObject
        val sha = MessageDigest.getInstance("SHA-256").digest(File(data, "repos.sot.json").readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("repos.sot.json drifted from build.json::storage.repos_source.sha256", build["sha256"]!!.jsonPrimitive.content, sha)
        assertEquals("cloud-repos/v1", sot["_schema"]!!.jsonPrimitive.content)
        assertEquals("data/repos.sot.json", build["path"]!!.jsonPrimitive.content)
        assertTrue("url must name git-repos-master", "/git-repos-master/" in build["url"]!!.jsonPrimitive.content)
        assertTrue("url must name the registry file", build["url"]!!.jsonPrimitive.content.endsWith("/repos.json"))
    }

    @Test fun everyFamilyRepositoryIsListedAndCreated() {
        assertTrue(family.repos.isNotEmpty())
        for (r in family.repos) {
            val e = decl.byGithub(r.name)
            assertTrue("${r.name} is in drive-git-repos.json but not in the registry", e != null)
            assertFalse("${r.name} is pending_creation in the registry", e!!.pending)
            assertEquals("${r.name} visibility must be the registry's", e.private, r.private)
        }
        assertEquals("a created, listed family loses nothing", family.repos.map { it.name }, Declarations.restrictToRegistry(family, decl).repos.map { it.name })
    }

    @Test fun pendingRepositoriesAreDeclaredAndNeverCloneable() {
        // cloud-webapps and cloud-me-containers were pending_creation when #1073 landed and the registry has since
        // created them (2026-10-21): the real registry now lists both as created and cloneable...
        for (n in listOf("cloud-webapps", "cloud-me-containers")) {
            val e = decl.byGithub(n)
            assertTrue("$n must be declared in the registry", e != null)
            assertFalse("$n is created in the registry", e!!.pending)
            assertTrue(decl.cloneable(n))
        }
        // ...so the pending rule is held on the registry as it was, rebuilt by the same parser: the real entries plus the two as pending.
        fun pending(n: String) = JsonObject(mapOf("name" to JsonPrimitive(n), "github" to JsonPrimitive(n), "private" to JsonPrimitive(false), "pending" to JsonPrimitive(true), "renamed_from" to JsonArray(emptyList())))
        val registry = Declarations.parseRepoSot(JsonArray(json.parseToJsonElement(baked()).jsonArray.filter { e -> e.jsonObject["name"]!!.jsonPrimitive.content !in setOf("cloud-webapps", "cloud-me-containers") } + listOf(pending("cloud-webapps"), pending("cloud-me-containers"))).toString())
        for (n in listOf("cloud-webapps", "cloud-me-containers")) {
            val e = registry.byGithub(n)
            assertTrue("$n must be declared in the registry", e != null)
            assertTrue("$n is pending", e!!.pending)
            assertFalse(registry.cloneable(n))
        }
        val withPending = Declarations.GitFamilyDecl(family.instances, family.repos + family.repos.first().copy(name = "cloud-webapps", seed = true, private = false))
        assertEquals("a pending repository is dropped from the family", family.repos.map { it.name }, Declarations.restrictToRegistry(withPending, registry).repos.map { it.name })
        val unknown = Declarations.GitFamilyDecl(family.instances, family.repos + family.repos.first().copy(name = "not-in-the-registry"))
        assertFalse("an unlisted repository is dropped", Declarations.restrictToRegistry(unknown, decl).repos.any { it.name == "not-in-the-registry" })
    }

    @Test fun renamesComeFromTheRegistry() {
        val front = decl.byGithub("Projects")!!
        assertTrue("ffront -> Projects (#731, front renamed Projects #1224) is the registry's rename", "ffront" in front.renamedFrom)
        assertTrue("the drive family carries the same device-side rename", family.renames["ffront"] == "Projects")
        assertEquals("front-diegonmarcos", family.repos.first { it.name == "diegonmarcos.github.io" }.renamedFrom.single())
        assertTrue("cloud-mobile is not a repository", sot["repos"]!!.jsonArray.none { it.jsonObject["name"]!!.jsonPrimitive.content == "cloud-mobile" })
        assertTrue(sot["renames"]!!.jsonArray.any { val o = it.jsonObject; o["from"]!!.jsonPrimitive.content == "cloud-mobile" && o["to"]!!.jsonPrimitive.content == "cloud-superapp" })
    }

    @Test fun blankAndBrokenRegistryParseToEmptyAndListNothing() {
        assertTrue(Declarations.parseRepoSot("").repos.isEmpty())
        assertTrue(Declarations.parseRepoSot("{not json").repos.isEmpty())
        assertTrue("no registry = nothing is cloneable (fail closed)", Declarations.restrictToRegistry(family, Declarations.parseRepoSot("")).repos.isEmpty())
    }
}
