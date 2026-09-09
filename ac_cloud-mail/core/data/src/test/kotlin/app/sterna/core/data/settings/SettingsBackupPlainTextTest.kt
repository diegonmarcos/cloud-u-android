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
 * The reading-mode switch (#149): its default, its stored key, and its trip through a settings
 */
class SettingsBackupPlainTextTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun theSwitchSurvivesAJsonRoundTrip() {
        val backup = SettingsBackup(plainText = true)
        val back = json.decodeFromString(
            SettingsBackup.serializer(),
            json.encodeToString(SettingsBackup.serializer(), backup),
        )
        assertEquals(true, back.plainText)
    }

    @Test fun aBackupWrittenBeforeItExistedStillImports() {
        // Absent = "leave as is": restoreBackup only applies non-null fields, so importing a backup
        // written before this setting existed keeps whatever the device has, instead of quietly
        // deciding for it.
        val old = """{"version":1,"themeMode":"DARK","replyBar":false}"""
        val backup = json.decodeFromString(SettingsBackup.serializer(), old)
        assertNull(backup.plainText)
        assertTrue(backup.isPlausible())
    }

    @Test fun itIsCarriedAsTrueWhenExplicitlyOn() {
        // The direction that matters: the default is false, so "on" is the answer a backup has to
        // carry. The VALUE, not the key — the codec writes with encodeDefaults, so an unset field is
        // exported as `"plainText": null` and contains("plainText") is true of every export ever
        // written, including one that lost the setting entirely.
        val encoded = SettingsBackupCodec.encode(SettingsBackup(plainText = true))
        assertTrue("exported: $encoded", encoded.contains("\"plainText\": true"))
        val off = SettingsBackupCodec.encode(SettingsBackup(plainText = false))
        assertTrue("exported: $off", off.contains("\"plainText\": false"))
    }

    @Test fun messagesOpenAsTheirHtmlUntilSomebodyAsksOtherwise() {
        // The default, run rather than described: the decision is executed on Preferences where the
        // key is ABSENT, which is the state of every existing install on the update that ships this.
        // Flipping the elvis in plainTextFrom flattens every message for every user who has never
        // opened Settings, and nothing else in this repo would notice.
        assertFalse(
            "a reader who has configured nothing must still get the message's own HTML",
            plainTextFrom(emptyPreferences()),
        )
        assertTrue(
            "a reader who turned the switch on must get text",
            plainTextFrom(preferencesOf(storedKey to true)),
        )
        assertFalse(plainTextFrom(preferencesOf(storedKey to false)))
    }

    @Test fun theSwitchIsReadBackFromTheKeyItWasWrittenTo() {
        // The key is spelled out here rather than imported: it names persisted user data, so
        // renaming it silently puts the HTML back for everyone who chose to read text, and that is a
        // behaviour change this test is entitled to see.
        assertTrue(plainTextFrom(preferencesOf(booleanPreferencesKey("plain_text") to true)))
        // A neighbouring switch must not answer for it.
        assertFalse(plainTextFrom(preferencesOf(booleanPreferencesKey("reply_bar") to true)))
    }

    /** The persisted key, spelled as the store spells it — see the test above. */
    private val storedKey = booleanPreferencesKey("plain_text")
}
