package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.PackageInstallerReceiver
import com.diegonmarcos.superapp.updater.cache.ApkCache
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * #774 — A FINISHED DOWNLOAD IS NEVER FETCHED TWICE.
 *
 * The owner's report, reproduced exactly: a ~400 MB lib APK (the proot rootfs)
 * finished downloading, the system install sheet was cancelled, and the Store
 * then downloaded all of it again. The receiver had KEPT the file — the bug was
 * that the next Install never looked: Fleet.download went straight to
 * ReleaseSource, and Download.toFile only short-circuits on a complete `.part`,
 * which a finished download no longer is.
 *
 * Driven through the REAL path — Fleet.download → ReleaseSource → Download over
 * HttpURLConnection against a local server that counts asset GETs and bytes
 * served, and the REAL PackageInstallerReceiver handed STATUS_FAILURE_ABORTED.
 * No mocks of the thing under test.
 *
 * Every assertion has its NEGATIVE CONTROL beside it: the same scenario with the
 * guarded behaviour removed by hand (the cached file deleted; the `.part`
 * dropped) must move the counter. That is what makes the green meaningful — a
 * counter that cannot see a re-download would pass the main assertion forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreCacheStagesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pkg = "org.example.rootfs"
    private lateinit var server: HttpServer
    private val assetGets = AtomicInteger()
    private val servedBytes = AtomicLong()
    private val rangesSeen = mutableListOf<String>()
    private lateinit var apk: ByteArray

    /** A real zip (VerifiedApk checks the structure) big enough that a resume
     *  is distinguishable from a restart by the byte count alone. */
    private fun fakeApk(): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z ->
            z.setLevel(0)
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(pkg.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("assets/rootfs.bin")); z.write(ByteArray(256 * 1024) { (it % 251).toByte() }); z.closeEntry()
        }
    }.toByteArray()

    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun send(x: HttpExchange, code: Int, body: ByteArray) {
        x.sendResponseHeaders(code, body.size.toLong())
        x.responseBody.use { it.write(body) }
    }

    @Before
    fun up() {
        ApkCache.clear(ctx)
        apk = fakeApk()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/r/Rootfs.apk") { x ->
            if (x.requestMethod == "HEAD") {
                x.responseHeaders.add("Content-Length", apk.size.toString())
                x.sendResponseHeaders(200, -1); x.close(); return@createContext
            }
            assetGets.incrementAndGet()
            val range = x.requestHeaders.getFirst("Range")
            if (range != null) synchronized(rangesSeen) { rangesSeen += range }
            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val body = apk.copyOfRange(from, apk.size)
            servedBytes.addAndGet(body.size.toLong())
            if (from > 0) {
                x.responseHeaders.add("Content-Range", "bytes $from-${apk.size - 1}/${apk.size}")
                send(x, 206, body)
            } else send(x, 200, body)
        }
        server.createContext("/r/Rootfs.apk.sha256") { x ->
            send(x, 200, "${hex(apk)}  Rootfs.apk\n".toByteArray())
        }
        server.start()
    }

    @After
    fun down() {
        server.stop(0)
        ApkCache.clear(ctx)
        ApkCache.clearNote(ctx, pkg)
    }

    private fun app() = Fleet.App(
        id = "lib-rootfs-test", label = "Rootfs", pkg = pkg, altId = null,
        // GHCR is the fallback source; pointing it at a closed port makes any
        // fall-through fail loudly instead of quietly succeeding elsewhere.
        registry = "127.0.0.1:1", namespace = "x", image = "x", tag = "latest",
        asset = "Rootfs.apk", assets = emptyMap(),
        releaseUrl = "http://127.0.0.1:${server.address.port}/r/Rootfs.apk",
        repoUrl = "", ghcrPage = "", blocked = false, kind = "lib",
    )

    /** What Android delivers when the user taps Cancel on the install sheet. */
    private fun cancelInstallSheet(path: String) {
        PackageInstallerReceiver().onReceive(ctx, Intent()
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
            .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "User rejected permissions")
            .putExtra(PackageInstallerReceiver.EXTRA_APK_PATH, path)
            .putExtra(PackageInstallerReceiver.EXTRA_TARGET_PKG, pkg))
    }

    @Test
    fun `completed download + install cancelled by the user = NO re-download`() {
        val first = Fleet.download(ctx, app())
        assertEquals("the first download fetches the asset once", 1, assetGets.get())

        cancelInstallSheet(first.file.absolutePath)
        assertTrue("a cancelled install must keep the cached APK", first.file.exists())
        assertEquals("the row must learn WHY it is still cached",
            ApkCache.STAGE_INSTALL, ApkCache.noteOf(ctx, pkg)?.stage)

        val again = Fleet.download(ctx, app())
        assertEquals("Install after a cancelled sheet must reuse the cache, not fetch " +
            "${apk.size} bytes again", 1, assetGets.get())
        assertEquals(first.file.canonicalPath, again.file.canonicalPath)
        assertArrayEquals(apk, again.file.readBytes())
        assertEquals("a successful (cached) download clears the stage note", null, ApkCache.noteOf(ctx, pkg))
    }

    @Test
    fun `control - with the cached file gone the same path DOES re-download`() {
        val first = Fleet.download(ctx, app())
        cancelInstallSheet(first.file.absolutePath)
        ApkCache.drop(first.file)   // the cache discarded — exactly the old behaviour's effect
        Fleet.download(ctx, app())
        assertEquals("the counter must see a re-download, or the test above proves nothing",
            2, assetGets.get())
    }

    @Test
    fun `a cached file that no longer hashes to the published sha is NOT reused`() {
        val first = Fleet.download(ctx, app())
        first.file.writeBytes(apk.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        val again = Fleet.download(ctx, app())
        assertEquals("corrupted cache must be re-fetched, never installed", 2, assetGets.get())
        assertArrayEquals(apk, again.file.readBytes())
    }

    @Test
    fun `a download killed half way resumes with Range and fetches only the rest`() {
        val half = apk.size / 2
        // The process died here: a `.part` holding the first half, nothing else.
        ApkCache.file(ctx, "fleet-lib-rootfs-test-release.apk.part").writeBytes(apk.copyOfRange(0, half))
        val done = Fleet.download(ctx, app())
        assertArrayEquals("the resumed file is the artifact, byte for byte", apk, done.file.readBytes())
        assertEquals("only the missing half crosses the network", (apk.size - half).toLong(), servedBytes.get())
        assertEquals(listOf("bytes=$half-"), synchronized(rangesSeen) { rangesSeen.toList() })
    }

    @Test
    fun `control - without the part the whole artifact is served`() {
        Fleet.download(ctx, app())
        assertEquals(apk.size.toLong(), servedBytes.get())
        assertTrue(synchronized(rangesSeen) { rangesSeen.isEmpty() })
    }

    @Test
    fun `stale builds of the same package are pruned when a newer one is cached`() {
        fun cached(name: String, code: Long) = ApkCache.file(ctx, name).apply {
            writeBytes(apk)
            java.io.File(parentFile, "$name.record").writeText("$pkg\n$code\n${hex(apk)}\n")
        }
        val old = cached("fleet-lib-rootfs-test-old.apk", 5L)
        val other = cached("fleet-other.apk", 5L).also {
            java.io.File(it.parentFile, "${it.name}.record").writeText("org.example.other\n5\n${hex(apk)}\n")
        }
        val pruned = ApkCache.pruneStale(ctx, pkg, 6L)
        assertEquals(listOf(old.name), pruned)
        assertTrue("an older build of the same package is gone", !old.exists())
        assertTrue("another package's cache is untouched", other.exists())
        assertNotNull(ApkCache.record(other))
    }
}
