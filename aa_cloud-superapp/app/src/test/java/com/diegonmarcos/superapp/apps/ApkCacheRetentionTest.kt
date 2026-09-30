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
 * #666 sharpened what "installed" is allowed to mean, and added the assertions
 * that pin it:
 *
 * 5. a same-versionCode REBUILD (same package, same versionCode, different
 *    bytes) is KEPT — this is the one that matters. Mutate
 *    [ApkCache.installedSha256] to return the download record's own digest and
 *    the comparison becomes the cache agreeing with itself, which passes
 *    unconditionally; this test goes red;
 * 6. [ApkCache.installedSha256] hashes `sourceDir`, asserted against the
 *    installed bytes directly;
 * 7. when those bytes cannot be read the fallback decides but NAMES itself as
 *    weaker evidence — a mutation that claims a digest it never read goes red;
 * 8. the manual clear reports MEASURED counts and bytes and leaves the unproven
 *    entry alone — a mutation to a constant, or one that lets
 *    [ApkCache.clearRedundant] take it, goes red.
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

    /**
     * [from] becomes the installed package's `sourceDir` — the APK Android is
     * actually running, which is what [ApkCache.installedSha256] hashes. Null
     * leaves it unset, standing in for the device that will not give those bytes
     * up (a split install, an unopenable path) so the weaker fallback is
     * exercised too.
     */
    private fun install(pkg: String, code: Long, from: File? = null) {
        val src = from?.let { f ->
            File(ctx.filesDir, "installed-$pkg.apk").apply { parentFile?.mkdirs(); writeBytes(f.readBytes()) }
        }
        shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg; versionName = "1.0"; longVersionCode = code
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg; sourceDir = src?.absolutePath
            }
        })
    }

    /** A file of [bytes] standing in for an installed APK that is NOT the
     *  download — a rebuild of the same version, which is the case versionCode
     *  equality cannot see. */
    private fun installDifferent(pkg: String, code: Long, bytes: ByteArray) {
        val src = File(ctx.filesDir, "installed-$pkg.apk").apply {
            parentFile?.mkdirs(); writeBytes(bytes)
        }
        shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg; versionName = "1.0"; longVersionCode = code
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg; sourceDir = src.absolutePath
            }
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
        // The installed package IS these bytes, so its digest matches.
        install("org.example.landed", 77L, from = apk)
        val r = ApkCache.reapIfInstalled(ctx, apk)
        assertTrue("a verified install must clear its cached APK: ${r.reason}", !apk.exists())
        assertTrue("and say what it proved", r is ApkCache.Retention.Reaped)
        assertTrue("the record sidecar must go with it",
            !File(apk.parentFile, apk.name + ".record").exists())
        assertTrue("the proof must name package and versionCode, never merely 'status ok': ${r.reason}",
            r.reason.contains("org.example.landed") && r.reason.contains("77"))
        assertTrue("and the proof must be the DIGEST, not a versionCode match: ${r.reason}",
            r.reason.contains("byte-for-byte"))
    }

    /**
     * #666 THE ASSERTION THAT CARRIES THE TICKET.
     *
     * Same package, same versionCode, DIFFERENT bytes — a rebuild of the same
     * version, which is precisely what versionCode equality cannot see and what
     * #517/#518 are a history of getting wrong. The cache must survive it.
     *
     * This is also the mutation test for "read the installed sha from the
     * INSTALLED PACKAGE": make [ApkCache.installedSha256] return the download
     * record's own digest instead of hashing `sourceDir`, and the comparison is
     * the cache agreeing with itself, it passes unconditionally, the APK is
     * reaped and this test goes red.
     */
    @Test
    fun `a same-versionCode REBUILD is not a match, and the cache is KEPT`() {
        val apk = cache("external-org.example.rebuilt.apk", "org.example.rebuilt", 42L)
        installDifferent("org.example.rebuilt", 42L, "a different build of version 42".toByteArray())
        val r = ApkCache.reapIfInstalled(ctx, apk)
        assertTrue("the installed package's bytes are not the download's, so the only copy of " +
            "the download must survive: ${r.reason}", apk.exists())
        assertTrue(r is ApkCache.Retention.Kept)
        assertTrue("and #233: it must SAY the digests differ, not merely decline: ${r.reason}",
            r.reason.contains("DIFFERENT bytes"))
    }

    /** The installed digest read straight, so a mutation cannot hide behind the
     *  retention gate's other clauses. */
    @Test
    fun `installedSha256 reads the installed package, not the download record`() {
        val apk = cache("external-org.example.read.apk", "org.example.read", 9L)
        installDifferent("org.example.read", 9L, "installed bytes".toByteArray())
        val expected = MessageDigest.getInstance("SHA-256").digest("installed bytes".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("it must hash what is INSTALLED", expected,
            ApkCache.installedSha256(ctx, "org.example.read"))
        assertFalse("and that must not be the cached artifact's own digest",
            expected == ApkCache.record(apk)!!.sha256)
    }

    /** When the device will not hand over the installed bytes, the decision still
     *  happens — on the older evidence, and saying so rather than claiming a
     *  digest it never read. */
    @Test
    fun `an unreadable installed APK falls back, and NAMES the weaker evidence`() {
        val apk = cache("external-org.example.split.apk", "org.example.split", 3L)
        install("org.example.split", 3L)            // no sourceDir: cannot be hashed
        val r = ApkCache.reapIfInstalled(ctx, apk)
        assertTrue("the fallback must still decide: ${r.reason}", r is ApkCache.Retention.Reaped)
        assertTrue("and must not pretend it compared digests: ${r.reason}",
            r.reason.contains("weaker evidence"))
    }

    /**
     * #666 the manual button reports MEASURED counts and bytes, and refuses to
     * take the only copy of an unproven download.
     *
     * Mutation: return a constant from [ApkCache.plan], or let
     * [ApkCache.clearRedundant] delete the unproven entry, and this goes red.
     */
    @Test
    fun `the manual clear measures what it frees and keeps the unproven`() {
        val done = cache("external-org.example.done.apk", "org.example.done", 11L)
        install("org.example.done", 11L, from = done)
        val open = cache("external-org.example.open.apk", "org.example.open", 12L)   // not installed
        // Read before the reap: after it, done.length() is 0 and would agree with
        // anything.
        val doneBytes = done.length()
        val openBytes = open.length()
        val plan = ApkCache.plan(ctx)
        assertEquals("exactly the one provably-installed APK is free to take", 1, plan.redundant.size)
        assertEquals("and the unproven one is counted as kept", 1, plan.kept.size)
        assertEquals("the bytes must be the file's real length, never an estimate",
            doneBytes, plan.redundantBytes)
        assertEquals(openBytes, plan.keptBytes)
        val e = ApkCache.clearRedundant(ctx)
        assertFalse("the redundant one goes", done.exists())
        assertTrue("the only copy of an unfinished install STAYS — #625 must not come " +
            "back through the manual button", open.exists())
        assertEquals("and the report is the real freed byte count", doneBytes, e.freedBytes)
        assertEquals(listOf(done.name), e.deleted)
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
