package com.diegonmarcos.superapp.launcher

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1331 - LnktreeTargets turns the declared source of a Lnktree page entry into a URL, and only that: it is handed a
 * declaration of the test's own making (never the shipped build.json) and returns null for anything it cannot resolve,
 * which is what makes the renderer grey the entry. Robolectric because the parse is org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LnktreeTargetsTest {
    private val linktree = """{"slides":[{"columns":[{"links":[
        {"label":"Fin Terminal","url":"https://t.example.org/fin"},
        {"label":"Twice","url":"https://t.example.org/a"},{"label":"Twice","url":"https://t.example.org/b"}]}]}]}"""
    private val cfg = """{"repo_base":"https://git.example.org/me/","registry_sites":{"1.2.3":"https://r.example.org/app/"}}"""

    private fun r(site: String = "", registry: String = "", repo: String = "", path: String = "") =
        LnktreeTargets.resolve(site, registry, repo, path, linktree, cfg)

    @Test fun `a site label resolves to the linktree link`() = assertEquals("https://t.example.org/fin", r(site = "Fin Terminal"))

    @Test fun `a label with two different links, or none, resolves to nothing`() {
        assertNull(r(site = "Twice"))
        assertNull(r(site = "Nope"))
    }

    @Test fun `a registry code resolves through registry_sites and an unknown one to nothing`() {
        assertEquals("https://r.example.org/app/", r(registry = "1.2.3"))
        assertNull(r(registry = "9.9.9"))
    }

    @Test fun `a repo resolves under repo_base, with its path as a tree folder`() {
        assertEquals("https://git.example.org/me/Repo", r(repo = "Repo"))
        assertEquals("https://git.example.org/me/Repo/tree/main/A/B", r(repo = "Repo", path = "A/B"))
    }

    @Test fun `an entry that names no source resolves to nothing`() = assertNull(r())

    @Test fun `a repo with no repo_base declared resolves to nothing`() =
        assertNull(LnktreeTargets.resolve("", "", "Repo", "", linktree, "{}"))
}
