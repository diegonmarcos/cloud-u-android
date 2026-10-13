package com.diegonmarcos.superapp.extinstall

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * STRUCTURAL: the extapp install progress reuses the fleet's install engine
 * (libs:updater ApkInstallWorker via Updater.installApk) and the Cloud Store's
 * per-job state (libs:appstore StoreJobs.board) instead of a second installer.
 */
class ExtInstallReuseTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/AndroidManifest.xml").isFile }
    private val main = File(root, "app/src/main/java/com/diegonmarcos/superapp")
    private fun src(p: String) = File(main, p).readText()

    @Test fun storeIsTheAppstoreBoard_andEngineIsTheUpdater() {
        val s = src("extinstall/ExtAppInstalls.kt")
        assertTrue(s.contains("ExtInstallTracker(StoreJobs.board)"))
        assertTrue(s.contains("Updater.installApk("))
        assertTrue(s.contains("UpdateProgress.addObserver("))
    }

    @Test fun noSecondDownloaderOrInstaller() {
        val all = File(main, "extinstall").listFiles()!!.joinToString("\n") { it.readText() }
        for (banned in listOf("HttpURLConnection", "PackageInstaller", "openConnection(", "OkHttp", "Download.toFile"))
            assertFalse("extinstall must not carry its own $banned", all.contains(banned))
    }

    @Test fun everyMissingAppInstallGoesThroughTheTrackedPath() {
        val shell = src("ShellActivity.kt")
        val launch = shell.substringAfter("private fun launchExternalApp(").substringBefore("// ── tile click dispatch")
        assertTrue(launch.contains("ExtAppInstalls.start("))
        assertTrue(launch.contains("ExtTileRing.handleBusyTap("))
        assertFalse("no untracked install", launch.contains("Updater.installApk("))
        assertFalse(src("launcher/AppInstall.kt").contains("Updater.installApk("))
        assertFalse(src("floatingnav/FloatingNavService.kt").contains("Updater.installApk("))
    }

    @Test fun tilesWearTheRing() {
        for (f in listOf("launcher/TileGridFragment.kt", "launcher/HomeGroupedFragment.kt", "launcher/GroupedTilesFragment.kt"))
            assertTrue(f, src(f).contains("ExtTileRing.attach("))
    }

    @Test fun notificationIsQuietAndThrottled() {
        val n = src("extinstall/ExtInstallNotifier.kt")
        assertTrue(n.contains("IMPORTANCE_LOW"))
        assertTrue(n.contains("SilentChannels.quiet("))
        assertTrue(n.contains("setOnlyAlertOnce(true)"))
        assertTrue(n.contains("PostThrottle("))
        assertTrue(File(root, "app/src/main/AndroidManifest.xml").readText().contains(".extinstall.ExtInstallActionReceiver"))
    }
}
