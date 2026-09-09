package app.sterna.core.data.settings

import app.sterna.core.data.settings.SettingsRepository.Companion.isWithinQuietHours
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a mail notification must be posted silently: the quiet-hours window test shared by
 */
class QuietHoursTest {

    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test fun `the default night window silences the small hours`() {
        val start = SettingsRepository.DEFAULT_QUIET_START
        val end = SettingsRepository.DEFAULT_QUIET_END
        assertTrue(isWithinQuietHours(at(23), start, end))
        assertTrue(isWithinQuietHours(at(3, 30), start, end))
        assertTrue(isWithinQuietHours(at(22), start, end))
    }

    @Test fun `the default night window leaves the day alone`() {
        val start = SettingsRepository.DEFAULT_QUIET_START
        val end = SettingsRepository.DEFAULT_QUIET_END
        assertFalse(isWithinQuietHours(at(7), start, end))
        assertFalse(isWithinQuietHours(at(14, 15), start, end))
        assertFalse(isWithinQuietHours(at(21, 59), start, end))
    }

    @Test fun `a window inside one day does not wrap`() {
        assertTrue(isWithinQuietHours(at(10), at(9), at(12)))
        assertFalse(isWithinQuietHours(at(8, 59), at(9), at(12)))
        assertFalse(isWithinQuietHours(at(12), at(9), at(12)))
    }

    @Test fun `the window is closed at its end and open at its start`() {
        assertTrue(isWithinQuietHours(at(22), at(22), at(7)))
        assertFalse(isWithinQuietHours(at(7), at(22), at(7)))
    }

    @Test fun `an empty window is never quiet`() {
        assertFalse(isWithinQuietHours(at(22), at(22), at(22)))
        assertFalse(isWithinQuietHours(at(3), at(22), at(22)))
    }

    @Test fun `midnight is inside a wrapping window`() {
        assertTrue(isWithinQuietHours(0, at(22), at(7)))
        assertTrue(isWithinQuietHours(at(23, 59), at(22), at(7)))
    }
}
