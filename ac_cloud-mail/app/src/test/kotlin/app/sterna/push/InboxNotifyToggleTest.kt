package app.sterna.push

import app.sterna.core.data.account.StoredAccount
import app.sterna.ui.inbox.notifyingFolderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Inbox's "Notify about new mail": on by default, wired through every delivery path, others untouched. */
class InboxNotifyToggleTest {
    private fun src(path: String) = File("src/main/kotlin/app/sterna/$path").readText()

    @Test fun `the Inbox menu shows the checkbox, ticked by default`() {
        val screen = src("ui/inbox/InboxScreen.kt")
        assertTrue("inbox is no longer hidden from the notify entry", """setOf("sent", "drafts", "trash", "junk")""" in screen)
        assertTrue(screen.contains("trailingIcon = { Checkbox(checked = watched, onCheckedChange = null) }"))
        // ticked: a fresh account mutes nothing, and the Inbox is in the notifying set
        assertTrue(StoredAccount::class.java.getDeclaredField("mutedFolders") != null)
        assertEquals(setOf("inbox"), notifyingFolderIds(emptySet(), "inbox", emptySet()))
    }

    @Test fun `muting the Inbox leaves another folder's setting alone`() {
        assertEquals(setOf("f1"), notifyingFolderIds(setOf("f1"), "inbox", setOf("inbox")))
        assertEquals(setOf("f1", "inbox"), notifyingFolderIds(setOf("f1"), "inbox", emptySet()))
        assertTrue(announcesFolder("f1", setOf("inbox")))
        assertFalse(announcesFolder("inbox", setOf("inbox")))
    }

    @Test fun `every delivery path reaches the one pass that honours the setting`() {
        // push (service + WorkManager), the poll worker and the IDLE service all run FetchAndNotify.run
        listOf("push/PushService.kt", "push/PushFetchWorker.kt", "push/MailFetchWorker.kt").forEach {
            assertTrue("$it must go through FetchAndNotify.run", src(it).contains("FetchAndNotify.run("))
        }
        // ...which decides announce per folder from the muted set, and notifyDiff is called nowhere else
        val fetch = src("push/FetchAndNotify.kt")
        assertTrue(fetch.contains("announce = announcesFolder(folder.mailboxId, muted)"))
        assertTrue(fetch.contains("val muted = store.mutedFolders(credentials.id)"))
        val callers = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .filter { "NewMailNotifier.notifyDiff(" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("FetchAndNotify.kt"), callers)
        // and notifyDiff posts nothing new when announce is off
        val notifier = src("push/NewMailNotifier.kt")
        assertTrue(notifier.contains("(if (announce) newSince(context, credentials.id, mailboxId, emails) else emptyList())"))
    }

    @Test fun `new-mail notifications have exactly three posters, and two are reminders`() {
        val posters = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .filter { "Notifications.notifyNewMail(" in it.readText() }.map { it.name }.sorted().toList()
        assertEquals(listOf("NewMailNotifier.kt", "RemindWorker.kt", "SnoozeWorker.kt"), posters)
    }

    @Test fun `turning it back on does not replay: the baseline advances while it is off`() {
        // notifyDiff ends in seed(baselineIds) whatever announce says, so mail that arrived while muted is
        // remembered and only later arrivals are new.
        val notifier = src("push/NewMailNotifier.kt")
        val tail = notifier.substringAfter("fun notifyDiff(").substringBefore("/** Whether [email] was received")
        assertTrue(tail.contains("        seed(context, credentials.id, mailboxId, baselineIds)"))
        assertFalse("seed must not be inside the announce branch", tail.substringBefore("seed(context").contains("if (announce) {"))
    }

    @Test fun `the Inbox choice is stored as a mute, in the account record`() {
        val vm = src("ui/inbox/InboxViewModel.kt")
        assertTrue(vm.contains("store.setFolderMuted(accountId, mailboxId, muted = !watched)"))
        assertTrue(src("push/RemindWorker.kt").contains("Notifications.notifyNewMail("))
    }
}
