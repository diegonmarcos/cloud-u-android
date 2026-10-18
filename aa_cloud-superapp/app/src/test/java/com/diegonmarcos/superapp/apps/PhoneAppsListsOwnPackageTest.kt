package com.diegonmarcos.superapp.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cloud > All apps must list the SuperApp itself (its own package used to be dropped). */
class PhoneAppsListsOwnPackageTest {
    private val own = "com.diegonmarcos.superapp"

    @Test fun ownPackageIsListedWithoutProfileFilter() =
        assertTrue(PhoneAppsFragment.listsPackage(own, null))

    @Test fun ownPackageIsListedAmongOthers() {
        val listed = listOf(own, "com.android.chrome", "org.mozilla.firefox")
            .filter { PhoneAppsFragment.listsPackage(it, null) }
        assertTrue(own in listed)
        assertTrue(listed.size == 3)
    }

    @Test fun ownPackageIsListedWhenWhitelisted() =
        assertTrue(PhoneAppsFragment.listsPackage(own, setOf(own, "com.android.chrome")))

    @Test fun profileWhitelistStillCuts() =
        assertFalse(PhoneAppsFragment.listsPackage("com.other", setOf("com.android.chrome")))
}
