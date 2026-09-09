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
 * The quoted-dates-in-UTC switch (#120): its default, its stored key, and its trip through a
 */
class SettingsBackupQuotedDatesUtcTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun theSwitchSurvivesAJsonRoundTrip() {
        val backup = SettingsBackup(quotedDatesUtc = true)
        val back = json.decodeFromString(
            SettingsBackup.serializer(),
            json.encodeToString(SettingsBackup.serializer(), backup),
        )
        assertEquals(true, back.quotedDatesUtc)
    }

    @Test fun aBackupWrittenBeforeItExistedStillImports() {
        // Absent = "leave as is": restoreBackup only applies non-null fields, so importing a backup
        // written before this setting existed keeps whatever the device has, instead of quietly
        // deciding for it.
        val old = """{"version":1,"themeMode":"DARK","replyBar":false}"""
        val backup = json.decodeFromString(SettingsBackup.serializer(), old)
        assertNull(backup.quotedDatesUtc)
        assertTrue(backup.isPlausible())
    }

    @Test fun itIsCarriedAsTrueWhenExplicitlyOn() {
        // The direction that matters: the default is false, so "on" is the answer a backup has to
        // carry. The VALUE, not the key — the codec writes with encodeDefaults, so an unset field
        // is exported as `"quotedDatesUtc": null` and contains("quotedDatesUtc") is true of every
        // export ever written, including one that lost the setting entirely.
        val encoded = SettingsBackupCodec.encode(SettingsBackup(quotedDatesUtc = true))
        assertTrue("exported: $encoded", encoded.contains("\"quotedDatesUtc\": true"))
        val off = SettingsBackupCodec.encode(SettingsBackup(quotedDatesUtc = false))
        assertTrue("exported: $off", off.contains("\"quotedDatesUtc\": false"))
    }

    @Test fun outgoingDatesStayLocalUntilSomebodyAsksOtherwise() {
        // The default, run rather than described: the decision is executed on Preferences where the
        // key is ABSENT, which is the state of every existing install on the update that ships
        // this. Flipping the elvis in quotedDatesUtcFrom rewrites the quoted dates of every user
        // who has never opened Settings, and nothing else in this repo would notice.
        assertFalse(
            "a writer who has configured nothing must keep the local time in outgoing quotes",
            quotedDatesUtcFrom(emptyPreferences()),
        )
        assertTrue(
            "a writer who turned the switch on must get UTC",
            quotedDatesUtcFrom(preferencesOf(storedKey to true)),
        )
        assertFalse(quotedDatesUtcFrom(preferencesOf(storedKey to false)))
    }

    @Test fun theSwitchIsReadBackFromTheKeyItWasWrittenTo() {
        // The key is spelled out here rather than imported: it names persisted user data, so
        // renaming it silently puts the local time back for everyone who chose UTC, and that is a
        // behaviour change this test is entitled to see.
        assertTrue(quotedDatesUtcFrom(preferencesOf(booleanPreferencesKey("quoted_dates_utc") to true)))
        // A neighbouring switch must not answer for it.
        assertFalse(quotedDatesUtcFrom(preferencesOf(booleanPreferencesKey("plain_text") to true)))
    }

    /** The persisted key, spelled as the store spells it — see the test above. */
    private val storedKey = booleanPreferencesKey("quoted_dates_utc")
}
