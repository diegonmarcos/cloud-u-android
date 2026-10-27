package com.diegonmarcos.cloudkgstore

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneAppsTest {
    private val apps = listOf(
        PhoneApp("com.android.settings", "Settings", "15", 35, true, ""),
        PhoneApp("org.mozilla.firefox", "Firefox", "130.0", 2015, false, "com.android.vending"),
        PhoneApp("com.diegonmarcos.cloudkgstore", "Cloud KG Store", "0.1.0-dev", 3000100, false, "com.diegonmarcos.cloudstore"),
        PhoneApp("a.b.c", "alpha", "1", 1, false, ""),
    )

    @Test fun userAppsAreListedByLabelAndSystemAppsOnlyOnRequest() {
        assertEquals(listOf("alpha", "Cloud KG Store", "Firefox"), PhoneApps.filter(apps, "", false).map { it.label })
        assertEquals(listOf("alpha", "Cloud KG Store", "Firefox", "Settings"), PhoneApps.filter(apps, "", true).map { it.label })
    }

    @Test fun theQueryMatchesLabelOrPackageIgnoringCase() {
        assertEquals(listOf("Firefox"), PhoneApps.filter(apps, " MOZILLA ", true).map { it.label })
        assertEquals(listOf("Settings"), PhoneApps.filter(apps, "settings", true).map { it.label })
        assertEquals(emptyList<String>(), PhoneApps.filter(apps, "settings", false).map { it.label })
        assertEquals(emptyList<String>(), PhoneApps.filter(apps, "nothing-installed-like-this", true))
    }

    @Test fun permissionsShowGrantedFirstWithoutTheCommonPrefix() {
        val p = listOf(
            PhoneDetail.Permission("android.permission.CAMERA", false),
            PhoneDetail.Permission("android.permission.INTERNET", true),
            PhoneDetail.Permission("com.x.CUSTOM", true),
        )
        assertEquals(listOf("INTERNET", "com.x.CUSTOM", "CAMERA"), PhoneApps.orderedPermissions(p).map { PhoneApps.shortName(it.name) })
    }
}
