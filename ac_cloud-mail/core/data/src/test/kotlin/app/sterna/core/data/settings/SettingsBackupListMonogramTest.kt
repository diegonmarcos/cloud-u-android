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
 * The sender-initials switch of the message list (#144): its default, its stored key, and its trip
 */
class SettingsBackupListMonogramTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun theSwitchSurvivesAJsonRoundTrip() {
        val backup = SettingsBackup(listMonogram = false)
        val back = json.decodeFromString(
            SettingsBackup.serializer(),
            json.encodeToString(SettingsBackup.serializer(), backup),
        )
        assertEquals(false, back.listMonogram)
    }

    @Test fun aBackupWrittenBeforeItExistedStillImports() {
        // Absent = "leave as is": restoreBackup only applies non-null fields, so importing a backup
        // written before this setting existed keeps the repository default (the initials stay)
        // instead of silently stripping the avatar column off every row on the device.
        val old = """{"version":1,"themeMode":"DARK","unreadTint":false}"""
        val backup = json.decodeFromString(SettingsBackup.serializer(), old)
        assertNull(backup.listMonogram)
        assertTrue(backup.isPlausible())
    }

    @Test fun itIsCarriedAsFalseWhenExplicitlyOff() {
        // The direction that matters: the default is true, so "off" is the answer a backup has to
        // carry. The VALUE, not the key — the codec writes with encodeDefaults, so an unset field
        // is exported as `"listMonogram": null` and contains("listMonogram") is true of every
        // export ever written, including one that lost the setting entirely.
        val encoded = SettingsBackupCodec.encode(SettingsBackup(listMonogram = false))
        assertTrue("exported: $encoded", encoded.contains("\"listMonogram\": false"))
        // And the other value is carried too, rather than swallowed by a default on the way out.
        val on = SettingsBackupCodec.encode(SettingsBackup(listMonogram = true))
        assertTrue("exported: $on", on.contains("\"listMonogram\": true"))
    }

    @Test fun theInitialsAreShownUntilSomebodyTurnsThemOff() {
        // The default, run rather than described: the decision is executed on Preferences where the
        // key is ABSENT, which is the state of every existing install on the update that ships
        // this. Flipping the elvis in listMonogramFrom takes the coloured circle away from every
        // user who has never opened Appearance, and nothing else in this repo would notice.
        assertTrue(
            "a list nobody has configured must still show the sender initials",
            listMonogramFrom(emptyPreferences()),
        )
        assertTrue(listMonogramFrom(preferencesOf(storedKey to true)))
        assertFalse(
            "a reader who turned the initials off must not get them back",
            listMonogramFrom(preferencesOf(storedKey to false)),
        )
    }

    @Test fun theSwitchIsReadBackFromTheKeyItWasWrittenTo() {
        // The key is spelled out here rather than imported: it names persisted user data, so
        // renaming it silently puts the initials back for everyone who had removed them, and that
        // is a behaviour change this test is entitled to see.
        assertFalse(listMonogramFrom(preferencesOf(booleanPreferencesKey("list_monogram") to false)))
        // A neighbouring switch must not answer for it.
        assertTrue(listMonogramFrom(preferencesOf(booleanPreferencesKey("unread_tint") to false)))
    }

    /** The persisted key, spelled as the store spells it — see the test above. */
    private val storedKey = booleanPreferencesKey("list_monogram")
}
