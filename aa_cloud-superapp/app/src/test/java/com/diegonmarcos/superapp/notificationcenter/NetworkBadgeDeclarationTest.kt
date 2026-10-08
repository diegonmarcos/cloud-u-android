package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Network badge is in the declaration THIS BUILD SHIPS, and its off switch works. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NetworkBadgeDeclarationTest {

    private val badge get() = BadgeServices.declared.firstOrNull { it.id == NetworkBadgeService.BADGE_ID }

    @Test fun `the shipped declaration carries a persistent Network badge with its own channel`() {
        val b = badge
        assertTrue("network_mesh is not in the shipped declaration: ${BadgeServices.declared.map { it.id }}", b != null)
        b!!
        assertTrue(b.isBadge && b.persistent && b.enabled)
        assertEquals(NetworkBadgeService::class.java.name, b.service)
        assertEquals(NetworkBadgeService.CHANNEL_ID, b.channel)
        assertTrue("restarted after an update", BadgeDeclaration.restartServices(BadgeServices.declared).contains(b.service))
    }

    @Test fun `the owner can turn the badge off`() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val b = badge!!
        org.robolectric.Shadows.shadowOf(ctx).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(BadgeCustomization.isEnabled(ctx, b))
        assertTrue("wanted while on and granted", BadgeServices.wanted(ctx, b.service))
        BadgeCustomization.set(ctx, b, BadgeCustomization.KEY_ENABLED, false)
        assertFalse(BadgeCustomization.isEnabled(ctx, b))
        assertFalse("its service is no longer wanted", BadgeServices.wanted(ctx, b.service))
    }

    @Test fun `it asks for the notification grant it needs`() {
        assertTrue(badge!!.requires.contains("notifications"))
    }
}
