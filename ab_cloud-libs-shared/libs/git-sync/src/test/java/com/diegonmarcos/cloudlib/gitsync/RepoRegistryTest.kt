package com.diegonmarcos.cloudlib.gitsync

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoRegistryTest {

    private fun tmp(): File = File(Files.createTempDirectory("registry").toFile(), "git-sync/repos.json")

    @Test fun roundTripsEveryFieldAndReplacesById() {
        val reg = RepoRegistry(tmp())
        assertTrue(reg.load().isEmpty())
        val a = ManagedRepo("r1", "notes", "/sdcard/notes", remoteUrl = "https://x/y.git", authKind = "https",
            authUsername = "me", sshKeyPath = "", authorName = "Me", authorEmail = "me@x", pullRebase = false,
            syncMessage = "auto", lastSyncEpochSeconds = 42, lastSyncSummary = "pushed")
        reg.upsert(a)
        reg.upsert(ManagedRepo("r2", "other", "/sdcard/other"))
        assertEquals(listOf(a, ManagedRepo("r2", "other", "/sdcard/other")), reg.load())
        reg.upsert(a.copy(name = "renamed"))
        assertEquals(listOf("other", "renamed"), reg.load().map { it.name })
        reg.remove("r2")
        assertEquals(listOf("renamed"), reg.load().map { it.name })
    }

    @Test fun malformedFileReadsAsEmptyAndIsRecoverable() {
        val f = tmp(); f.parentFile.mkdirs(); f.writeText("{not json")
        val reg = RepoRegistry(f)
        assertTrue(reg.load().isEmpty())
        reg.upsert(ManagedRepo("x", "x", "/x"))
        assertEquals(1, reg.load().size)
    }

    @Test fun unknownKeysFromANewerVersionAreIgnored() {
        val f = tmp(); f.parentFile.mkdirs()
        f.writeText("""[{"id":"a","name":"a","path":"/a","futureField":true}]""")
        assertEquals("a", RepoRegistry(f).load().single().id)
    }

    @Test fun idIsStableForOnePathAndDistinctAcrossPaths() {
        assertEquals(RepoRegistry.idFor("/sdcard/notes/"), RepoRegistry.idFor("/sdcard/notes"))
        assertNotEquals(RepoRegistry.idFor("/sdcard/notes"), RepoRegistry.idFor("/sdcard/other"))
    }
}
