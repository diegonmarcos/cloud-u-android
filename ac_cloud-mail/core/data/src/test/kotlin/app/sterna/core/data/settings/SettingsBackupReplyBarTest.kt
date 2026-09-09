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
 * The reader's Reply/Forward bar switch (#63) must travel in a settings backup like every other
 */
class SettingsBackupReplyBarTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun theSwitchSurvivesAJsonRoundTrip() {
        val backup = SettingsBackup(replyBar = false)
        val back = json.decodeFromString(
            SettingsBackup.serializer(),
            json.encodeToString(SettingsBackup.serializer(), backup),
        )
        assertEquals(false, back.replyBar)
    }

    @Test fun aBackupWrittenBeforeItExistedStillImports() {
        // Absent = "leave as is": restoreBackup only applies non-null fields, so importing an older
        // backup keeps the repository default (the bar is shown) instead of silently removing it.
        val old = """{"version":1,"themeMode":"DARK","signatureDelimiter":false}"""
        val backup = json.decodeFromString(SettingsBackup.serializer(), old)
        assertNull(backup.replyBar)
        assertTrue(backup.isPlausible())
    }

    @Test fun itIsCarriedAsFalseWhenExplicitlyOff() {
        // The direction that matters: the default is true, so "off" is the answer a backup has to
        // carry, and a codec that dropped false values would restore the bar the user removed.
        //
        // The VALUE, not the key. `contains("replyBar")` was true of any export at all: the codec
        // writes with encodeDefaults and explicitNulls, so an unset field is exported as
        // `"replyBar": null` and the key is always there. An encoding that lost the setting on
        // every export left this assertion green — which is precisely what it was written to stop.
        val encoded = SettingsBackupCodec.encode(SettingsBackup(replyBar = false))
        assertTrue("exported: $encoded", encoded.contains("\"replyBar\": false"))
    }

    @Test fun theBarIsOnUntilSomebodyTurnsItOff() {
        // The default, run rather than described. Nothing covered it: flipping the elvis in
        // SettingsRepository.replyBar took the bar away from EVERY user who had never touched the
        // switch — the exact opposite of what the setting announces — with the whole suite green.
        //
        // The LOOKUP is run too, not just the defaulting. As a function of the stored `Boolean?`
        // this could still be defeated from outside — `replyBarFrom(prefs[KEY] ?: false)` is one
        // line, does the same damage, and leaves the function itself untouched. It takes the whole
        // Preferences now, so there is nothing left to pre-empt.
        assertTrue("a reader that has never seen the switch shows the bar", replyBarFrom(emptyPreferences()))
        assertTrue(replyBarFrom(preferencesOf(storedKey to true)))
        assertFalse("a reader that turned it off must not get it back", replyBarFrom(preferencesOf(storedKey to false)))
    }

    @Test fun theSwitchIsReadBackFromTheKeyItWasWrittenTo() {
        // The key is spelled out here rather than imported: it names persisted user data, so
        // renaming it silently restores the bar for everyone who had turned it off, and that is a
        // behaviour change this test is entitled to see.
        assertFalse(replyBarFrom(preferencesOf(booleanPreferencesKey("reply_bar") to false)))
        // A neighbouring switch must not answer for it.
        assertTrue(replyBarFrom(preferencesOf(booleanPreferencesKey("conversation_view") to false)))
    }

    /** The persisted key, spelled as the store spells it — see the test above. */
    private val storedKey = booleanPreferencesKey("reply_bar")
}
