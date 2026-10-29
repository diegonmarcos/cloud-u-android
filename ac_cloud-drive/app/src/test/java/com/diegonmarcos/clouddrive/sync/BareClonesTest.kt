package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1282 Clone bare: path, one bare per repository (origin URL + renamed_from), update instead of a second clone. */
class BareClonesTest {
    private val renames = mapOf("cloud-infra" to "cloudy-t-infra", "git-repos-master" to "_git-repos-master")
    private val canonical: (String) -> String = { renames[it] ?: it }
    private val q: (String) -> String = { "'$it'" }
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }

    @Test fun `the mirror path is root, owner, canonical repo dot git`() {
        assertEquals("/s/git/_bare/diegonmarcos/cloudy-t-infra.git", BareClones.path("/s/git/", "_bare", "https://github.com/diegonmarcos/cloud-infra.git", "cloud-infra", canonical))
        assertEquals("/s/git/_bare/diegonmarcos/_git-repos-master.git", BareClones.path("/s/git", "_bare", "git@github.com:diegonmarcos/_git-repos-master.git", "", canonical))
        assertNull("an unsafe root is refused", BareClones.path("/s/git", "../x", "https://github.com/o/r.git", "r", canonical))
        assertFalse(BareClones.validRoot("/abs")); assertTrue(BareClones.validRoot("_bare/mirrors"))
        assertEquals("o", BareClones.ownerOf("ssh://git@host:22/o/r.git"))
    }

    @Test fun `one bare per repository - an existing mirror under an old name is updated, duplicates collapse`() {
        val mirrors = listOf(BareClones.Mirror("/g/_bare/diegonmarcos/cloud-infra.git", "https://github.com/diegonmarcos/cloud-infra.git"))
        val targets = listOf(
            BareClones.Target("cloudy-t-infra", "https://github.com/diegonmarcos/cloudy-t-infra.git"),
            BareClones.Target("cloud-me", "https://github.com/diegonmarcos/cloud-me.git"),
            BareClones.Target("cloud-me", "git@github.com:diegonmarcos/cloud-me.git"),
        )
        val p = BareClones.plan(targets, mirrors, canonical)
        assertEquals(mapOf("cloudy-t-infra" to "/g/_bare/diegonmarcos/cloud-infra.git"), p.toUpdate)
        assertEquals(listOf("cloud-me"), p.toClone.map { it.name })
        assertEquals(mapOf("cloudy-t-infra" to "/g/_bare/diegonmarcos/cloud-infra.git"), BareClones.index(mirrors, canonical))
    }

    @Test fun `origin is read from a bare config and the commands never touch a working clone`() {
        val cfg = "[core]\n\tbare = true\n[remote \"upstream\"]\n\turl = x\n[remote \"origin\"]\n\turl = https://github.com/o/r.git\n\tfetch = +refs/*:refs/*\n\tmirror = true\n"
        assertEquals("https://github.com/o/r.git", BareClones.originOfConfig(cfg))
        assertEquals("clone --mirror 'u' '/g/_bare/o/r.git'", BareClones.cloneArgs("u", "/g/_bare/o/r.git", 0, q))
        assertEquals("clone --mirror --depth 1 'u' 'd'", BareClones.cloneArgs("u", "d", 1, q))
        assertEquals("-C 'd' remote update --prune", BareClones.updateArgs("d", q))
    }

    @Test fun `clone_bare is declared in both scopes with full history and its own bare root`() {
        val git = Declarations.parseGitPage(Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"])
        val bare = git.depthActions.filter { it.kind == Declarations.DEPTH_CLONE_BARE }
        assertEquals(setOf(Declarations.DEPTH_SCOPE_ALL, Declarations.DEPTH_SCOPE_REPO), bare.map { it.scope }.toSet())
        assertTrue(bare.all { it.depth == 0 })
        assertTrue(BareClones.validRoot(git.bareRoot))
    }
}
