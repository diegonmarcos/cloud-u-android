package com.diegonmarcos.clouddrive.sync.store

import com.diegonmarcos.cloudlib.sharedstore.StorePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1315 ONE path form. The Git layer's models carry HOST paths; a script sees GUEST paths; the translation lives in StoreRoute
 * alone and is exact both ways, for both host aliases, and refuses an escape. Mutations proven red while writing this suite:
 * dropping the `/data/data` alias (aliasHostIsTheSameStore red), keeping `..` (escapeIsRefused red), host() without the root case
 * (rootMapsBothWays red).
 */
class StoreRouteTest {
    private val paths = StorePaths("cld.termux", "cloud-shared-store", "CloudDrive", "cloud-drive")
    private val exec = StoreRoute(StorePaths.Mode.EXEC, paths, 0)
    private val direct = StoreRoute(StorePaths.Mode.DIRECT, paths, 0)
    private val none = StoreRoute(StorePaths.Mode.NONE, paths, 0)
    private val host = "/data/user/0/cld.termux/files/home/cloud-shared-store"

    @Test fun `the store root maps both ways`() {
        assertEquals(host, exec.hostRoot)
        assertEquals("/root/cloud-shared-store", exec.guestRoot)
        assertEquals("/root/cloud-shared-store", exec.guest(host))
        assertEquals(host, exec.host("/root/cloud-shared-store"))
        assertEquals(host, exec.host("/root/cloud-shared-store/"))
    }

    @Test fun `a clone path maps both ways and a trailing slash does not matter`() {
        val h = "$host/git/cloudy-u-android"
        assertEquals("/root/cloud-shared-store/git/cloudy-u-android", exec.guest(h))
        assertEquals("/root/cloud-shared-store/git/cloudy-u-android", exec.guest("$h/"))
        assertEquals(h, exec.host("/root/cloud-shared-store/git/cloudy-u-android"))
        assertEquals(h, exec.host(exec.guestOrThrow(h)))
    }

    @Test fun `the data-data alias is the same store`() {
        val alias = "/data/data/cld.termux/files/home/cloud-shared-store/git/x"
        assertTrue(exec.inStore(alias))
        assertEquals("/root/cloud-shared-store/git/x", exec.guest(alias))
    }

    @Test fun `outside the store is outside`() {
        assertFalse(exec.inStore("/storage/emulated/0/CloudDrive/git/x"))
        assertNull(exec.guest("/storage/emulated/0/CloudDrive/git/x"))
        assertNull(exec.host("/storage/emulated/0/CloudDrive"))
        assertNull(exec.host("/root/other"))
        val thrown = runCatching { exec.guestOrThrow("/sdcard/x") }.exceptionOrNull()
        assertTrue("the error names the path", thrown?.message?.contains("/sdcard/x") == true)
    }

    @Test fun `an escape is refused in both directions`() {
        assertNull(exec.guest("$host/git/../../secret"))
        assertFalse(exec.inStore("$host/../other"))
        assertNull(exec.host("/root/cloud-shared-store/../etc"))
    }

    @Test fun `only NONE cannot run a script`() {
        assertTrue(exec.viaTermux)
        assertTrue(direct.viaTermux)
        assertFalse(none.viaTermux)
    }

    @Test fun `the route is the same for every mode`() {
        assertEquals(exec.guest("$host/git/a"), none.guest("$host/git/a"))
        assertEquals(direct.hostRoot, none.hostRoot)
    }
}
