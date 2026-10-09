package app.sterna.push

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** Remind and Copy Code on the new-mail banner: the delay, the setting, the cancel, the shared extractor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationRemindTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun src(path: String) = File("src/main/kotlin/app/sterna/$path").readText()

    @Test fun `remind re-posts after one hour by default`() {
        assertEquals(60, NotificationReminders.delayMinutes(context))
        val req = NotificationReminders.requestFor(NotificationReminders.delayMinutes(context), "a", "m")
        assertEquals(TimeUnit.HOURS.toMillis(1), req.workSpec.initialDelay)
        assertEquals("m", req.workSpec.input.getString(RemindWorker.KEY_ID))
        assertEquals("a", req.workSpec.input.getString(RemindWorker.KEY_ACCOUNT))
    }

    @Test fun `remind honours the configured delay`() {
        NotificationReminders.setDelayMinutes(context, 15)
        assertEquals(15, NotificationReminders.delayMinutes(context))
        val req = NotificationReminders.requestFor(NotificationReminders.delayMinutes(context), "a", "m")
        assertEquals(TimeUnit.MINUTES.toMillis(15), req.workSpec.initialDelay)
        NotificationReminders.setDelayMinutes(context, 0)
        assertEquals("a nonsense value falls back", 60, NotificationReminders.delayMinutes(context))
    }

    @Test fun `one unique work name per message so a read can cancel it`() {
        assertEquals(NotificationReminders.workName("a", "m"), NotificationReminders.workName("a", "m"))
        assertTrue(NotificationReminders.workName("a", "m") != NotificationReminders.workName("a", "n"))
    }

    @Test fun `reading or deleting a message cancels its reminder`() {
        val n = src("push/Notifications.kt")
        val cancelChild = n.substringAfter("fun cancelChild(").substringBefore("fun dismiss(")
        assertTrue(cancelChild.contains("NotificationReminders.cancel(context, accountId, emailId)"))
        // a reminder whose banner was already down is caught by the worker: it will not re-post a read message
        assertTrue(src("push/RemindWorker.kt").contains("if (email.isSeen) return Result.success()"))
    }

    @Test fun `copy code uses the reading pane's extractor and no second one`() {
        val n = src("push/Notifications.kt")
        assertTrue(n.contains("val code = verificationCodeFromMessage(email)"))
        assertTrue(src("ui/message/MessageScreen.kt").contains("internal fun verificationCodeFromMessage("))
        assertTrue("the banner path holds no extractor of its own", !n.contains("extractVerificationCode"))
        assertTrue(!src("push/NotificationActionReceiver.kt").contains("extractVerificationCode"))
    }

    @Test fun `the copy marks the clip sensitive on Android 13 and says Code copied`() {
        val r = src("push/NotificationActionReceiver.kt")
        assertTrue(r.contains("SDK_INT >= 33"))
        assertTrue(r.contains("EXTRA_IS_SENSITIVE"))
        assertTrue(r.contains("R.string.message_code_copied"))
    }
}
