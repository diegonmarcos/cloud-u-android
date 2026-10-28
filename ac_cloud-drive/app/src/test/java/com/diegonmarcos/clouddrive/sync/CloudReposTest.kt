package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.sync.GitHostsDecl.CloudListDecl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1149 Sync > Git > Cloud: the public cloud* list's filter, over a recorded GitHub REST body. */
class CloudReposTest {

    private fun item(name: String, private: Boolean = false, fork: Boolean = false) =
        """{"name":"$name","full_name":"diegonmarcos/$name","private":$private,"fork":$fork,"owner":{"login":"diegonmarcos"},"default_branch":"main","clone_url":"https://github.com/diegonmarcos/$name.git","html_url":"https://github.com/diegonmarcos/$name"}"""

    private val body = "[" + listOf(
        item("front-data"), item("cloud-u-android"), item("cloud-data", private = true), item("Cloud-Notes"), item("cloud"),
        item("_git-repos-master"), item("my-cloud"), item("cloud-me-my-ai", private = true), item("cloud-t-infra", fork = true),
    ).joinToString(",") + "]"

    @Test fun keepsPublicReposWhoseNameStartsWithTheDeclaredPrefix() {
        val out = CloudRepos.filter(GitHubRepos.parse(body), "cloud")
        assertEquals(listOf("cloud", "cloud-t-infra", "cloud-u-android", "Cloud-Notes").sortedBy { it.lowercase() }, out.map { it.name })
        assertTrue("no private repository ever", out.none { it.private })
    }

    @Test fun aPrivateRepoIsDroppedEvenWhenTheNameMatches() {
        val out = CloudRepos.filter(GitHubRepos.parse(body), "cloud")
        assertTrue("cloud-data" !in out.map { it.name } && "cloud-me-my-ai" !in out.map { it.name })
    }

    @Test fun theNameMustSTARTWithThePrefixNotContainIt() {
        val out = CloudRepos.filter(GitHubRepos.parse(body), "cloud").map { it.name }
        assertTrue("my-cloud" !in out && "front-data" !in out && "_git-repos-master" !in out)
    }

    @Test fun aBlankPrefixListsNothingRatherThanEverything() {
        assertEquals(emptyList<String>(), CloudRepos.filter(GitHubRepos.parse(body), "").map { it.name })
    }

    @Test fun anErrorBodyIsNoRepositories() {
        assertEquals(emptyList<String>(), CloudRepos.filter(GitHubRepos.parse("""{"message":"API rate limit exceeded"}"""), "cloud").map { it.name })
    }

    @Test fun theUrlIsTheDeclaredRouteWithNoCredentialInIt() {
        val d = CloudListDecl("diegonmarcos", "cloud", "/users/{owner}/repos?type=owner&per_page=100", 4)
        assertEquals("https://api.github.com/users/diegonmarcos/repos?type=owner&per_page=100&page=3", d.url("https://api.github.com", 3))
    }
}
