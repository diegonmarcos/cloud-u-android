package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.cloudbrowser.torrent.TorrentFiles
import com.diegonmarcos.cloudbrowser.torrent.TorrentSettings
import com.diegonmarcos.superapp.browser.download.DlPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A real libtorrent session on the JVM (libtorrent4j-linux, test-only): it starts with the
 * browser's settings (DHT, LSD, uTP), adds the Sintel magnet, writes save_resume_data, reads that
 * resume data back the way LtTorrentBridge does after a restart, and stops. No peer is needed:
 * everything asserted happens locally. (Metadata resolution needs the swarm; that is the on-device
 * check.)
 */
class TorrentSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    companion object {
        private const val HASH = "08ada5a7a6183aae1e09d831df6748d566095a10"
        private const val SINTEL = "magnet:?xt=urn:btih:$HASH&dn=Sintel"
        private var loaded = false
        private var why = ""

        /** libtorrent4j loads its JNI library from `libtorrent4j.jni.path`: extract the linux one from the test classpath. */
        @BeforeClass @JvmStatic fun native() {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch")
            if (!os.contains("linux") || (arch != "amd64" && arch != "x86_64")) { why = "host is $os/$arch"; return }
            val url = TorrentSessionTest::class.java.classLoader!!.getResources("lib/x86_64/libtorrent4j.so").toList()
                .firstOrNull { it.toString().contains("libtorrent4j-linux") } ?: run { why = "no linux libtorrent4j on the test classpath"; return }
            val f = File.createTempFile("libtorrent4j", ".so").apply { deleteOnExit() }
            url.openStream().use { i -> f.outputStream().use { i.copyTo(it) } }
            System.setProperty("libtorrent4j.jni.path", f.path)
            loaded = runCatching { org.libtorrent4j.LibTorrent.version(); true }.getOrElse { why = it.toString(); false }
        }
    }

    @Test fun `a session starts, adds a magnet, saves resume data that reloads, and stops`() {
        assumeTrue("libtorrent native library not loadable here: $why", loaded)
        val sm = org.libtorrent4j.SessionManager(false)
        val added = CountDownLatch(1)
        val saved = CountDownLatch(1)
        val handle = AtomicReference<org.libtorrent4j.TorrentHandle>()
        val resume = AtomicReference<ByteArray>()
        sm.addListener(object : org.libtorrent4j.AlertListener {
            override fun types() = intArrayOf(org.libtorrent4j.alerts.AlertType.ADD_TORRENT.swig(), org.libtorrent4j.alerts.AlertType.SAVE_RESUME_DATA.swig())
            override fun alert(a: org.libtorrent4j.alerts.Alert<*>) {
                when (a) {
                    is org.libtorrent4j.alerts.AddTorrentAlert -> { handle.set(a.handle()); added.countDown() }
                    is org.libtorrent4j.alerts.SaveResumeDataAlert -> {
                        resume.set(org.libtorrent4j.AddTorrentParams.writeResumeDataBuf(a.params())); saved.countDown()
                    }
                    else -> Unit
                }
            }
        })
        val pack = TorrentSettings.pack(DlPolicy(speedLimitKbps = 100, uploadKbps = 10, parallel = 2))
        assertTrue(pack.isEnableDht && pack.isEnableLsd)
        assertTrue(pack.getBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_outgoing_utp.swigValue()))
        assertEquals(100 * 1024, pack.downloadRateLimit())
        assertEquals(10 * 1024, pack.uploadRateLimit())
        // Listen on loopback only: the test needs no peer and must not open a public port.
        pack.listenInterfaces("127.0.0.1:0")
        pack.setEnableDht(false)
        sm.start(org.libtorrent4j.SessionParams(pack))
        try {
            assertTrue(sm.isRunning)
            val save = tmp.newFolder("job", "torrent")
            val p = TorrentFiles.params(save.parentFile!!, null, SINTEL)
            assertNotNull("the magnet makes add_torrent_params", p)
            p!!.savePath = save.path
            org.libtorrent4j.SessionHandle(sm.swig()).asyncAddTorrent(p)
            assertTrue("add_torrent_alert", added.await(15, TimeUnit.SECONDS))
            val h = handle.get()
            assertTrue(h.isValid)
            assertEquals(HASH, h.infoHash().toHex())
            h.setFlags(org.libtorrent4j.TorrentFlags.SEQUENTIAL_DOWNLOAD)
            assertTrue(h.flags.and_(org.libtorrent4j.TorrentFlags.SEQUENTIAL_DOWNLOAD).non_zero())
            h.pause()
            h.saveResumeData(org.libtorrent4j.TorrentHandle.SAVE_INFO_DICT)
            assertTrue("save_resume_data_alert", saved.await(15, TimeUnit.SECONDS))
            val bytes = resume.get()
            assertTrue(bytes.isNotEmpty())
            // What LtTorrentBridge does after a restart: resume data wins over the magnet.
            TorrentFiles.writeAtomic(TorrentFiles.resume(save.parentFile!!), bytes)
            assertArrayEquals(bytes, TorrentFiles.resume(save.parentFile!!).readBytes())
            val back = TorrentFiles.params(save.parentFile!!, null, "not-a-magnet")
            assertNotNull("resume data reloads", back)
            assertEquals(HASH, back!!.infoHashes.getBest().toHex())
            assertEquals(save.path, back.savePath)
        } finally {
            sm.stop()
        }
        assertFalse(sm.isRunning)
    }
}
