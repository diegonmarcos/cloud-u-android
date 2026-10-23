package com.diegonmarcos.cloudstore

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import com.diegonmarcos.superapp.appstore.UnpackAfterInstall
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1121 After Cloud Store installs the Termux terminal or Nix-on-Droid it starts that app's
 * UnpackService explicitly, falls back to launching the app, and keeps the result for the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UnpackAfterInstallTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val defaultStarter = UnpackAfterInstall.starter
    private val defaultLauncher = UnpackAfterInstall.launcher
    private val defaultSender = UnpackAfterInstall.sender
    private val defaultRetries = UnpackAfterInstall.startRetries
    private val defaultDelay = UnpackAfterInstall.retryDelayMs

    @After fun restore() {
        UnpackAfterInstall.starter = defaultStarter
        UnpackAfterInstall.launcher = defaultLauncher
        UnpackAfterInstall.sender = defaultSender
        UnpackAfterInstall.startRetries = defaultRetries
        UnpackAfterInstall.retryDelayMs = defaultDelay
    }

    /** The fallback tests below run with no retry window; the retry itself is proven in aRefusedStartIsRetriedUntilAndroidAllowsIt. */
    private fun noRetry() { UnpackAfterInstall.startRetries = 0 }

    @Test fun aRefusedStartIsRetriedUntilAndroidAllowsIt() {
        var tries = 0; var launched = false
        UnpackAfterInstall.retryDelayMs = 10
        UnpackAfterInstall.starter = { _, _ -> ++tries >= 3 }
        UnpackAfterInstall.launcher = { _, _ -> launched = true; true }
        UnpackAfterInstall.run(app, "cld.termux")
        assertEquals(1, tries)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        assertEquals(3, tries)
        assertFalse("a start that Android allowed on a retry never opens the app", launched)
        assertEquals(UnpackAfterInstall.UNPACKING, UnpackAfterInstall.resultOf(app, "cld.termux"))
    }

    @Test fun aStartRefusedForTheWholeWindowOpensTheApp() {
        var tries = 0; var launched = false
        UnpackAfterInstall.startRetries = 2; UnpackAfterInstall.retryDelayMs = 10
        UnpackAfterInstall.starter = { _, _ -> tries++; false }
        UnpackAfterInstall.launcher = { _, _ -> launched = true; true }
        UnpackAfterInstall.run(app, "cld.termux")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertEquals(3, tries)
        assertTrue(launched)
        assertEquals("opened to unpack", UnpackAfterInstall.resultOf(app, "cld.termux"))
    }

    @Test fun onlyTheTwoTerminalsApply() {
        assertTrue(UnpackAfterInstall.applies("cld.termux"))
        assertTrue(UnpackAfterInstall.applies("cld.termux.nix"))
        assertFalse(UnpackAfterInstall.applies("com.diegonmarcos.cloudstore"))
        assertFalse(UnpackAfterInstall.applies("com.termux"))
    }

    @Test fun otherPackagesStartNothing() {
        var started = 0
        UnpackAfterInstall.starter = { _, _ -> started++; true }
        UnpackAfterInstall.run(app, "com.diegonmarcos.cloudmail")
        assertEquals(0, started)
        assertNull(UnpackAfterInstall.resultOf(app, "com.diegonmarcos.cloudmail"))
    }

    @Test fun startsTheUnpackServiceExplicitlyWithAResultReceiver() {
        val sent = ArrayList<Intent>()
        UnpackAfterInstall.starter = { _, i -> sent += i; true }
        UnpackAfterInstall.run(app, "cld.termux.nix")
        assertEquals(1, sent.size)
        val i = sent[0]
        assertEquals("cld.termux.nix", i.component!!.packageName)
        assertEquals("com.termux.app.UnpackService", i.component!!.className)
        assertEquals("com.diegonmarcos.cloud.action.UNPACK", i.action)
        assertTrue(i.hasExtra(UnpackAfterInstall.EXTRA_RECEIVER))
        assertEquals(UnpackAfterInstall.UNPACKING, UnpackAfterInstall.resultOf(app, "cld.termux.nix"))
    }

    @Test fun theAppsAnswerBecomesTheResult() {
        var receiver: ResultReceiver? = null
        // Robolectric's Parcel does not carry a binder across, so the reply is read here without the parcel round trip;
        // that round trip is proven across real processes by test-store-unpack-cross-process.yml.
        UnpackAfterInstall.sender = { it }
        UnpackAfterInstall.starter = { _, i ->
            receiver = i.getParcelableExtra(UnpackAfterInstall.EXTRA_RECEIVER); true
        }
        UnpackAfterInstall.run(app, "cld.termux")
        receiver!!.send(0, Bundle())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("unpacked", UnpackAfterInstall.resultOf(app, "cld.termux"))

        UnpackAfterInstall.run(app, "cld.termux")
        receiver!!.send(1, Bundle().apply { putString("error", "the rootfs library is not installed") })
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("unpack failed: the rootfs library is not installed", UnpackAfterInstall.resultOf(app, "cld.termux"))
    }

    @Test fun anUnstartableServiceFallsBackToLaunchingTheApp() {
        var launched: String? = null
        noRetry()
        UnpackAfterInstall.starter = { _, _ -> false }
        UnpackAfterInstall.launcher = { _, pkg -> launched = pkg; true }
        UnpackAfterInstall.run(app, "cld.termux")
        assertEquals("cld.termux", launched)
        assertEquals("opened to unpack", UnpackAfterInstall.resultOf(app, "cld.termux"))
    }

    @Test fun neitherWorkingIsReportedNotHidden() {
        noRetry()
        UnpackAfterInstall.starter = { _, _ -> false }
        UnpackAfterInstall.launcher = { _, _ -> false }
        UnpackAfterInstall.run(app, "cld.termux")
        assertTrue(UnpackAfterInstall.resultOf(app, "cld.termux")!!.startsWith("unpack failed"))
    }

    @Test fun theErrorTextNeverComesBackEmpty() {
        assertEquals("unpack failed: unknown error", UnpackAfterInstall.text(1, null))
        assertEquals("unpack failed: unknown error", UnpackAfterInstall.text(1, " "))
    }

    /** #1121 the phone crash: the terminal cannot load a Store subclass of ResultReceiver. */
    @Test fun receiverForSendingIsAPlainResultReceiverThatForwards() {
        var got: Pair<Int, String?>? = null
        class Mine : ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, data: Bundle?) { got = resultCode to data?.getString("error") }
        }
        val plain = UnpackAfterInstall.receiverForSending(Mine())
        assertEquals(ResultReceiver::class.java, plain.javaClass)
        assertTrue(plain.javaClass != Mine::class.java)
        // Forwarding needs a real binder (Robolectric's Parcel drops it): test-store-unpack-cross-process.yml sends
        // from the terminal's process and asserts the Store's record.
        assertNull(got)
    }

    @Test fun theIntentCarriesAPlainResultReceiver() {
        val sent = ArrayList<Intent>()
        UnpackAfterInstall.starter = { _, i -> sent += i; true }
        UnpackAfterInstall.run(app, "cld.termux")
        assertEquals(ResultReceiver::class.java, sent[0].getParcelableExtra<ResultReceiver>(UnpackAfterInstall.EXTRA_RECEIVER)!!.javaClass)
    }
}
