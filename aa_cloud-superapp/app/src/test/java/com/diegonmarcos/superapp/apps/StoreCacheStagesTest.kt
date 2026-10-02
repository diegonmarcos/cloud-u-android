package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.FleetInstall
import com.diegonmarcos.superapp.appstore.StoreStages
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.PackageInstallerReceiver
import com.diegonmarcos.superapp.updater.cache.ApkCache
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * The smallest HTTP/1.1 responder that HttpURLConnection will talk to:
 * one request per connection (`Connection: close`), request line + headers in,
 * status + headers + body out, no body on HEAD. com.sun.net.httpserver is not
 * on the Android unit-test classpath; java.net.ServerSocket is.
 */
internal class TinyHttp(
    private val handle: (method: String, path: String, headers: Map<String, String>) -> Triple<Int, Map<String, String>, ByteArray>,
) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "tiny-http") {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { c.use { serve(it) } } }
            }
        }
    }

    private fun serve(c: Socket) {
        val inp = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val parts = (inp.readLine() ?: return).split(" ")
        val headers = HashMap<String, String>()
        while (true) {
            val l = inp.readLine() ?: break
            if (l.isEmpty()) break
            headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
        }
        val (code, extra, body) = handle(parts[0], parts[1], headers)
        val head = StringBuilder("HTTP/1.1 $code X\r\n")
        extra.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        val out = c.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (parts[0] != "HEAD") out.write(body)
        out.flush()
    }

    fun stop() = socket.close()
}

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
    private lateinit var server: TinyHttp
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

    @Before
    fun up() {
        ApkCache.clear(ctx)
        apk = fakeApk()
        server = TinyHttp { method, path, headers ->
            when {
                path == "/r/Rootfs.apk.sha256" ->
                    Triple(200, emptyMap(), "${hex(apk)}  Rootfs.apk\n".toByteArray())
                path != "/r/Rootfs.apk" -> Triple(404, emptyMap(), ByteArray(0))
                method == "HEAD" -> Triple(200, emptyMap(), apk)   // length only; TinyHttp sends no body
                else -> {
                    assetGets.incrementAndGet()
                    val range = headers["range"]
                    if (range != null) synchronized(rangesSeen) { rangesSeen += range }
                    val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    val body = apk.copyOfRange(from, apk.size)
                    servedBytes.addAndGet(body.size.toLong())
                    if (from > 0) Triple(206, mapOf("Content-Range" to "bytes $from-${apk.size - 1}/${apk.size}"), body)
                    else Triple(200, emptyMap(), body)
                }
            }
        }
    }

    @After
    fun down() {
        server.stop()
        StoreStages.installer = FleetInstall::install
        ApkCache.clear(ctx)
        ApkCache.clearNote(ctx, pkg)
    }

    private fun app(releaseUrl: String = "http://127.0.0.1:${server.port}/r/Rootfs.apk") = Fleet.App(
        id = "lib-rootfs-test", label = "Rootfs", pkg = pkg, altId = null,
        // GHCR is the fallback source; pointing it at a closed port makes any
        // fall-through fail loudly instead of quietly succeeding elsewhere.
        registry = "127.0.0.1:1", namespace = "x", image = "x", tag = "latest",
        asset = "Rootfs.apk", assets = emptyMap(),
        releaseUrl = releaseUrl,
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

    // ── #774 the three stages and the auto chain ─────────────────────────

    private val sheets = AtomicInteger()

    /** Stand-in for Android's install sheet behind [StoreStages.installer].
     *  Accept installs the handed-over bytes as the package (so the device
     *  really runs them — [ApkCache.landed] hashes sourceDir); Cancel delivers
     *  STATUS_FAILURE_ABORTED to the REAL receiver, exactly as the platform does. */
    private fun sheet(accept: Boolean) {
        StoreStages.installer = { c, _, v ->
            sheets.incrementAndGet()
            if (accept) {
                val src = java.io.File(c.filesDir, "installed-$pkg.apk").apply { writeBytes(v.file.readBytes()) }
                shadowOf(c.packageManager).installPackage(PackageInfo().apply {
                    packageName = pkg; versionName = "7"; longVersionCode = 7L
                    applicationInfo = ApplicationInfo().apply { packageName = pkg; sourceDir = src.absolutePath }
                })
            } else cancelInstallSheet(v.file.absolutePath)
            null
        }
    }

    @Test
    fun `auto chain stops at Download when the download fails, and never opens the installer`() {
        sheet(accept = true)
        // No release asset, GHCR on a closed port: every source fails, fast.
        val s = StoreStages.auto(ctx, app(releaseUrl = ""))
        assertEquals("download", s.failedAt)
        assertEquals("the row offers exactly Download to take over from", listOf("download"), s.actions)
        assertEquals("Install must not run without a cached APK", 0, sheets.get())
        assertEquals(ApkCache.STAGE_DOWNLOAD, ApkCache.noteOf(ctx, pkg)?.stage)
    }

    @Test
    fun `auto chain stops at Install when the sheet is cancelled, cache kept, Install takes over without a download`() {
        sheet(accept = false)
        val stopped = StoreStages.auto(ctx, app())
        assertEquals(1, assetGets.get())
        assertEquals("install", stopped.failedAt)
        assertEquals(listOf("install", "download", "clear"), stopped.actions)
        val cached = stopped.cached
        assertNotNull("the stage names the cached APK it stopped with", cached)
        assertTrue(cached!!.file.exists())
        assertTrue("the reason is the installer's own: ${stopped.text}", stopped.text.contains("User rejected"))

        // A fresh read (what the row shows after an app restart) says the same.
        val reread = StoreStages.stage(ctx, app())
        assertEquals("error", reread.id); assertEquals("install", reread.failedAt)

        // The user takes over with Install: from the cache, no network fetch.
        sheet(accept = true)
        val installed = StoreStages.install(ctx, app())
        assertEquals("Install after a cancel reuses the cache", 1, assetGets.get())
        assertEquals("installed", installed.id)
        assertEquals("the installed row offers Clear while the APK is still cached", listOf("clear"), installed.actions)

        val cleared = StoreStages.clear(ctx, app())
        assertTrue("Clear deletes the cached APK", !cached.file.exists())
        assertEquals("installed", cleared.id)
        assertEquals(null, cleared.cached)
    }

    @Test
    fun `auto chain runs all three stages when nothing fails`() {
        sheet(accept = true)
        val s = StoreStages.auto(ctx, app())
        assertEquals(1, sheets.get())
        assertEquals("installed", s.id)
        assertEquals("the chain clears after a proven install", null, StoreStages.cachedFor(ctx, app()))
        assertEquals(null, s.failedAt)
    }

    @Test
    fun `control - a cancelled sheet does NOT read as installed`() {
        sheet(accept = false)
        StoreStages.auto(ctx, app())
        assertTrue("landed must be false when nothing was installed",
            StoreStages.cachedFor(ctx, app())!!.let { !ApkCache.landed(ctx, it, pkg) })
    }

    @Test
    fun `a killed download shows as resumable and Download fetches only the rest`() {
        val half = apk.size / 2
        ApkCache.file(ctx, "fleet-lib-rootfs-test-release.apk.part").writeBytes(apk.copyOfRange(0, half))
        val before = StoreStages.stage(ctx, app())
        assertEquals(listOf("download"), before.actions)
        assertTrue("the row says the bytes are kept: ${before.text}", before.text.contains("kept"))
        val after = StoreStages.download(ctx, app())
        assertEquals((apk.size - half).toLong(), servedBytes.get())
        assertEquals("cached", after.id)
        assertEquals(listOf("install", "download", "clear"), after.actions)
    }

    @Test
    fun `the unattended pass holds an app at Install after a cancelled sheet, and only then`() {
        assertEquals("control: nothing noted, nothing held", null, Fleet.heldAtInstall(ctx, app()))
        val first = Fleet.download(ctx, app())
        cancelInstallSheet(first.file.absolutePath)
        assertEquals("held at install after the user cancelled", ApkCache.STAGE_INSTALL,
            Fleet.heldAtInstall(ctx, app())?.stage)
        // The user takes over: a successful Install clears the hold.
        sheet(accept = true)
        StoreStages.install(ctx, app())
        assertEquals("a finished install releases the hold", null, Fleet.heldAtInstall(ctx, app()))
    }

    // ── #780 a cached APK never hides an update ──────────────────────────
    //
    // The phone's report: apps with an update showed no usable Download — the
    // row offered only Clear. stage() returned "installed · Clear" for any
    // landed cache BEFORE reading the remote, so the last update's leftover APK
    // hid the next one. Each case below fails on that code; each pair is the
    // other's control (same setup, one fact flipped, the verdict must flip).

    /** A distinct real zip per build: Install re-verifies it as an APK. */
    private fun bytesOf(tag: String): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write("$pkg $tag".toByteArray()); z.closeEntry() }
    }.toByteArray()

    private fun installedAt(code: Long, bytes: ByteArray) {
        val src = java.io.File(ctx.filesDir, "installed-$pkg-$code.apk").apply { writeBytes(bytes) }
        shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg; versionName = "$code"; longVersionCode = code
            applicationInfo = ApplicationInfo().apply { packageName = pkg; sourceDir = src.absolutePath }
        })
    }

    /** A cached build WITH its download record, as Fleet.download leaves a real APK. */
    private fun cachedAt(code: Long, bytes: ByteArray) =
        ApkCache.file(ctx, "fleet-lib-rootfs-test-v$code-${hex(bytes).take(6)}.apk").apply {
            writeBytes(bytes)
            java.io.File(parentFile, "$name.record").writeText("$pkg\n$code\n${hex(bytes)}\n")
        }

    private fun remoteIs(bytes: ByteArray) =
        Fleet.State.UpdateAvailable("2", hex(bytes).take(12), bytes.size.toLong(), source = "release")

    @Test
    fun `780 installed v1, cached v1 landed (same bytes), remote v2 = update_available + Download, cache auto-cleared`() {
        val v1 = bytesOf("v1"); installedAt(1, v1)
        val f = cachedAt(1, v1)
        val s = StoreStages.stage(ctx, app(), remoteIs(bytesOf("v2")))
        assertEquals("update_available", s.id)
        assertEquals("Download, and nothing to Clear: the landed cache cleared itself", listOf("download"), s.actions)
        assertFalse("a cache byte-identical to the installed APK is reaped without a tap", f.exists())
    }

    @Test
    fun `780 installed v1, cached v1 landed (other bytes), remote v2 = update_available, Clear only as an extra`() {
        installedAt(1, bytesOf("v1"))
        val f = cachedAt(1, bytesOf("v1-rebuild"))
        val s = StoreStages.stage(ctx, app(), remoteIs(bytesOf("v2")))
        assertEquals("update_available", s.id)
        assertEquals(listOf("download", "clear"), s.actions)
        assertTrue("unproven bytes are never auto-deleted", f.exists())
    }

    @Test
    fun `780 installed v1, cached v2 = cached + Install first, Download still live`() {
        installedAt(1, bytesOf("v1"))
        val v2 = bytesOf("v2")
        val f = cachedAt(2, v2)
        for (remote in listOf(remoteIs(v2), null)) {
            val s = StoreStages.stage(ctx, app(), remote)
            assertEquals("cached", s.id)
            assertEquals(listOf("install", "download", "clear"), s.actions)
            assertEquals(f.canonicalPath, s.cached!!.file.canonicalPath)
        }
    }

    @Test
    fun `780 control - cached v2 superseded by remote v3 = update_available, the stale cache is not offered`() {
        installedAt(1, bytesOf("v1"))
        val f = cachedAt(2, bytesOf("v2"))
        ApkCache.note(ctx, pkg, ApkCache.STAGE_INSTALL, "User rejected permissions")
        val s = StoreStages.stage(ctx, app(), remoteIs(bytesOf("v3")))
        assertEquals("update_available", s.id)
        assertEquals(listOf("download", "clear"), s.actions)
        assertEquals(null, s.cached)
        assertTrue(f.exists())
        assertEquals("a note about a superseded build must not hold the unattended pass",
            null, Fleet.heldAtInstall(ctx, app()))
    }

    @Test
    fun `780 installed v2, cached v2 landed, remote v2 = installed + Clear (other bytes) or nothing (same bytes)`() {
        val v2 = bytesOf("v2"); installedAt(2, v2)
        val other = cachedAt(2, bytesOf("v2-rebuild"))
        val up = Fleet.State.Installed("2", 2L, hex(v2).take(12), v2.size.toLong())
        val s = StoreStages.stage(ctx, app(), up)
        assertEquals("installed", s.id); assertEquals(listOf("clear"), s.actions)
        ApkCache.drop(other)
        val same = cachedAt(2, v2)
        val t = StoreStages.stage(ctx, app(), up)
        assertEquals("installed", t.id); assertEquals(emptyList<String>(), t.actions)
        assertFalse(same.exists())
    }

    @Test
    fun `780 auto chain downloads past a landed cache instead of reinstalling it`() {
        sheet(accept = true)
        installedAt(1, bytesOf("v1"))
        cachedAt(1, bytesOf("v1-rebuild"))
        StoreStages.auto(ctx, app())
        assertEquals("a landed cache must not stand in for the download", 1, assetGets.get())
    }

    @Test
    fun `780 control - auto chain installs an actionable cache without the network`() {
        sheet(accept = false)
        installedAt(1, bytesOf("v1"))
        cachedAt(2, bytesOf("v2"))
        StoreStages.auto(ctx, app())
        assertEquals(0, assetGets.get())
        assertEquals(1, sheets.get())
    }
}
