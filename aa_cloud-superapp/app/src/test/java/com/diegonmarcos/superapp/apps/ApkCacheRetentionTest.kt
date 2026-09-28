package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.BatchInstall
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.cache.ApkCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * #625 — THE CACHE KEEPS WHAT IS NOT INSTALLED, AND ONLY DROPS WHAT IS.
 *
 * Diego's report was "it downloads a file, the install goes bad, and it DELETES
 * the download — many times". The delete sites were all already conditional on
 * success; the thing actually removing the bytes was Android reclaiming
 * `cacheDir`, which it is entitled to do at any moment without asking. So the
 * four assertions here are the four ways that can regress, and each one is
 * written so that the OPPOSITE behaviour fails it:
 *
 * 1. a cached APK whose package is NOT installed survives a reap attempt — a
 *    mutation that deletes it turns this red;
 * 2. a cached APK the device confirms as installed is reaped — a mutation that
 *    keeps it forever turns this red;
 * 3. [BatchInstall] finishes every download before it starts any install — a
 *    mutation that interleaves them turns this red;
 * 4. the cache does NOT live under `cacheDir` — a mutation moving it back turns
 *    this red.
 *
 * The record sidecar is written here by hand rather than through
 * [ApkCache.keep], because Robolectric's PackageManager does not parse a real
 * APK archive off disk: what is under test is the retention GATE, and the gate
 * reads the record. The format is asserted by being used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApkCacheRetentionTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** A real zip in the real cache directory, with the download record
     *  [ApkCache.record] expects: pkg, versionCode, sha256, signing cert. */
    private fun cache(name: String, pkg: String, code: Long): File {
        val f = ApkCache.file(ctx, name)
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(pkg.toByteArray()); z.closeEntry()
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
        File(f.parentFile, f.name + ".record").writeText("$pkg\n$code\n$sha\n")
        return f
    }

    private fun install(pkg: String, code: Long) {
        shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg; versionName = "1.0"; longVersionCode = code
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        })
    }

    @Test
    fun `a download whose install did not land is KEPT, with the reason`() {
        val apk = cache("external-org.example.declined.apk", "org.example.declined", 42L)
        // Nothing installed: this is the declined dialog, the failed install and
        // the killed process, all of which used to end with the bytes gone.
        val r = ApkCache.reapIfInstalled(ctx, apk)
        assertTrue("a cached APK for a package that is not installed must survive: ${r.reason}",
            apk.exists())
        assertTrue("and the decision must be reported as a keep, with the reason",
            r is ApkCache.Retention.Kept)
        assertTrue("the reason must name the package that is not installed: ${r.reason}",
            r.reason.contains("org.example.declined"))
    }

    @Test
    fun `a download installed at a DIFFERENT versionCode is KEPT`() {
        val apk = cache("external-org.example.other.apk", "org.example.other", 42L)
        install("org.example.other", 41L)   // something else put an older build there
        val r = ApkCache.reapIfInstalled(ctx, apk)
        assertTrue("versionCode 41 installed is not the cached 42: ${r.reason}", apk.exists())
        assertTrue(r is ApkCache.Retention.Kept)
    }

    @Test
    fun `a download the device confirms as installed is reaped`() {
        val apk = cache("external-org.example.landed.apk", "org.example.landed", 77L)
        install("org.example.landed", 77L)
        val r = ApkCache.reapIfInstalled(ctx, apk)
        assertTrue("a verified install must clear its cached APK: ${r.reason}", !apk.exists())
        assertTrue("and say what it proved", r is ApkCache.Retention.Reaped)
        assertTrue("the record sidecar must go with it",
            !File(apk.parentFile, apk.name + ".record").exists())
        assertTrue("the proof must name package and versionCode, never merely 'status ok': ${r.reason}",
            r.reason.contains("org.example.landed") && r.reason.contains("77"))
    }

    @Test
    fun `a cached file that no longer hashes to what was downloaded is KEPT`() {
        val apk = cache("external-org.example.swapped.apk", "org.example.swapped", 5L)
        install("org.example.swapped", 5L)
        apk.appendBytes(byteArrayOf(0))     // the bytes changed under us
        val r = ApkCache.reapIfInstalled(ctx, apk)
        assertTrue("bytes that are not the download must not be treated as installed: ${r.reason}",
            apk.exists() && r is ApkCache.Retention.Kept)
    }

    @Test
    fun `the cache is NOT cacheDir`() {
        val dir = ApkCache.dir(ctx).canonicalPath
        assertFalse("the APK cache must not live under cacheDir — the OS reclaims it without " +
            "asking and Settings Clear cache wipes it, which IS #625 ($dir)",
            dir.startsWith(ctx.cacheDir.canonicalPath))
        assertTrue("it must live under the app's no-backup files dir ($dir)",
            dir.startsWith(ctx.noBackupFilesDir.canonicalPath))
        assertTrue("and a file handed out by the cache must be inside it",
            ApkCache.isOurs(ctx, cache("fleet-x-release.apk", "org.example.inside", 1L)))
    }

    @Test
    fun `every download finishes before any install starts`() {
        val calls = ArrayList<String>()
        val targets = listOf("a", "b", "c").map {
            BatchInstall.Target("org.example.$it", it, null, null)
        }
        val apk = VerifiedApk.structural(cache("batch-probe.apk", "org.example.probe", 1L))!!
        val engine = object : BatchInstall.Engine {
            override fun stage(ctx: Context, t: BatchInstall.Target): VerifiedApk {
                calls += "download:${t.label}"; return apk
            }
            override fun install(ctx: Context, t: BatchInstall.Target, a: VerifiedApk): String? {
                calls += "install:${t.label}"; return null
            }
        }
        val outcomes = BatchInstall.run(ctx, targets, engine)
        assertEquals("download-all-then-install-one-by-one, in that order",
            listOf("download:a", "download:b", "download:c",
                   "install:a", "install:b", "install:c"), calls)
        assertEquals("every app gets its own outcome", 3, outcomes.size)
        assertTrue("and each one says it was downloaded and installed",
            outcomes.all { it.downloaded && it.installed })
    }

    @Test
    fun `one dead download costs only its own app`() {
        val calls = ArrayList<String>()
        val targets = listOf("a", "bad", "c").map {
            BatchInstall.Target("org.example.$it", it, null, null)
        }
        val apk = VerifiedApk.structural(cache("batch-probe-2.apk", "org.example.probe", 1L))!!
        val engine = object : BatchInstall.Engine {
            override fun stage(ctx: Context, t: BatchInstall.Target): VerifiedApk {
                calls += "download:${t.label}"
                if (t.label == "bad") error("the vendor feed answered 404")
                return apk
            }
            override fun install(ctx: Context, t: BatchInstall.Target, a: VerifiedApk): String? {
                calls += "install:${t.label}"; return null
            }
        }
        val outcomes = BatchInstall.run(ctx, targets, engine)
        assertEquals(listOf("download:a", "download:bad", "download:c", "install:a", "install:c"), calls)
        val bad = outcomes.single { it.target.label == "bad" }
        assertFalse("the dead one is not reported as downloaded", bad.downloaded)
        assertTrue("and its reason survives: ${bad.message}",
            bad.message?.contains("404") == true)
        assertEquals("the other two still installed", 2, outcomes.count { it.installed })
    }
}
