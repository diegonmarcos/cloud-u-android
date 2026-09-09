package app.sterna.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The black-background switch (#117): its default, its stored key, and its trip through a settings
 */
class SettingsBackupPureBlackTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun theSwitchSurvivesAJsonRoundTrip() {
        val backup = SettingsBackup(pureBlack = true)
        val back = json.decodeFromString(
            SettingsBackup.serializer(),
            json.encodeToString(SettingsBackup.serializer(), backup),
        )
        assertEquals(true, back.pureBlack)
    }

    @Test fun aBackupWrittenBeforeItExistedStillImports() {
        // Absent = "leave as is": restoreBackup only applies non-null fields, so importing a backup
        // written before this setting existed keeps whatever the device already had, instead of
        // resetting the theme of someone who had turned the black background on.
        val old = """{"version":1,"themeMode":"DARK","unreadTint":false}"""
        val backup = json.decodeFromString(SettingsBackup.serializer(), old)
        assertNull(backup.pureBlack)
        assertTrue(backup.isPlausible())
    }

    @Test fun itIsCarriedAsTrueWhenExplicitlyOn() {
        // The direction that matters: the default is false, so "on" is the answer a backup has to
        // carry. The VALUE, not the key — the codec writes with encodeDefaults, so an unset field
        // is exported as `"pureBlack": null` and contains("pureBlack") is true of every export ever
        // written, including one that lost the setting entirely.
        val encoded = SettingsBackupCodec.encode(SettingsBackup(pureBlack = true))
        assertTrue("exported: $encoded", encoded.contains("\"pureBlack\": true"))
        // And the other value is carried too, rather than swallowed by a default on the way out.
        val off = SettingsBackupCodec.encode(SettingsBackup(pureBlack = false))
        assertTrue("exported: $off", off.contains("\"pureBlack\": false"))
    }

    @Test fun theDarkThemeKeepsItsSurfacesUntilSomebodyAsksForBlack() {
        // The default, run rather than described: the decision is executed on Preferences where the
        // key is ABSENT, which is the only state a fresh install and every existing install are in
        // on the update that ships this. Flipping the elvis in pureBlackFrom repaints the dark
        // theme of every user who never asked, and nothing else in this repo would notice.
        assertFalse(
            "an app nobody has configured must keep the Pelagic dark surfaces",
            pureBlackFrom(emptyPreferences()),
        )
        assertTrue(pureBlackFrom(preferencesOf(storedKey to true)))
        assertFalse(
            "a reader who turned the black background off must not get it back",
            pureBlackFrom(preferencesOf(storedKey to false)),
        )
    }

    @Test fun theSwitchIsReadBackFromTheKeyItWasWrittenTo() {
        // The key is spelled out here rather than imported: it names persisted user data, so
        // renaming it silently puts the ordinary dark theme back for everyone who had chosen black,
        // and that is a behaviour change this test is entitled to see.
        assertTrue(pureBlackFrom(preferencesOf(booleanPreferencesKey("pure_black") to true)))
        // A neighbouring switch must not answer for it.
        assertFalse(pureBlackFrom(preferencesOf(booleanPreferencesKey("unread_tint") to true)))
    }

    /** The persisted key, spelled as the store spells it — see the test above. */
    private val storedKey = booleanPreferencesKey("pure_black")
}
