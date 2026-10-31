package com.diegonmarcos.clouddrive.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1358 the git engine's All files access: read from its own `info`, said once, and the row and report say the real reason. */
class EngineGrantTest {
    private fun info(ok: Boolean, access: Boolean?) = JSONObject().put("ok", ok).put("version", "7.8.0").also { if (access != null) it.put("files_access", access) }

    @Test fun `the engine's info answer is the grant`() {
        assertEquals(EngineGrant.Grant.MISSING, EngineGrant.fromInfo(info(true, false), sdk = 34))
        assertEquals(EngineGrant.Grant.GRANTED, EngineGrant.fromInfo(info(true, true), sdk = 34))
        // an old engine (no files_access) or no answer says nothing: no banner on a guess
        assertEquals(EngineGrant.Grant.UNKNOWN, EngineGrant.fromInfo(info(true, null), sdk = 34))
        assertEquals(EngineGrant.Grant.UNKNOWN, EngineGrant.fromInfo(info(false, false), sdk = 34))
        assertEquals(EngineGrant.Grant.UNKNOWN, EngineGrant.fromInfo(null, sdk = 34))
        // below Android 11 there is no All files access to grant
        assertEquals(EngineGrant.Grant.GRANTED, EngineGrant.fromInfo(info(true, false), sdk = 29))
    }

    @Test fun `only shared storage needs the engine's grant, never the store`() {
        assertTrue(EngineGrant.touchesSharedStorage("/storage/self/primary/vaults/_git-repos-master"))
        assertTrue(EngineGrant.touchesSharedStorage("/sdcard/Obsidian/notes"))
        assertFalse(EngineGrant.touchesSharedStorage("/data/user/0/cld.termux/files/home/cloud-shared-store/git/_git-repos-master"))
    }

    @Test fun `the engine's refusal reads as FILES_ACCESS, not as an engine that did not answer`() {
        val engine = "the git sync engine has no All files access, so it cannot read or write the shared storage"
        assertEquals(RepoStatus.ErrorKind.FILES_ACCESS, RepoStatus.errorKind(engine))
        assertEquals(RepoStatus.ErrorKind.FILES_ACCESS, RepoStatus.errorKind(EngineGrant.REASON + " (/sdcard/x)"))
        assertEquals(RepoStatus.ErrorKind.ENGINE, RepoStatus.errorKind("the git sync engine did not answer git"))
    }

    @Test fun `the report is one line per repository, the name literal and the reason real`() {
        val nothing = BulkDepth.withTail("bare clone in cld.termux failed (exit 1)", "")
        assertEquals("bare clone in cld.termux failed (exit 1): git printed nothing", nothing)
        val denied = BulkDepth.withTail("bare clone in cld.termux failed (exit 1)", "mkdir: cannot create directory '/root/x': Permission denied\n")
        assertEquals("bare clone in cld.termux failed (exit 1): mkdir: cannot create directory '/root/x': Permission denied", denied)
        // a summary that already carries the reason is not doubled
        assertEquals(denied, BulkDepth.withTail(denied, "mkdir: cannot create directory '/root/x': Permission denied"))
        val run = BulkDepth.Run("clone_all_bare", "clone_bare", 2, 2, failed = mapOf("_git-repos-master" to denied, "cloudy-___" to nothing), ended = true)
        val lines = run.lines()
        assertEquals(2, lines.size)
        assertTrue(lines.none { '\n' in it })
        assertTrue(lines[0].startsWith("failed  _git-repos-master  bare clone"))
        assertTrue(lines[1].startsWith("failed  cloudy-___  bare clone"))
    }
}
