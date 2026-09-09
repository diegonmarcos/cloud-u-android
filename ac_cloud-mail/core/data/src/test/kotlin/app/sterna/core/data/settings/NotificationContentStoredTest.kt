package app.sterna.core.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification-content position on its way to and from storage (Codeberg #25): its key, its
 */
class NotificationContentStoredTest {

    /** The persisted key, spelled as the store spells it — renaming it resets everyone's choice. */
    private val storedKey = stringPreferencesKey("notification_content")

    @Test fun aFreshInstallShowsSenderAndSubject() {
        assertEquals(
            "a device nobody has configured must post sender + subject",
            NotificationContent.SENDER_AND_SUBJECT,
            notificationContentFrom(emptyPreferences()),
        )
    }

    @Test fun everyStoredPositionIsReadBack() {
        NotificationContent.entries.forEach { position ->
            assertEquals(
                "$position does not survive a round trip through the store",
                position,
                notificationContentFrom(preferencesOf(storedKey to position.name)),
            )
        }
    }

    @Test fun theTalkativePositionIsNeverReachedByAccident() {
        // Every way of NOT having chosen it, and the answer is the same each time. The default is
        // the one direction of this setting that cannot be corrected after the fact: a body shown
        // on a lock screen has been read by whoever was looking at it.
        listOf(emptyPreferences(), preferencesOf(storedKey to ""), preferencesOf(storedKey to "sender_and_subject"))
            .forEach { prefs ->
                assertEquals(
                    "nobody asked for a body preview here",
                    NotificationContent.SENDER_AND_SUBJECT,
                    notificationContentFrom(prefs),
                )
            }
    }

    @Test fun aPositionThisBuildDoesNotKnowFallsBackToSenderAndSubject() {
        // A newer build's position, read after a downgrade. Written as a literal that is NOT one of
        // this enum's names, which is the whole point: the string is what an older build would have
        // been handed on the day BODY_PREVIEW shipped.
        assertEquals(
            NotificationContent.SENDER_AND_SUBJECT,
            notificationContentFrom(preferencesOf(storedKey to "BODY_PREVIEW_AND_ATTACHMENTS")),
        )
        assertNull(notificationContentOrNull("BODY_PREVIEW_AND_ATTACHMENTS"))
        // Names are matched whole and exactly: a prefix of a real one is not a real one either.
        assertNull(notificationContentOrNull("BODY_"))
        assertNull(notificationContentOrNull("body_preview"))
    }

    @Test fun aStoredPositionIsReadBackFromTheKeyItWasWrittenTo() {
        assertEquals(
            NotificationContent.NONE,
            notificationContentFrom(preferencesOf(storedKey to NotificationContent.NONE.name)),
        )
        // A neighbouring setting must not answer for it.
        assertEquals(
            NotificationContent.SENDER_AND_SUBJECT,
            notificationContentFrom(preferencesOf(stringPreferencesKey("delivery_mode") to "NONE")),
        )
    }

    // -- the backup, which carries the same name --------------------------------------------------

    @Test fun theTalkativePositionSurvivesABackupRoundTrip() {
        val encoded = SettingsBackupCodec.encode(
            SettingsBackup(notificationContent = NotificationContent.BODY_PREVIEW.name),
        )
        assertTrue("exported: $encoded", encoded.contains("\"notificationContent\": \"BODY_PREVIEW\""))
        val back = SettingsBackupCodec.decode(encoded)
        // The VALUE restoreBackup would apply, run rather than described.
        assertEquals(
            NotificationContent.BODY_PREVIEW,
            notificationContentOrNull(back?.notificationContent),
        )
    }

    @Test fun everyPositionSurvivesABackupRoundTrip() {
        NotificationContent.entries.forEach { position ->
            val encoded = SettingsBackupCodec.encode(SettingsBackup(notificationContent = position.name))
            assertEquals(
                "$position does not survive a backup round trip",
                position,
                notificationContentOrNull(SettingsBackupCodec.decode(encoded)?.notificationContent),
            )
        }
    }

    @Test fun aBackupWrittenBeforeThePositionExistedChangesNothing() {
        // Absent, or a name this build does not know: restoreBackup only applies a non-null answer,
        // so a backup from another build leaves the device's own position alone instead of quietly
        // making its notifications more talkative.
        val old = """{"version":1,"themeMode":"DARK"}"""
        val backup = SettingsBackupCodec.decode(old)
        assertNull(backup?.notificationContent)
        assertNull(notificationContentOrNull(backup?.notificationContent))
        assertNull(notificationContentOrNull("SENDER_AND_BODY"))
    }
}
