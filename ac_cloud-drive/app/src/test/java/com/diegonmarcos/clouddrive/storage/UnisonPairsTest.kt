package com.diegonmarcos.clouddrive.storage

import com.diegonmarcos.clouddrive.UnisonSyncWorker
import com.diegonmarcos.clouddrive.storage.UnisonPairs.Draft
import com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig
import com.diegonmarcos.cloudlib.rclone.unison.UnisonProfile
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folders Sync runs on pairs the USER made: the shipped declaration carries Unison's policy and no pair, a pair is added
 * through the engine's own parser, and what is saved holds no credential. (This replaces the old test that asserted the
 * single sample pair the APK used to ship.)
 */
class UnisonPairsTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val policy = JSONObject(File(root, "data/drive-sync-rules.json").readText()).getJSONObject("unison").toString()
    private val storage = "/sdcard"
    private val ssh = Draft("Docs to box", "Documents/Sync", host = "box.mesh.diegonmarcos.com", user = "me", remotePath = "sync/docs", scheduleMinutes = 60)

    @Test fun `the shipped declaration is valid policy and ships no pair`() {
        val d = UnisonConfig.parse(policy, storage)
        assertEquals(emptyList<String>(), d.problems)
        assertEquals("a pair in the APK would be sample data", emptyList<String>(), d.pairs.map { it.id })
        assertEquals(emptyList<String>(), UnisonPairs.parse(policy, "", storage).pairs.map { it.id })
    }

    @Test fun `an added ssh pair is validated by the engine parser and inherits the shipped policy`() {
        val saved = UnisonPairs.add("", ssh, policy, storage).getOrThrow()
        val pair = UnisonPairs.parse(policy, saved, storage).pairs.single()
        assertEquals("docs-to-box", pair.id); assertEquals("/sdcard/Documents/Sync", pair.rootA); assertEquals(60, pair.scheduleMinutes)
        assertEquals("no prefer: every conflict waits for the user", null, pair.prefer); assertTrue(pair.backup)
        val prf = UnisonProfile.generate(pair)
        assertTrue("the shared ignore rules reach the user's pair", prf.contains("ignore = Name .DS_Store") && prf.contains("ignore = Name *.tmp"))
        assertTrue(prf.contains("root = ${pair.rootA}"))
    }

    @Test fun `a local peer pair works and a duplicate name gets its own id`() {
        val a = UnisonPairs.add("", Draft("Pics", "Pictures", localPeer = "/storage/1234-ABCD/Pictures"), policy, storage).getOrThrow()
        val b = UnisonPairs.add(a, Draft("Pics", "Pictures2", localPeer = "/storage/1234-ABCD/P2"), policy, storage).getOrThrow()
        assertEquals(listOf("pics", "pics-2"), UnisonPairs.parse(policy, b, storage).pairs.map { it.id })
    }

    @Test fun `the parser's refusal comes back as the sentence, and nothing is saved`() {
        val r = UnisonPairs.add("", ssh.copy(host = "8.8.8.8"), policy, storage)
        assertTrue(r.isFailure); assertTrue(r.exceptionOrNull()!!.message!!, r.exceptionOrNull()!!.message!!.contains("mesh"))
        assertTrue(UnisonPairs.add("", ssh.copy(rootA = "../x"), policy, storage).isFailure)
        assertTrue(UnisonPairs.add("", ssh.copy(scheduleMinutes = 5), policy, storage).isFailure)
        assertTrue(UnisonPairs.add("", ssh.copy(prefer = "bogus"), policy, storage).isFailure)
    }

    @Test fun `remove drops only that pair and damaged text is an empty state, not a crash`() {
        val a = UnisonPairs.add("", ssh, policy, storage).getOrThrow()
        val b = UnisonPairs.add(a, Draft("Pics", "Pictures", localPeer = "/storage/x/P"), policy, storage).getOrThrow()
        assertEquals(listOf("pics"), UnisonPairs.parse(policy, UnisonPairs.remove(b, "docs-to-box"), storage).pairs.map { it.id })
        assertEquals(emptyList<String>(), UnisonPairs.parse(policy, "not json", storage).pairs.map { it.id })
        assertNotNull(UnisonSyncWorker.WORK_NAME)
    }

    @Test fun `the saved text holds no credential, the ssh key is Cloud Account's`() {
        val text = UnisonPairs.add("", ssh, policy, storage).getOrThrow().lowercase()
        for (w in listOf("password", "passphrase", "private", "secret", "token", "key")) assertFalse(w, w in text)
    }

    @Test fun `no profile file is shipped by hand and the version is not restated`() {
        assertTrue(root.walkTopDown().none { it.isFile && it.extension == "prf" })
        assertFalse(policy.contains("2.53"))
    }
}
