package com.diegonmarcos.cloudlib.gitsync

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** #1070: a repository the host routes elsewhere (the shared store) is answered by the host's probe, off the calling thread, cached per path. */
class RepositoryProbeTest {
    private val dir = File("/data/user/0/cld.termux/files/home/cloud-shared-store/git/r")

    @After fun reset() { GitEngine.repositoryProbe = null; GitEngine.forgetRepository(dir) }

    @Test fun `a routed directory is answered by the probe, not by the disk, and only asked once`() {
        val asked = AtomicInteger(); var threadName = ""
        GitEngine.repositoryProbe = { asked.incrementAndGet(); threadName = Thread.currentThread().name; true }
        assertTrue(runBlocking { GitEngine.isRepositoryAsync(dir) })
        assertTrue(GitEngine.isRepository(dir)); assertTrue(runBlocking { GitEngine.isRepositoryAsync(dir) })
        assertEquals("cached per path", 1, asked.get())
        assertTrue("asked on an IO worker, not the caller's thread", threadName.contains("DefaultDispatcher") || threadName != Thread.currentThread().name)
    }

    @Test fun `a probe that cannot answer is false and is not cached, a clone forgets the answer`() {
        var answer: Boolean? = null
        GitEngine.repositoryProbe = { answer }
        assertFalse(GitEngine.isRepository(dir))
        answer = true
        assertTrue("the earlier no-answer was not remembered", GitEngine.isRepository(dir))
        answer = false
        GitEngine.forgetRepository(dir)
        assertFalse(GitEngine.isRepository(dir))
    }

    @Test fun `a real repository on disk needs no probe`() {
        val d = Files.createTempDirectory("repo").toFile(); File(d, ".git").mkdirs()
        GitEngine.repositoryProbe = { error("must not be asked") }
        assertTrue(GitEngine.isRepository(d)); d.deleteRecursively()
    }
}
