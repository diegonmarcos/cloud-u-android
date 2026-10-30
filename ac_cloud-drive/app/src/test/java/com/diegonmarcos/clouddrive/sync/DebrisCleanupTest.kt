package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1315 the leftover cleanup's pure rules: the cld.termux script deletes only a folder older than the declared age (exit 20
 * when younger, 16 when gone), the java.io twin applies the same age, and the sizes script answers one line per folder.
 * Mutations proven red: dropping `-mmin` from the script (theScriptRefusesAYoungFolder), `>=` to `>` on a zero age (oldEnough).
 */
class DebrisCleanupTest {
    private fun q(s: String) = "'$s'"

    @Test fun `the script refuses a young or gone folder and deletes an old one`() {
        val s = FolderActions.debrisScript("/root/s/git/x.incoming", 360, ::q)
        assertTrue(s.contains("-mmin +360")); assertTrue(s.contains("|| exit 20")); assertTrue(s.contains("|| exit 16"))
        assertTrue("the delete is the last step, on the one folder", s.trimEnd().endsWith("rm -rf -- '/root/s/git/x.incoming'"))
        assertTrue("never below one minute", FolderActions.debrisScript("/x", 0, ::q).contains("-mmin +1"))
    }

    @Test fun `old enough is the same age rule in java io`() {
        assertTrue(FolderActions.debrisOldEnough(1_000L, 1_000L + 6 * 3_600_000L, 360))
        assertFalse(FolderActions.debrisOldEnough(1_000L, 1_000L + 6 * 3_600_000L - 1, 360))
        assertFalse("an unreadable mtime is never old enough", FolderActions.debrisOldEnough(0L, 10_000_000L, 1))
    }

    @Test fun `the sizes script answers one line per folder in kilobytes`() {
        val s = com.diegonmarcos.clouddrive.sync.inventory.InventoryScript.sizesScript(listOf("/root/s/git/a", "/root/s/git/b"), ::q)
        assertTrue(s.startsWith("for d in '/root/s/git/a' '/root/s/git/b'; do")); assertTrue(s.contains("du -sk"))
        assertEquals(mapOf("/root/s/git/a" to 12L * 1024, "/root/s/git/b" to 0L), com.diegonmarcos.clouddrive.sync.inventory.InventoryScript.parseSizes("/root/s/git/a\t12\n/root/s/git/b\t0\n/root/s/git/c\t\nnoise\n"))
    }
}
