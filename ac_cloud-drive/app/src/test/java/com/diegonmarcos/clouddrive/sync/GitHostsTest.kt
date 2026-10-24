package com.diegonmarcos.clouddrive.sync

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1149 Sync > Git > Me: the hosts beyond GitHub and Cloud git, over recorded response bodies. */
class GitHostsTest {

    @Test fun eachHostGetsItsOwnAuthHeader() {
        assertEquals(mapOf("PRIVATE-TOKEN" to "t"), GitHosts.headers(GitHosts.KIND_GITLAB, "u", "t"))
        assertEquals(mapOf("Authorization" to "token t"), GitHosts.headers(GitHosts.KIND_GITEA, "u", "t"))
        assertEquals("Basic " + Base64.getEncoder().encodeToString("u:t".toByteArray()), GitHosts.headers(GitHosts.KIND_BITBUCKET, "u", "t").getValue("Authorization"))
        assertEquals("Basic " + Base64.getEncoder().encodeToString(":t".toByteArray()), GitHosts.headers(GitHosts.KIND_AZURE, "org", "t").getValue("Authorization"))
        assertEquals(emptyMap<String, String>(), GitHosts.headers("unknown", "u", "t"))
    }

    @Test fun secretsLiveUnderKeysThatCannotBeARepoId() {
        assertEquals("host:gitlab:token", GitHosts.tokenKey("gitlab"))
        assertEquals("host:gitlab:user", GitHosts.userKey("gitlab"))
        assertFalse(GitHosts.tokenKey("x").startsWith("repo-"))
    }

    @Test fun giteaParsesLikeTheTableExpects() {
        val r = GitHosts.parse(GitHosts.KIND_GITEA, """[{"name":"dots","owner":{"login":"me"},"private":true,"fork":false,"default_branch":"main","size":42,"clone_url":"https://codeberg.org/me/dots.git","ssh_url":"git@codeberg.org:me/dots.git","html_url":"https://codeberg.org/me/dots"}]""")
        assertEquals(1, r.size)
        assertEquals("dots", r[0].name); assertEquals("me", r[0].owner); assertTrue(r[0].private); assertEquals(42L, r[0].sizeKb)
        assertEquals("https://codeberg.org/me/dots.git", r[0].cloneUrl)
    }

    @Test fun gitlabParsesVisibilityAndUrls() {
        val r = GitHosts.parse(GitHosts.KIND_GITLAB, """[{"path":"app","name":"App","visibility":"private","default_branch":"main","http_url_to_repo":"https://gitlab.com/g/app.git","ssh_url_to_repo":"git@gitlab.com:g/app.git","web_url":"https://gitlab.com/g/app","namespace":{"full_path":"g"},"star_count":3},{"path":"pub","visibility":"public","http_url_to_repo":"https://gitlab.com/g/pub.git","namespace":{"full_path":"g"}}]""")
        assertEquals(listOf("app", "pub"), r.map { it.name })
        assertEquals(listOf(true, false), r.map { it.private })
        assertEquals("g", r[0].owner); assertEquals(3L, r[0].stars); assertEquals("https://gitlab.com/g/app.git", r[0].cloneUrl)
    }

    @Test fun bitbucketTakesTheHttpsAndSshCloneLinks() {
        val r = GitHosts.parse(GitHosts.KIND_BITBUCKET, """{"values":[{"slug":"site","name":"Site","is_private":true,"workspace":{"slug":"acme"},"mainbranch":{"name":"master"},"size":2048,"links":{"clone":[{"name":"https","href":"https://bitbucket.org/acme/site.git"},{"name":"ssh","href":"git@bitbucket.org:acme/site.git"}],"html":{"href":"https://bitbucket.org/acme/site"}}}],"next":"x"}""")
        assertEquals(1, r.size)
        assertEquals("site", r[0].name); assertEquals("acme", r[0].owner); assertTrue(r[0].private); assertEquals("master", r[0].defaultBranch); assertEquals(2L, r[0].sizeKb)
        assertEquals("https://bitbucket.org/acme/site.git", r[0].cloneUrl); assertEquals("git@bitbucket.org:acme/site.git", r[0].sshUrl)
    }

    @Test fun azureTakesTheProjectAsOwnerAndStripsTheRefPrefix() {
        val r = GitHosts.parse(GitHosts.KIND_AZURE, """{"count":1,"value":[{"name":"svc","defaultBranch":"refs/heads/main","remoteUrl":"https://acme@dev.azure.com/acme/Proj/_git/svc","sshUrl":"git@ssh.dev.azure.com:v3/acme/Proj/svc","webUrl":"https://dev.azure.com/acme/Proj/_git/svc","project":{"name":"Proj","visibility":"private"}}]}""")
        assertEquals("svc", r.single().name); assertEquals("Proj", r.single().owner); assertEquals("main", r.single().defaultBranch); assertTrue(r.single().private)
    }

    @Test fun garbageAndErrorBodiesAreNoRepositoriesForEveryKind() {
        GitHosts.KINDS.forEach { k ->
            assertEquals(k, emptyList<GitHubRepos.Repo>(), GitHosts.parse(k, "not json"))
            assertEquals(k, emptyList<GitHubRepos.Repo>(), GitHosts.parse(k, """{"message":"401 Unauthorized"}"""))
        }
        assertEquals(emptyList<GitHubRepos.Repo>(), GitHosts.parse("nope", "[]"))
    }

    @Test fun fetchWithoutATokenOrAUserFailsBeforeAnyRequest() {
        val d = GitHostsDecl.GitHostDecl("bb", "Bitbucket", "cloud", "bitbucket", "https://api.bitbucket.org/2.0", "/repositories", "", true, "Username", "", 1)
        assertTrue(GitHosts.fetch(d, "u", "").isFailure)
        assertTrue(GitHosts.fetch(d, "", "tok").isFailure)
    }

    @Test fun theCloneUserIsTheHostsFixedOneElseTheSignInName() {
        val fixed = GitHostsDecl.GitHostDecl("gl", "GitLab", "code", "gitlab", "https://gitlab.com/api/v4", "/projects", "", false, "", "oauth2", 1)
        assertEquals("oauth2", GitHosts.cloneUser(fixed, "me"))
        assertEquals("me", GitHosts.cloneUser(fixed.copy(cloneUser = ""), "me"))
    }
}
