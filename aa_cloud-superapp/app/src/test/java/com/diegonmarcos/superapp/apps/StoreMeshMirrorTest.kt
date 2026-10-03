package com.diegonmarcos.superapp.apps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.source.DownloadFailure
import com.diegonmarcos.superapp.updater.source.MeshMirror
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * #837 the third download leg: the fleet's git-proxy-api mirrors the release
 * asset on the mesh-private name, so the Store can install while github.com /
 * ghcr.io do not resolve. URL mapping, the REQUIRED sha256 sidecar, digest
 * verification and the #831 DNS classification are pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreMeshMirrorTest {

    @After fun reset() { MeshMirror.base = MeshMirror.BASE }

    private val apk: ByteArray = ByteArrayOutputStream().also { bo ->
        ZipOutputStream(bo).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(ByteArray(64) { it.toByte() }); z.closeEntry()
        }
    }.toByteArray()
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    /** A stub mirror: path -> (status, body); records each request path. */
    private fun mirror(routes: Map<String, Pair<Int, ByteArray>>, seen: MutableList<String>): ServerSocket {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                s.use {
                    val r = it.getInputStream().bufferedReader()
                    val path = r.readLine()?.split(' ')?.getOrNull(1) ?: ""
                    while (r.readLine()?.isNotEmpty() == true) {}
                    seen += path
                    val (code, body) = routes[path] ?: (404 to """{"error":"x","code":"not_found"}""".toByteArray())
                    it.getOutputStream().apply {
                        write("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            }
        }
        return ss
    }

    private fun app(id: String, asset: String) = Fleet.App(
        id = id, label = id, pkg = "com.example.$id", altId = null,
        registry = "ghcr.io", namespace = "diegonmarcos", image = id, tag = "latest",
        asset = asset, assets = emptyMap(),
        releaseUrl = "https://github.com/diegonmarcos/cloud-u-android/releases/download/latest/$asset",
        repoUrl = "", ghcrPage = "", blocked = false, kind = "lib",
    )

    @Test fun `the mesh leg is tried third, after release and ghcr`() {
        assertEquals("mesh", Fleet.sourceOrder.last())
        assertEquals(listOf("release", "ghcr"), Fleet.sourceOrder.take(2))
    }

    @Test fun `a GitHub release-download URL maps onto git-proxy-api's mesh name and port`() {
        assertEquals(
            "http://git-proxy-api.app:8123/releases/diegonmarcos/cloud-u-android/latest/assets/Cloud-Lib-Wallet.apk",
            MeshMirror.urlFor("https://github.com/diegonmarcos/cloud-u-android/releases/download/latest/Cloud-Lib-Wallet.apk"))
        assertNull(MeshMirror.urlFor("https://example.com/a.apk"))
        assertNull(MeshMirror.urlFor(""))
    }

    @Test fun `the sidecar parses bare and sha256sum forms, and nothing else`() {
        val h = "a".repeat(64)
        assertEquals(h, MeshMirror.parseSha256(h))
        assertEquals(h, MeshMirror.parseSha256("${h.uppercase()}  Cloud-Lib-Wallet.apk\n"))
        assertNull(MeshMirror.parseSha256("not a digest"))
        assertNull(MeshMirror.parseSha256(null))
    }

    @Test fun `fetch downloads from the mirror and verifies against the mirror's sidecar`() {
        val seen = CopyOnWriteArrayList<String>()
        val p = "/releases/diegonmarcos/cloud-u-android/latest/assets/Cloud-Lib-Mesh.apk"
        mirror(mapOf(p to (200 to apk), "$p.sha256" to (200 to "$apkSha  Cloud-Lib-Mesh.apk\n".toByteArray())), seen).use { ss ->
            MeshMirror.base = "http://127.0.0.1:${ss.localPort}"
            val got = MeshMirror.fetch(ApplicationProvider.getApplicationContext(), app("lib-mesh", "Cloud-Lib-Mesh.apk"))
            assertNotNull(got)
            assertTrue(got!!.file.readBytes().contentEquals(apk))
            assertEquals("the sidecar is read BEFORE the bytes", listOf("$p.sha256", p), seen.toList())
        }
    }

    @Test fun `bytes that do not match the sidecar are refused and dropped`() {
        val p = "/releases/diegonmarcos/cloud-u-android/latest/assets/Cloud-Lib-Bad.apk"
        mirror(mapOf(p to (200 to apk), "$p.sha256" to (200 to "b".repeat(64).toByteArray())), CopyOnWriteArrayList()).use { ss ->
            MeshMirror.base = "http://127.0.0.1:${ss.localPort}"
            val ctx = ApplicationProvider.getApplicationContext<Application>()
            try {
                MeshMirror.fetch(ctx, app("lib-bad", "Cloud-Lib-Bad.apk")); fail("must refuse")
            } catch (e: IllegalStateException) {
                assertTrue(e.message, e.message!!.contains("failed verification"))
            }
        }
    }

    @Test fun `no sidecar on the mirror means no download - never a size-only fallback`() {
        val seen = CopyOnWriteArrayList<String>()
        val p = "/releases/diegonmarcos/cloud-u-android/latest/assets/Cloud-Lib-NoSha.apk"
        mirror(mapOf(p to (200 to apk)), seen).use { ss ->
            MeshMirror.base = "http://127.0.0.1:${ss.localPort}"
            val t = runCatching { MeshMirror.fetch(ApplicationProvider.getApplicationContext(), app("lib-nosha", "Cloud-Lib-NoSha.apk")) }
                .exceptionOrNull()
            assertNotNull(t)
            assertEquals(DownloadFailure.Kind.NOT_PUBLISHED, DownloadFailure.kind(t!!))
            assertEquals("the APK itself was never fetched", listOf("$p.sha256"), seen.toList())
        }
    }

    @Test fun `an unresolvable mesh name is reported as DNS, naming that host`() {
        MeshMirror.base = "http://git-proxy-api.invalid:8123"
        val t = runCatching { MeshMirror.fetch(ApplicationProvider.getApplicationContext(), app("lib-dns", "Cloud-Lib-Dns.apk")) }
            .exceptionOrNull()
        assertNotNull(t)
        assertEquals(DownloadFailure.Kind.DNS, DownloadFailure.kind(t!!))
        assertTrue(DownloadFailure.describe(t, "10.9.0.1"), DownloadFailure.describe(t, "10.9.0.1")
            .startsWith("DNS: cannot resolve git-proxy-api.invalid"))
    }

    @Test fun `guard - cleartext is allowed for the mesh mirror's exact name, not the whole app TLD`() {
        val f = File("src/main/res/xml/network_security_config.xml").takeIf { it.isFile } ?: return
        val s = f.readText()
        assertTrue(s.contains("<domain>git-proxy-api.app</domain>"))
        assertTrue(!s.contains(">app</domain>"))
    }
}
