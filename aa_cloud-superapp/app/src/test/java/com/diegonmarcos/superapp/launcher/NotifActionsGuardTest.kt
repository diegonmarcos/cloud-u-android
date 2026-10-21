package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1068 guard - a redesign must never silently drop a per-box action again. [NotifActions.REQUIRED_PER_BOX] is the list;
 * this draws a Notify box and asserts every action is present and works, and reads the source of every gesture the row
 * must keep (swipe right = read, swipe left = archive, long-press sheet, tap = open) so a restyle that deletes one fails
 * here. Boards > Inbox has the same guard in BoardsTest ("every Inbox box carries every required action").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h1600dp-450dpi")
class NotifActionsGuardTest : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private fun view(key: String, vararg rows: NotifRow) =
        GroupView(NotifGroup(key = key, label = "App $key", sub = key, rows = rows.toList()), GroupChip("${rows.size}", Tone.OK))

    @Test
    fun `the required actions are exactly the four the owner named`() {
        assertEquals(listOf("mark_read", "archive", "open", "reply"), NotifActions.REQUIRED_PER_BOX)
        assertEquals(listOf("mark_all_read", "archive_all", "restore"), NotifActions.REQUIRED_PER_PAGE)
    }

    @Test
    fun `every Notify box draws every required action and Mark read and Archive act`() {
        val now = System.currentTimeMillis()
        val a = view("pkg.a", NotifRow(now - 60_000, "t1", "b1", id = "phone:a1"), NotifRow(now - 30_000, "t2", "b2", id = "phone:a2"))
        val b = view("pkg.b", NotifRow(now - 20_000, "t3", "b3", id = "phone:b1"))
        val st = AggregatorState(ctx, "actions-guard", emptyList(), emptyList())
        showKit { GroupList(st, listOf(a, b)) { } }
        for (key in listOf("pkg.a", "pkg.b")) for (act in NotifActions.REQUIRED_PER_BOX) {
            compose.onNodeWithTag(NotifActions.tag(act, key)).assertExists()
        }
        compose.onNodeWithTag(NotifActions.tag(NotifActions.MARK_READ, "pkg.a")).assertHasClickAction()
        compose.onNodeWithTag(NotifActions.tag(NotifActions.MARK_READ, "pkg.a")).performClick()
        compose.waitForIdle()
        assertTrue("readIds=${st.readIds} stored=${StackFilters.readKeys(ctx, "actions-guard")}",
            st.readIds.containsAll(listOf("phone:a1", "phone:a2")))
        assertFalse("phone:b1" in st.readIds)
        compose.onNodeWithTag(NotifActions.tag(NotifActions.ARCHIVE, "pkg.b")).performClick()
        compose.waitForIdle()
        assertTrue(st.isArchived("pkg.b"))
        compose.onNodeWithTag(AggTags.group("pkg.b")).assertDoesNotExist()
    }

    @Test
    fun `the row gestures and page actions are still wired in the source`() {
        val dir = File(System.getProperty("user.dir"), "src/main/java/com/diegonmarcos/superapp/launcher")
        val notify = File(dir, "AggregatorNotify.kt").readText()
        val boards = File(dir, "BoardsPanels.kt").readText()
        // swipe right = toggle read, swipe left = archive, long-press = sheet, tap = open (Notify rows)
        assertTrue("swipe right must toggle read", Regex("total > 0 && !inArchive[\\s\\S]{0,200}state\\.markRead").containsMatchIn(notify))
        assertTrue("swipe left must archive the row", notify.contains("state.archiveRow(r.id, !inArchive)"))
        assertTrue("long-press must open the sheet", notify.contains("onLongClick = { Haptics.tap(hostView); menu = true }"))
        assertTrue("tap must open the app", notify.contains("onClick = { openApp?.invoke() }"))
        // Boards > Inbox rows keep the same two swipes and use the shared pill row
        assertTrue(boards.contains("rowSwipe(id, onRight = { state.markRead(id, unread) }, onLeft = { state.archiveRow(id, true) })"))
        assertTrue("Notify boxes must draw NotifActionRow", notify.contains("NotifActionRow("))
        assertTrue("Inbox boxes must draw NotifActionRow", boards.contains("NotifActionRow("))
        // page-level: Mark all read / Archive all pills and the Restore pill of an archived box
        assertTrue(notify.contains("AggTags.MARK_ALL_READ") && notify.contains("AggTags.ARCHIVE_ALL"))
        assertTrue(notify.contains("ARCHIVE_RESTORE_LABEL"))
    }
}
