package com.diegonmarcos.superapp.profile

import android.app.Application
import com.diegonmarcos.superapp.profile.AccountUpload.Result
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #778 — Upload L → server against a MOCK GIT REMOTE that keeps GitHub's contents-API rules: a
 * file has a blob sha, an update must name the sha it replaces (else 409), a new file names none,
 * a bad token is 401. (A real socket is not used: Android unit tests compile against android.jar,
 * which carries no HTTP server to stand one up with.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountUploadTest {

    private val target = AccountUpload.Target("https://remote.test/repos/{repo}/contents/{path}", "owner/vault", "C_A1-configs/profile-local.json", "main")
    private val token = "test-credential-123"

    /** The remote: files by path, each (content, sha); every request recorded. */
    private class MockRemote(private val token: String, var staleShaOnGet: Boolean = false) : AccountUpload.Http {
        val files = HashMap<String, Pair<String, String>>()
        val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
        private var n = 0
        override fun call(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
            requests += Triple(method, url, headers)
            if (headers["Authorization"] != "Bearer $token") return 401 to """{"message":"Bad credentials"}"""
            val path = url.substringAfter("/contents/").substringBefore('?')
            val cur = files[path]
            return when (method) {
                "GET" -> if (cur == null) 404 to """{"message":"Not Found"}"""
                         else 200 to JSONObject().put("sha", if (staleShaOnGet) "stale" else cur.second).toString()
                "PUT" -> {
                    val o = JSONObject(body!!)
                    val sha = o.optString("sha").ifBlank { null }
                    if (cur != null && sha != cur.second) return 409 to """{"message":"sha does not match"}"""
                    if (cur == null && sha != null) return 422 to """{"message":"sha given for a new file"}"""
                    val text = String(java.util.Base64.getDecoder().decode(o.getString("content")))
                    files[path] = text to "blob${++n}"
                    val code = if (cur == null) 201 else 200
                    code to JSONObject().put("commit", JSONObject().put("sha", "c0ffee$n").put("html_url", "https://remote.test/commit/c0ffee$n")).toString()
                }
                else -> 405 to "{}"
            }
        }
    }

    @Test fun `a new file is created with no sha, and the commit is reported`() {
        val remote = MockRemote(token)
        val r = AccountUpload.commit(target, token, "{\"a\":1}".toByteArray(), "profile-local: test", remote)
        assertEquals(Result.Committed("c0ffee1", "https://remote.test/commit/c0ffee1", created = true), r)
        assertEquals("{\"a\":1}", remote.files[target.path]!!.first)
        assertEquals(listOf("GET", "PUT"), remote.requests.map { it.first })
        assertTrue("the GET reads the declared branch", remote.requests[0].second.endsWith("?ref=main"))
    }

    @Test fun `an update names the sha it replaces`() {
        val remote = MockRemote(token)
        remote.files[target.path] = "old" to "blob-old"
        val r = AccountUpload.commit(target, token, "new".toByteArray(), "m", remote)
        assertTrue(r is Result.Committed && !r.created)
        assertEquals("new", remote.files[target.path]!!.first)
    }

    @Test fun `a file that moved on since the read is refused, not overwritten`() {
        val remote = MockRemote(token, staleShaOnGet = true)
        remote.files[target.path] = "theirs" to "blob-theirs"
        val r = AccountUpload.commit(target, token, "mine".toByteArray(), "m", remote)
        assertTrue(r is Result.Failed && r.status == 409 && "changed on the server" in r.reason)
        assertEquals("theirs", remote.files[target.path]!!.first)
    }

    @Test fun `a refused credential says so and the token appears in no URL and no result`() {
        val remote = MockRemote("another-credential")
        val r = AccountUpload.commit(target, token, "x".toByteArray(), "m", remote)
        assertTrue(r is Result.Failed && r.status == 401 && "refused" in r.reason)
        assertFalse(token in r.toString())
        assertTrue(remote.requests.none { token in it.second })
        assertEquals(listOf("GET"), remote.requests.map { it.first })
    }

    @Test fun `no credential, no request`() {
        val remote = MockRemote(token)
        assertTrue(AccountUpload.commit(target, "", "x".toByteArray(), "m", remote) is Result.Failed)
        assertTrue(remote.requests.isEmpty())
    }
}
