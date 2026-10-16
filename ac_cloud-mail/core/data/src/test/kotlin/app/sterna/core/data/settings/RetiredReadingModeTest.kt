package app.sterna.core.data.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader has no plain-text mode any more; a settings export from when it had one still imports. */
class RetiredReadingModeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun anOldExportThatSwitchedItOnStillParses() {
        val old = """{"version":1,"themeMode":"DARK","plainText":true}"""
        val backup = json.decodeFromString(SettingsBackup.serializer(), old)
        assertTrue(backup.isPlausible())
        // Carried by the codec as data and never applied: the repository has nothing to apply it to.
        assertEquals(true, backup.plainText)
    }
}
