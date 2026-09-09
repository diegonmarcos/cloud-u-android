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
 * The unread-background switch (#141): its default, its stored key, and its trip through a settings
 */
class SettingsBackupUnreadTintTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun theSwitchSurvivesAJsonRoundTrip() {
        val backup = SettingsBackup(unreadTint = false)
        val back = json.decodeFromString(
            SettingsBackup.serializer(),
            json.encodeToString(SettingsBackup.serializer(), backup),
        )
        assertEquals(false, back.unreadTint)
    }

    @Test fun aBackupWrittenBeforeItExistedStillImports() {
        // Absent = "leave as is": restoreBackup only applies non-null fields, so importing a backup
        // written before this setting existed keeps the repository default (rows stay tinted)
        // instead of silently flattening every unread row on the device.
        val old = """{"version":1,"themeMode":"DARK","replyBar":false}"""
        val backup = json.decodeFromString(SettingsBackup.serializer(), old)
        assertNull(backup.unreadTint)
        assertTrue(backup.isPlausible())
    }

    @Test fun itIsCarriedAsFalseWhenExplicitlyOff() {
        // The direction that matters: the default is true, so "off" is the answer a backup has to
        // carry. The VALUE, not the key — the codec writes with encodeDefaults, so an unset field
        // is exported as `"unreadTint": null` and contains("unreadTint") is true of every export
        // ever written, including one that lost the setting entirely.
        val encoded = SettingsBackupCodec.encode(SettingsBackup(unreadTint = false))
        assertTrue("exported: $encoded", encoded.contains("\"unreadTint\": false"))
    }

    @Test fun unreadRowsAreTintedUntilSomebodyTurnsItOff() {
        // The default, run rather than described: the decision is executed on Preferences where the
        // key is ABSENT, which is the only state a fresh install is ever in. Flipping the elvis in
        // unreadTintFrom takes the background away from every user who has never opened Appearance,
        // and nothing else in this repo would notice.
        assertTrue(
            "a list nobody has configured must still tint its unread rows",
            unreadTintFrom(emptyPreferences()),
        )
        assertTrue(unreadTintFrom(preferencesOf(storedKey to true)))
        assertFalse(
            "a reader who turned the tint off must not get it back",
            unreadTintFrom(preferencesOf(storedKey to false)),
        )
    }

    @Test fun theSwitchIsReadBackFromTheKeyItWasWrittenTo() {
        // The key is spelled out here rather than imported: it names persisted user data, so
        // renaming it silently turns the tint back on for everyone who had removed it, and that is
        // a behaviour change this test is entitled to see.
        assertFalse(unreadTintFrom(preferencesOf(booleanPreferencesKey("unread_tint") to false)))
        // A neighbouring switch must not answer for it.
        assertTrue(unreadTintFrom(preferencesOf(booleanPreferencesKey("reply_bar") to false)))
    }

    /** The persisted key, spelled as the store spells it — see the test above. */
    private val storedKey = booleanPreferencesKey("unread_tint")
}
