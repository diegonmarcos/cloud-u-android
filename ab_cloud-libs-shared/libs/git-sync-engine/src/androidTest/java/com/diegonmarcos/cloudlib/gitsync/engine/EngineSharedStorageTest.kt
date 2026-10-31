package com.diegonmarcos.cloudlib.gitsync.engine

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #1358 THE ON-DEVICE PROOF of the git engine's All files access. The workflow grants it exactly as the owner's phone
 * gets it (`adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`), then this runs the engine's OWN calls ([GitCalls],
 * the JSON the service answers) on SHARED STORAGE: clone a public repository, commit, push to a local bare remote, pull
 * that commit into a second clone. Every verb must answer ok. The same calls without the grant answer the sentence the
 * Cloud Drive banner is about.
 */
@RunWith(AndroidJUnit4::class)
class EngineSharedStorageTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val calls = GitCalls(File(ctx.cacheDir, "git-scratch"), "test") { Environment.isExternalStorageManager() }
    private val root = File(Environment.getExternalStorageDirectory(), "engine-grant-proof/" + System.currentTimeMillis())

    private fun git(dir: File, verb: String, args: JSONObject = JSONObject()): JSONObject {
        val a = JSONObject(calls.git(JSONObject().put("dir", dir.absolutePath).put("verb", verb).put("args", args)))
        assertTrue("$verb in $dir: ${a.optString("error")}", a.getBoolean("ok"))
        return a
    }

    private fun op(dir: File, verb: String, args: JSONObject = JSONObject()): JSONObject {
        val r = JSONObject(git(dir, verb, args).getString("result"))
        assertTrue("$verb in $dir: ${r.optString("summary")} ${r.optString("details")}", r.getBoolean("ok"))
        return r
    }

    @Test fun cloneCommitPushPullOnSharedStorageWithTheGrant() {
        assertTrue("the appops grant did not take: isExternalStorageManager() is false", Environment.isExternalStorageManager())
        assertTrue(JSONObject(calls.info()).getBoolean("files_access"))
        assertTrue("cannot create $root", root.mkdirs() || root.isDirectory)

        // 1. clone a public repository into shared storage
        val a = File(root, "a")
        git(a, "clone", JSONObject().put("url", "https://github.com/octocat/Hello-World.git").put("depth", 0))
        assertTrue(File(a, "README").isFile)

        // 2. a local bare remote in shared storage, seeded by a push from the clone
        val remote = File(root, "remote.git")
        Git.init().setBare(true).setDirectory(remote).call().close()
        git(a, "add_remote", JSONObject().put("name", "local").put("url", remote.toURI().toString()))
        op(a, "push", JSONObject().put("remote", "local"))

        // 3. a second clone of that remote
        val b = File(root, "b")
        git(b, "clone", JSONObject().put("url", remote.toURI().toString()))

        // 4. commit in the first clone and push it
        File(a, "proof-1358.txt").writeText("written by the git sync engine on shared storage\n")
        git(a, "stage", JSONObject().put("paths", org.json.JSONArray().put("proof-1358.txt")))
        git(a, "commit", JSONObject().put("message", "#1358 engine proof").put("name", "Engine Proof").put("email", "proof@example.invalid"))
        val pushed = op(a, "push", JSONObject().put("remote", "local"))
        assertTrue(pushed.getString("summary"), pushed.getString("summary").startsWith("pushed"))

        // 5. pull it into the second clone
        op(b, "pull", JSONObject().put("remote", "origin").put("rebase", false))
        assertEquals("written by the git sync engine on shared storage\n", File(b, "proof-1358.txt").readText())
        root.deleteRecursively()
    }

    @Test fun withoutTheGrantEveryVerbOnSharedStorageSaysSo() {
        val denied = GitCalls(File(ctx.cacheDir, "git-scratch"), "test") { false }
        val a = JSONObject(denied.git(JSONObject().put("dir", File(root, "x").absolutePath).put("verb", "status").put("args", JSONObject())))
        assertFalse(a.getBoolean("ok"))
        assertTrue(a.getString("error"), a.getString("error").contains("All files access"))
    }
}
