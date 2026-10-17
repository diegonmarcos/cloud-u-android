package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.boards.Boards
import com.diegonmarcos.superapp.boards.BoardsSource
import com.diegonmarcos.superapp.boards.BoardsWire
import com.diegonmarcos.superapp.boards.InboxItem
import com.diegonmarcos.superapp.boards.PlaceVisit
import com.diegonmarcos.superapp.boards.PlacesDay
import com.diegonmarcos.superapp.boards.Read
import com.diegonmarcos.superapp.boards.StepsBoard
import com.diegonmarcos.superapp.boards.StepsDay
import com.diegonmarcos.superapp.boards.TaskProject
import com.diegonmarcos.superapp.boards.Why
import com.diegonmarcos.superapp.uikit.KitPartTags
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Boards (was Projects): the rename and its aliases, Work-PM and C3-PM gone, Tasks in their place, and every board
 * drawn from its provider — a fake [BoardsSource] here, the owner apps over the fleet IPC door in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BoardsTest : KitPageHarness() {

    private val real = Boards.source
    private val opened = mutableListOf<String>()

    private class Fake(
        val mail: Read<List<InboxItem>> = Read.Ok(emptyList()),
        val phone: Read<List<InboxItem>> = Read.Ok(emptyList()),
        val steps: Read<StepsBoard> = Read.Unavailable(Why.NOT_INSTALLED, "Cloud Nav is not installed"),
        val places: Read<List<PlacesDay>> = Read.Ok(emptyList()),
        val projects: Read<List<TaskProject>> = Read.Ok(emptyList()),
    ) : BoardsSource {
        var calls = 0
        override fun fleetMail(limit: Int) = mail.also { calls++ }
        override fun phoneNotifications() = phone.also { calls++ }
        override fun steps(days: Int) = steps.also { calls++ }
        override fun places(days: Int) = places.also { calls++ }
        override fun taskProjects() = projects.also { calls++ }
    }

    private fun use(f: Fake): Fake { Boards.source = { f }; return f }

    @Before fun clear() = opened.clear()
    @After fun restore() { Boards.source = real }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    // ── the rename ──────────────────────────────────────────────────

    @Test fun `the section is Boards and the old id still resolves`() {
        val boards = Sections.byId("boards")
        assertNotNull(boards)
        assertEquals("Boards", boards!!.label)
        assertTrue("bottom nav shows Boards", "boards" in Sections.bottomNavIds())
        assertFalse("bottom nav no longer names infos", "infos" in Sections.bottomNavIds())
        assertEquals("boards", Sections.byId("infos")?.id)
        assertEquals("boards", Sections.canonicalId("infos"))
        assertEquals("boards" to "my-pm", Sections.canonicalPage("infos", "my-pm"))
        assertTrue(Sections.all().none { it.label == "Projects" && it.id == "boards" })
    }

    @Test fun `Work-PM and C3-PM are gone and Tasks replaces them`() {
        val boards = Sections.byId("boards")!!
        assertEquals(listOf("msgs", "my-pm", "tasks"), boards.allPages.map { it.id })
        assertTrue(boards.allPages.none { it.label in setOf("Work-PM", "C3-PM") })
        assertFalse("work-pm" in boards.stackByPage || "work-pm" in boards.tilesByPage)
        assertEquals("boards" to "tasks", Sections.canonicalPage("infos", "work-pm"))
        assertEquals("boards" to "tasks", Sections.canonicalPage("boards", "cloud"))
        val tasks = boards.stackByPage["tasks"].orEmpty().map { it.kind }
        assertEquals(listOf("board_task_projects", "calendar_month"), tasks)
        // The calendar is the same component My-PM shows.
        assertTrue("calendar_month" in boards.stackByPage["my-pm"].orEmpty().map { it.kind })
    }

    @Test fun `no board carries sample numbers`() {
        val boards = Sections.byId("boards")!!
        val panels = boards.stackByPage.values.flatten()
        assertTrue(panels.none { it.kind == "stats" })
        assertTrue(panels.all { it.rows.isEmpty() })
        val myPm = boards.stackByPage["my-pm"].orEmpty().map { it.kind }
        assertTrue("board_steps" in myPm && "board_places" in myPm)
        assertEquals("board_inbox", boards.stackByPage["msgs"].orEmpty().first().kind)
    }

    // ── each board reads its provider ───────────────────────────────

    @Test fun `Tasks lists the declared projects and opens one in cloud-tasks`() {
        val f = use(Fake(projects = Read.Ok(listOf(
            TaskProject("p1", "Work", 3, 1, "Ship boards", "2026-10-20"),
            TaskProject("p2", "Home", 0, 4, null, null),
        ))))
        showKit { BoardTaskProjectsBody(0) { opened += it } }
        waitForText("Work")
        compose.onNodeWithText("3 open · 1 done · Next: Ship boards · 2026-10-20").assertExists()
        compose.onNodeWithText("0 open · 4 done · Nothing due").assertExists()
        compose.onNodeWithTag(KitPartTags.listRow(BoardTags.project("p1"))).performClick()
        assertEquals(listOf("extapp:cloud-tasks#project:p1"), opened)
        assertTrue(f.calls > 0)
    }

    @Test fun `Tasks with no projects says so, and an absent app is not an empty list`() {
        use(Fake(projects = Read.Ok(emptyList())))
        showKit { BoardTaskProjectsBody(0) { opened += it } }
        waitForText("declares no projects")
    }

    @Test fun `an unreachable cloud-tasks shows the reason`() {
        use(Fake(projects = Read.Unavailable(Why.NOT_INSTALLED, "Cloud Tasks is not installed")))
        showKit { BoardTaskProjectsBody(0) { opened += it } }
        waitForText("Cloud Tasks is not installed")
    }

    @Test fun `My-health shows cloud-nav's steps, today and seven days`() {
        val today = LocalDate.of(2026, 10, 17)
        use(Fake(steps = Read.Ok(StepsBoard(4321, (6 downTo 0).map { StepsDay(today.minusDays(it.toLong()), 1000L * (it + 1)) },
            "HEALTH_CONNECT", "READY", "Health Connect · granted", null))))
        showKit { BoardStepsBody(0) { opened += it } }
        waitForText("4,321")
        compose.onNodeWithTag("steps-chart").assertExists()
        compose.onNodeWithText("Source: Health Connect via Cloud Nav").assertExists()
    }

    @Test fun `My-health without the permission says so instead of a zero`() {
        use(Fake(steps = Read.Ok(StepsBoard(0, emptyList(), "DEVICE", "NEEDS_PERMISSION",
            "Health Connect · read permission not granted", null))))
        showKit { BoardStepsBody(0) { opened += it } }
        waitForText("read permission not granted")
    }

    @Test fun `Nav tracker lists the places per day`() {
        val d = LocalDate.of(2026, 10, 16)
        use(Fake(places = Read.Ok(listOf(PlacesDay(d, listOf(
            PlaceVisit("08:00–08:30", "Calle Mayor, Madrid", 40.4168, -3.7038, false),
            PlaceVisit("10:00–10:40", "Paseo de la Castellana, Madrid", 40.45, -3.69, false),
        ), 0)))))
        showKit { BoardPlacesBody(0) { opened += it } }
        waitForText("Calle Mayor, Madrid")
        compose.onNodeWithText("Paseo de la Castellana, Madrid").assertExists()
        compose.onNodeWithTag("places-map:$d").assertExists()
    }

    @Test fun `Nav tracker with no fixes is honest`() {
        use(Fake(places = Read.Ok(listOf(PlacesDay(LocalDate.of(2026, 10, 16), emptyList(), 0)))))
        showKit { BoardPlacesBody(0) { opened += it } }
        waitForText("No places recorded")
    }

    @Test fun `Inboxes groups fleet mail and phone apps by app`() {
        val now = System.currentTimeMillis()
        use(Fake(
            mail = Read.Ok(listOf(InboxItem(Boards.MAIL_PKG, "Cloud Mail", "Ana", "Invoice October", now - 60_000, true,
                "extapp:cloud-mail", fleet = true))),
            phone = Read.Ok(listOf(
                InboxItem("com.whatsapp", "WhatsApp", "Luis", "See you at ten", now - 120_000, true, "app:com.whatsapp", "k1"),
                InboxItem("com.whatsapp", "WhatsApp", "Marta", "ok!", now - 300_000, true, "app:com.whatsapp", "k2"),
                InboxItem(Boards.MAIL_PKG, "Cloud Mail", "dup", "dup", now, true, "extapp:cloud-mail", "k3", fleet = true),
            )),
        ))
        showKit { BoardInboxBody(0) { opened += it } }
        waitFor(BoardTags.group("com.whatsapp"))
        compose.onNodeWithTag(BoardTags.group(Boards.MAIL_PKG)).assertExists()
        compose.onNodeWithText("Invoice October").assertExists()
        compose.onNodeWithText("2 unread").assertExists()
        // Mail's own notification is the same message the provider gave: not drawn twice.
        assertEquals(0, compose.onAllNodesWithText("dup").fetchSemanticsNodes().size)
        compose.onNodeWithText("See you at ten").performClick()
        assertEquals(listOf("app:com.whatsapp"), opened)
    }

    @Test fun `Inboxes without notification access says why`() {
        use(Fake(phone = Read.Unavailable(Why.NO_PERMISSION, "Notification Access is off, so other apps' messages cannot be read")))
        showKit { BoardInboxBody(0) { opened += it } }
        waitForText("Notification Access is off")
    }

    // ── the wire ────────────────────────────────────────────────────

    @Test fun `the fleet routes' JSON parses`() {
        val s = BoardsWire.steps("""{"today":12,"days":[{"date":"2026-10-17","steps":12},{"date":"2026-10-16","steps":900}],
            "source":"DEVICE","status":"DEVICE_FALLBACK","status_line":"x","hint":""}""")
        assertEquals(12, s.today); assertEquals(LocalDate.of(2026, 10, 16), s.days.first().date); assertTrue(s.ready)
        val p = BoardsWire.places("""{"days":[{"date":"2026-10-17","visits":[{"part":"08:00","label":"A","lat":1.0,"lon":2.0,"predicted":true}]}]}""")
        assertEquals("A", p.single().visits.single().label)
        val t = BoardsWire.projects("""{"projects":[{"id":"p","name":"N","open":1,"done":2,"next_due_name":"","next_due":""}]}""")
        assertEquals(TaskProject("p", "N", 1, 2, null, null), t.single())
    }
}
