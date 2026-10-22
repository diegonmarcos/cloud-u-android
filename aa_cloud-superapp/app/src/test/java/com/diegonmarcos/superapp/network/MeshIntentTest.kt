package com.diegonmarcos.superapp.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1120 The owner's intent as it is stored: connected by default, Disconnect sticky until Connect, the start-up switches kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeshIntentTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `a fresh install wants the mesh connected, every condition on`() {
        assertEquals(MeshIntent.Policy(wanted = true, onBoot = true, onWifi = true, onMobile = true), MeshIntent.policy(ctx))
    }

    @Test fun `Disconnect sticks across reads until Connect`() {
        MeshIntent.setWanted(ctx, false)
        assertFalse(MeshIntent.wanted(ctx)); assertFalse(MeshIntent.policy(ctx).wanted)
        MeshIntent.setWanted(ctx, true)
        assertTrue(MeshIntent.wanted(ctx))
    }

    @Test fun `each start-up switch is stored on its own`() {
        MeshIntent.setOnBoot(ctx, false)
        assertEquals(MeshIntent.Policy(onBoot = false), MeshIntent.policy(ctx))
        MeshIntent.setOnWifi(ctx, false); MeshIntent.setOnMobile(ctx, false)
        assertEquals(MeshIntent.Policy(onBoot = false, onWifi = false, onMobile = false), MeshIntent.policy(ctx))
    }

    @Test fun `the Guest profile's forced tunnel-down is a Disconnect the supervisor respects`() {
        WgState.requestTunnelDown(ctx)
        assertFalse(MeshIntent.wanted(ctx))
    }
}
