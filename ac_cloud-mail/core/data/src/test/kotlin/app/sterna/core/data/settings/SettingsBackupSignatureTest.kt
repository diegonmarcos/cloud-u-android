package app.sterna.core.data.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three signature settings — when it is inserted, where, and whether the "-- " delimiter line
 */
class SettingsBackupSignatureTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun bothSettingsSurviveAJsonRoundTrip() {
        val backup = SettingsBackup(
            signatureOnReplies = true,
            signatureBelowQuote = true,
            signatureDelimiter = false,
        )
        val back = json.decodeFromString(
            SettingsBackup.serializer(),
            json.encodeToString(SettingsBackup.serializer(), backup),
        )
        assertEquals(true, back.signatureOnReplies)
        assertEquals(true, back.signatureBelowQuote)
        assertEquals(false, back.signatureDelimiter)
    }

    @Test fun aBackupWrittenBeforeTheyExistedStillImports() {
        // Absent = "leave as is": restoreBackup only applies non-null fields. For the delimiter
        // that means the repository default (on) is kept, not silently flipped off (#90).
        val old = """{"version":1,"themeMode":"DARK","unarchiveOnReply":true}"""
        val backup = json.decodeFromString(SettingsBackup.serializer(), old)
        assertNull(backup.signatureOnReplies)
        assertNull(backup.signatureBelowQuote)
        assertNull(backup.signatureDelimiter)
        assertTrue(backup.isPlausible())
    }

    @Test fun theyAreCarriedAsFalseWhenExplicitlyOff() {
        // The VALUES, not the keys — this is where the pattern was invented and where it was
        // copied from. The codec writes with encodeDefaults and explicitNulls, so every key is
        val json0 = SettingsBackupCodec.encode(
            SettingsBackup(
                signatureOnReplies = false,
                signatureBelowQuote = false,
                signatureDelimiter = false,
            ),
        )
        assertTrue("exported: $json0", json0.contains("\"signatureOnReplies\": false"))
        assertTrue("exported: $json0", json0.contains("\"signatureBelowQuote\": false"))
        assertTrue("exported: $json0", json0.contains("\"signatureDelimiter\": false"))
    }
}
