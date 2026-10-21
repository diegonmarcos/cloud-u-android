package com.diegonmarcos.cloudlib.rclone.unison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1084 conflict handling, part two: what keep-A, keep-B and keep-both ask the engine, and that none of them overwrites silently. */
class UnisonResolveTest {
    private val pair = UnisonConfig.parse(
        """{"pairs":[{"id":"docs","root_a":"Sync/Docs","root_b":{"host":"oci-apps.app","user":"diego","path":"/srv/sync/docs"},"max_backups":4}]}"""
    ).pairs.single()
    private val rootB = "socket://127.0.0.1:{PORT}//srv/sync/docs"

    private fun flag(extra: List<String>, name: String) = extra[extra.indexOf(name) + 1]

    @Test fun keepAPrefersThePhonesRootForOnePathOnly() {
        val p = UnisonResolve.plan(pair, "notes/todo.md", UnisonResolve.Choice.KEEP_A, "20261021-120000")
        assertEquals("notes/todo.md", flag(p.extra, "-path"))
        assertEquals("/sdcard/Sync/Docs", flag(p.extra, "-prefer"))
        assertNull(p.keepCopyRoot)
    }

    @Test fun keepBPrefersTheOtherSide() {
        val p = UnisonResolve.plan(pair, "notes/todo.md", UnisonResolve.Choice.KEEP_B, "x")
        assertEquals(rootB, flag(p.extra, "-prefer"))
        assertNull(p.keepCopyRoot)
    }

    @Test fun keepBothKeepsTheOtherSidesNameAndCopiesThePhonesVersionBesideIt() {
        val p = UnisonResolve.plan(pair, "notes/todo.md", UnisonResolve.Choice.KEEP_BOTH, "20261021-120000")
        assertEquals(rootB, flag(p.extra, "-prefer"))
        assertEquals("the phone's version is the one that must be copied back", "/sdcard/Sync/Docs", p.keepCopyRoot)
        assertEquals("conflict-local-20261021-120000", p.suffix)
    }

    @Test fun everyChoiceKeepsABackupOfWhatItReplaces_neverASilentOverwrite() {
        for (c in UnisonResolve.Choice.values()) {
            val e = UnisonResolve.plan(pair, "a/b.txt", c, "s").extra
            assertEquals("Path a/b.txt", flag(e, "-backup"))
            assertEquals("central", flag(e, "-backuplocation"))
            assertEquals("4", flag(e, "-maxbackups"))
        }
    }

    @Test fun theBigDeletionConfirmationIsRelaxedOnlyInsideAScopedRun() {
        for (c in UnisonResolve.Choice.values()) {
            val e = UnisonResolve.plan(pair, "a.txt", c, "s").extra
            assertTrue("-confirmbigdel=false" in e)
            assertNotNull("and it never travels without -path", e.indexOf("-path").takeIf { it >= 0 })
        }
    }

    @Test fun everyChoiceDescribesItsConsequenceToTheUser() {
        for (c in UnisonResolve.Choice.values()) assertTrue(UnisonResolve.describe(pair, c).isNotBlank())
        assertTrue(UnisonResolve.describe(pair, UnisonResolve.Choice.KEEP_BOTH).contains("conflict-local"))
    }
}
