package app.sterna.ui.settings

import com.diegonmarcos.superapp.updater.VersionOrder
import com.diegonmarcos.superapp.updater.VersionOrder.Order
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The downgrade refusal, driven through every branch it has.
 *
 * This is a BEHAVIOUR test, not a source lint: it calls the same function
 * `Fleet.commit` calls before it stages an APK. That guard is what stands
 * between the owner and #17 ("superapp installs a STALE APK causing
 * downgrade") - an update pass that walks the phone backwards - and until this
 * file existed nothing in the repository had ever executed it, because reaching
 * it needs a Context, a live PackageManager and a staged file.
 *
 * All four cases, because a comparison tested only on the case it was written
 * for is a comparison tested on nothing: NEWER, SAME, OLDER, and the two
 * unreadable ones.
 */
class VersionOrderTest {

    @Test fun `a higher version code is an update`() {
        assertEquals(Order.NEWER, VersionOrder.compare(3_400_000L, 3_355_358L))
        assertFalse(
            "a build NEWER than the installed one must install; refusing it is an app that " +
                "can never be updated again",
            VersionOrder.isDowngrade(3_400_000L, 3_355_358L),
        )
    }

    @Test fun `the same version code is not a downgrade`() {
        assertEquals(Order.SAME, VersionOrder.compare(3_355_358L, 3_355_358L))
        assertFalse(
            "re-installing the SAME versionCode is how a damaged install is repaired. Refusing " +
                "it would leave a corrupted app with no way back except uninstalling, which on " +
                "a mail client costs the accounts",
            VersionOrder.isDowngrade(3_355_358L, 3_355_358L),
        )
    }

    @Test fun `a lower version code is refused`() {
        assertEquals(Order.OLDER, VersionOrder.compare(190L, 3_355_358L))
        assertTrue(
            "THE GUARD. versionCode 190 against an installed 3355358 is the exact pair #17 was " +
                "filed for: PackageInstaller answers INSTALL_FAILED_VERSION_DOWNGRADE, which " +
                "reads as a broken app when it is really nothing to do",
            VersionOrder.isDowngrade(190L, 3_355_358L),
        )
    }

    @Test fun `one off the boundary is still refused`() {
        assertEquals(Order.OLDER, VersionOrder.compare(3_355_357L, 3_355_358L))
        assertTrue(
            "off-by-one at the boundary: a strict < is what separates OLDER from SAME, and a " +
                "<= here would refuse every repair install",
            VersionOrder.isDowngrade(3_355_357L, 3_355_358L),
        )
    }

    @Test fun `an unreadable candidate is UNKNOWN, and is not called a downgrade`() {
        assertEquals(
            "a null candidate means the staged APK's manifest would not parse. That is not an " +
                "ordering and must not be reported as one",
            Order.UNKNOWN,
            VersionOrder.compare(null, 3_355_358L),
        )
        assertFalse(
            "Fleet.commit has always installed anyway when the manifest is unreadable and let " +
                "PackageInstaller judge, because PackageInstaller has the real answer. Turning " +
                "UNKNOWN into a refusal here would silently change that",
            VersionOrder.isDowngrade(null, 3_355_358L),
        )
    }

    @Test fun `nothing installed yet is UNKNOWN, and installs`() {
        assertEquals(Order.UNKNOWN, VersionOrder.compare(3_355_358L, null))
        assertFalse(
            "a first install has no installed code to be older than. Calling it a downgrade " +
                "would make the app impossible to install at all",
            VersionOrder.isDowngrade(3_355_358L, null),
        )
    }

    @Test fun `a missing version is never treated as version zero`() {
        // The tempting shortcut is `candidate ?: 0`, and it inverts the guard: an
        // unreadable candidate becomes 0, 0 is below every real versionCode, and
        // every APK whose manifest would not parse is then refused as a
        // "downgrade" - a message about the wrong problem entirely.
        assertEquals(Order.UNKNOWN, VersionOrder.compare(null, 1L))
        assertEquals(Order.UNKNOWN, VersionOrder.compare(null, null))
        assertEquals(
            "zero is a legitimate versionCode and orders like any other. Only null is UNKNOWN",
            Order.OLDER,
            VersionOrder.compare(0L, 1L),
        )
    }
}
