package com.diegonmarcos.superapp.apps

import com.diegonmarcos.superapp.appstore.BuiltFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #820 the one reader of the release `.source` sidecar + the installed versionName sha. */
class StoreBuiltFromTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"

    @Test fun twoLineSidecarYieldsCommit() =
        assertEquals(sha, BuiltFrom.commitFromSidecar("deadbeef\n$sha\n"))

    @Test fun upperCaseCommitIsNormalised() =
        assertEquals(sha, BuiltFrom.commitFromSidecar("d\n${sha.uppercase()}"))

    @Test fun legacyOneLineSidecarIsUnknown() {
        assertNull(BuiltFrom.commitFromSidecar("deadbeef\n"))
        assertEquals("unknown", BuiltFrom.short(BuiltFrom.commitFromSidecar("deadbeef")))
    }

    @Test fun line1IsNeverTakenAsTheCommit() = assertNull(BuiltFrom.commitFromSidecar("$sha\n"))

    @Test fun nonHexOrShortLine2IsUnknown() {
        assertNull(BuiltFrom.commitFromSidecar("d\n${sha.take(39)}"))
        assertNull(BuiltFrom.commitFromSidecar("d\n${sha.take(39)}g"))
        assertNull(BuiltFrom.commitFromSidecar(null))
    }

    @Test fun shortIsEightChars() = assertEquals("01234567", BuiltFrom.short(sha))

    @Test fun versionNameShaIsRead() {
        assertEquals("55f8defa", BuiltFrom.shaFromVersionName("1.4.0 (sha-55f8defa)"))
        assertNull(BuiltFrom.shaFromVersionName("1.4.0"))
        assertNull(BuiltFrom.shaFromVersionName(null))
    }

    @Test fun sameComparesByPrefixAndNeverOnUnknown() {
        assertTrue(BuiltFrom.same("01234567", sha))
        assertFalse(BuiltFrom.same("76543210", sha))
        assertFalse(BuiltFrom.same(null, sha))
        assertFalse(BuiltFrom.same("", ""))
    }

    @Test fun commitUrlUsesTheReleaseRepo() {
        assertEquals("https://github.com/o/r/commit/$sha",
            BuiltFrom.commitUrl("https://github.com/o/r/releases/download/t/a.apk", sha))
        assertNull(BuiltFrom.commitUrl("https://github.com/o/r/releases/download/t/a.apk", null))
    }
}
