package com.diegonmarcos.cloudlib.rclone.unison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1084 the declaration is the one source of truth: what it accepts, and every way it refuses a pair. */
class UnisonConfigTest {

    private fun decl(pair: String, defaults: String = "{}", ignore: String = "[]") =
        """{"defaults":$defaults,"ignore":$ignore,"pairs":[$pair]}"""

    private val ssh = """{"id":"docs","name":"Docs","root_a":"Sync/Docs","root_b":{"host":"oci-apps.app","user":"diego","path":"/srv/sync/docs"}}"""

    @Test fun aValidSshPairParsesWithTheDeclaredDefaults() {
        val d = UnisonConfig.parse(decl(ssh, defaults = """{"auto":true,"fastcheck":"default","backup":true,"max_backups":5}""", ignore = """["Name .DS_Store","Name *.tmp","Name build"]"""))
        assertEquals(emptyList<String>(), d.problems)
        val p = d.pairs.single()
        assertEquals("docs", p.id)
        assertEquals("/sdcard/Sync/Docs", p.rootA)
        assertEquals(UnisonConfig.Peer.Ssh("oci-apps.app", 22, "diego", "/srv/sync/docs", "", "unison -server"), p.peer)
        assertEquals(listOf("Name .DS_Store", "Name *.tmp", "Name build"), p.ignore)
        assertNull("no policy = every conflict asks", p.prefer)
        assertTrue(p.backup); assertEquals(5, p.maxBackups); assertEquals("default", p.fastcheck)
        assertTrue(p.enabled); assertNull(p.scheduleMinutes)
        assertTrue("scheduled runs wait for an unmetered network unless told otherwise", p.requireUnmetered)
    }

    @Test fun aPairsOwnIgnoreRulesExtendTheSharedOnesWithoutDuplicates() {
        val pair = ssh.replace("\"root_b\"", "\"ignore\":[\"Name *.tmp\",\"Path private\"],\"root_b\"")
        val p = UnisonConfig.parse(decl(pair, ignore = """["Name *.tmp","Name .DS_Store"]""")).pairs.single()
        assertEquals(listOf("Name *.tmp", "Name .DS_Store", "Path private"), p.ignore)
    }

    @Test fun aScheduleAndTheMeteredRuleAreDeclaredPerPair() {
        val p = UnisonConfig.parse(decl(ssh.replace("\"root_b\"", "\"schedule_minutes\":60,\"require_unmetered\":false,\"root_b\""))).pairs.single()
        assertEquals(60, p.scheduleMinutes); assertFalse(p.requireUnmetered)
    }

    @Test fun aLocalToLocalPairIsAllowed() {
        val d = UnisonConfig.parse(decl("""{"id":"pics","root_a":"DCIM","root_b":{"path":"/storage/1234-ABCD/DCIM"}}"""))
        assertEquals(emptyList<String>(), d.problems)
        assertEquals(UnisonConfig.Peer.Local("/storage/1234-ABCD/DCIM"), d.pairs.single().peer)
    }

    private fun refused(pair: String, defaults: String = "{}", ignore: String = "[]", needle: String) {
        val d = UnisonConfig.parse(decl(pair, defaults, ignore))
        assertTrue("a pair that breaks '$needle' must not run: ${d.pairs}", d.pairs.isEmpty())
        assertTrue("expected a problem naming '$needle', got ${d.problems}", d.problems.any { it.contains(needle) })
    }

    @Test fun aHostThatIsNotAMeshNameIsRefused() {
        refused(ssh.replace("oci-apps.app", "203.0.113.7"), needle = "<name>.app mesh name")
        refused(ssh.replace("oci-apps.app", "files.diegonmarcos.com"), needle = "<name>.app mesh name")
        refused(ssh.replace("oci-apps.app", "OCI_apps.app"), needle = "<name>.app mesh name")
    }

    @Test fun anAutomaticWinnerWithoutBackupIsASilentOverwriteAndIsRefused() {
        refused(ssh.replace("\"root_b\"", "\"prefer\":\"newer\",\"backup\":false,\"root_b\""), needle = "no silent overwrite")
        refused(ssh, defaults = """{"prefer":"a","backup":false}""", needle = "no silent overwrite")
        val ok = UnisonConfig.parse(decl(ssh.replace("\"root_b\"", "\"prefer\":\"newer\",\"root_b\"")))
        assertEquals("a policy that keeps backups is fine", "newer", ok.pairs.single().prefer)
    }

    @Test fun badValuesAreRefusedEachNamingTheirField() {
        refused(ssh.replace("\"root_b\"", "\"prefer\":\"biggest\",\"root_b\""), needle = "prefer 'biggest'")
        refused(ssh.replace("\"root_b\"", "\"fastcheck\":\"maybe\",\"root_b\""), needle = "fastcheck 'maybe'")
        refused(ssh.replace("\"root_b\"", "\"max_backups\":0,\"root_b\""), needle = "max_backups")
        refused(ssh.replace("\"root_b\"", "\"schedule_minutes\":5,\"root_b\""), needle = "WorkManager's floor")
        refused(ssh.replace("\"root_b\"", "\"ignore\":[\"*.tmp\"],\"root_b\""), needle = "ignore rule '*.tmp'")
        refused(ssh.replace("Sync/Docs", "/abs/dir"), needle = "root_a must be a folder relative to shared storage")
        refused(ssh.replace("Sync/Docs", "../../data/data/x"), needle = "no ..")
        refused(ssh.replace("/srv/sync/docs", "/srv/../etc"), needle = "climbs")
        refused(ssh.replace("\"id\":\"docs\"", "\"id\":\"Docs Pair\""), needle = "id must be")
    }

    @Test fun oneBadPairNeverHidesTheOthersAndDuplicatesAreRefused() {
        val good = ssh
        val bad = ssh.replace("\"id\":\"docs\"", "\"id\":\"bad\"").replace("oci-apps.app", "1.2.3.4")
        val dup = ssh.replace("Docs\"", "Docs again\"")
        val d = UnisonConfig.parse(decl("$good,$bad,$dup"))
        assertEquals(listOf("docs"), d.pairs.map { it.id })
        assertEquals(2, d.problems.size)
        assertTrue(d.problems.any { it.contains("declared twice") })
    }

    @Test fun anEmptyDeclarationIsAValidPhone() {
        assertEquals(UnisonConfig.Declared(emptyList(), emptyList()), UnisonConfig.parse(""))
        assertFalse(UnisonConfig.parse("""{"pairs":[]}""").pairs.isNotEmpty())
    }
}
