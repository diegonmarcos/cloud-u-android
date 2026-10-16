package com.diegonmarcos.superapp.core

import com.diegonmarcos.superapp.core.StoreNotifyPrefs.Channel
import com.diegonmarcos.superapp.core.StoreNotifyPrefs.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreNotifyPrefsTest {

    @Test fun everythingIsOnUntilSwitchedOff() {
        for (k in Kind.values()) for (c in Channel.values()) assertTrue("$k/$c", StoreNotifyPrefs.allows(emptySet(), k, c))
    }

    @Test fun aSwitchNarrowsOnlyItsOwnKindAndChannel() {
        val off = setOf(StoreNotifyPrefs.key(Kind.INSTALL_DONE, Channel.PUSH))
        assertFalse(StoreNotifyPrefs.allows(off, Kind.INSTALL_DONE, Channel.PUSH))
        assertTrue(StoreNotifyPrefs.allows(off, Kind.INSTALL_DONE, Channel.IN_APP))
        assertTrue(StoreNotifyPrefs.allows(off, Kind.FAILURES, Channel.PUSH))
        assertTrue(StoreNotifyPrefs.allows(off, Kind.UPDATE_AVAILABLE, Channel.PUSH))
    }

    @Test fun keysAreDistinctPerKindAndChannel() {
        val keys = Kind.values().flatMap { k -> Channel.values().map { StoreNotifyPrefs.key(k, it) } }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(6, keys.size)
    }

    @Test fun theStoreDeclaresTheThreeKindsTheOwnerAskedFor() {
        assertEquals(listOf("update-available", "install-done", "failures"), Kind.values().map { it.id })
    }

    @Test fun theSuperAppStaysClosedWhateverIsStored() {
        assertFalse(StoreNotifyGate.mayPost(StoreNotifyGate.SUPERAPP_PKG))
        assertTrue(StoreNotifyGate.mayPost(StoreNotifyGate.STORE_PKG))
    }
}
