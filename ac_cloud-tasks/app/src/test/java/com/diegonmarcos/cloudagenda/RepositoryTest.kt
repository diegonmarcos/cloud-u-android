package com.diegonmarcos.cloudagenda

import android.database.sqlite.SQLiteDatabase
import com.diegonmarcos.cloudagenda.data.Attachment
import com.diegonmarcos.cloudagenda.data.AttachmentKind
import com.diegonmarcos.cloudagenda.data.Migrations
import com.diegonmarcos.cloudagenda.data.ProposalDraft
import com.diegonmarcos.cloudagenda.data.ProposalKind
import com.diegonmarcos.cloudagenda.data.ProposalStatus
import com.diegonmarcos.cloudagenda.data.TaskStatus
import com.diegonmarcos.cloudagenda.data.TasksDb
import com.diegonmarcos.cloudagenda.data.TasksRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Data model, migrations and first-run seeding, on a real (in-memory) SQLite under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {

    private fun repo() = TasksRepository(TasksDb(RuntimeEnvironment.getApplication(), null))

    // ── seed ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `first run seeds Project Zero with two lists, three tasks each, two subtasks each`() {
        val r = repo()
        assertTrue(r.seedIfEmpty())
        val s = r.snapshot()
        assertEquals(listOf("Project Zero"), s.projects.map { it.name })
        val lists = s.listsOf(s.projects.single().id)
        assertEquals(listOf("Step 0", "Step 1"), lists.map { it.name })
        for (l in lists) {
            val tasks = s.topLevel(l.id)
            assertEquals(3, tasks.size)
            for (t in tasks) assertEquals(2, s.subtasksOf(t.id).size)
        }
        assertEquals(2 * 3 * (1 + 2), s.tasks.size)
    }

    @Test fun `seeding twice changes nothing`() {
        val r = repo()
        assertTrue(r.seedIfEmpty())
        val first = r.snapshot()
        assertFalse(r.seedIfEmpty())
        assertEquals(first, r.snapshot())
    }

    @Test fun `seeding never touches a database that already has a project`() {
        val r = repo()
        r.addProject("Mine")
        assertFalse(r.seedIfEmpty())
        assertEquals(listOf("Mine"), r.snapshot().projects.map { it.name })
    }

    @Test fun `deleting everything does not bring the sample data back`() {
        val r = repo()
        r.seedIfEmpty()
        r.snapshot().projects.forEach { r.deleteProject(it.id) }
        assertFalse(r.seedIfEmpty())
        assertTrue(r.snapshot().projects.isEmpty())
    }

    // ── migrations ──────────────────────────────────────────────────────────────────────────────────────

    private fun memoryDb(): SQLiteDatabase = SQLiteDatabase.create(null).also { it.setForeignKeyConstraintsEnabled(true) }

    private fun tables(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
        }

    @Test fun `a version 1 database with data upgrades to the latest with the data intact`() {
        val db = memoryDb()
        Migrations.apply(db, 0, 1)
        assertFalse("agent tables are a v2 addition", "agent_config" in tables(db))
        db.execSQL("INSERT INTO projects(id, name, position, created_at) VALUES ('p', 'Kept', 0, 1)")
        db.execSQL("INSERT INTO lists(id, project_id, name, position, created_at) VALUES ('l', 'p', 'List', 0, 1)")
        db.execSQL("INSERT INTO tasks(id, list_id, name, status) VALUES ('t', 'l', 'My task', 'waiting')")

        Migrations.apply(db, 1, Migrations.LATEST)

        assertTrue("agent_config" in tables(db) && "proposals" in tables(db))
        db.rawQuery("SELECT p.name, l.name, t.name, t.status FROM tasks t JOIN lists l ON l.id = t.list_id JOIN projects p ON p.id = l.project_id", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(listOf("Kept", "List", "My task", "waiting"), (0..3).map { c.getString(it) })
        }
        db.close()
    }

    @Test fun `a fresh install and an upgraded one end with the same tables`() {
        val fresh = memoryDb().also { Migrations.apply(it, 0) }
        val stepped = memoryDb().also { Migrations.apply(it, 0, 1); Migrations.apply(it, 1) }
        assertEquals(tables(fresh), tables(stepped))
        fresh.close(); stepped.close()
    }

    @Test fun `re-running a step over an existing schema is harmless`() {
        val db = memoryDb()
        Migrations.apply(db, 0)
        Migrations.apply(db, 0)
        db.close()
    }

    @Test fun `no migration step destroys user data`() {
        val forbidden = Regex("""\b(DROP\s+TABLE|DELETE\s+FROM|TRUNCATE|DROP\s+COLUMN)\b""", RegexOption.IGNORE_CASE)
        val steps = Migrations.STEPS
        assertEquals(steps.map { it.to }, (1..steps.size).toList())
        for (s in steps) for (sql in s.statements) assertFalse("step ${s.to}: $sql", forbidden.containsMatchIn(sql))
    }

    // ── tasks, subtasks, attachments ────────────────────────────────────────────────────────────────────

    @Test fun `a task keeps every field, its attachments and its tags`() {
        val r = repo()
        val p = r.addProject("P")
        val l = r.addList(p.id, "L")
        val tag = r.addTag("urgent", 0xFFE53935)
        val t = r.addTask(l.id, "Call dentist")
        val saved = t.copy(
            details = "Ask about the crown", dateTarget = "2026-11-02", dateDeadline = "2026-11-30", location = "Praxis Klein",
            status = TaskStatus.WAITING, tagIds = setOf(tag.id),
            attachments = listOf(
                Attachment("a1", AttachmentKind.MAIL, "mail:acc/m1", "Appointment mail"),
                Attachment("a2", AttachmentKind.IMAGE, "content://drive/img/1", "scan.jpg"),
                Attachment("a3", AttachmentKind.FILE, "content://drive/file/2", "invoice.pdf"),
            ),
        )
        r.saveTask(saved)
        assertEquals(saved, r.snapshot().task(t.id))
    }

    @Test fun `a subtask has the same fields as a task`() {
        val r = repo()
        val l = r.addList(r.addProject("P").id, "L")
        val parent = r.addTask(l.id, "Parent")
        val sub = r.addTask(l.id, "Child", parentId = parent.id)
        val edited = sub.copy(details = "d", dateTarget = "2026-01-01", dateDeadline = "2026-01-02", location = "here", status = TaskStatus.HOLD)
        r.saveTask(edited)
        val s = r.snapshot()
        assertEquals(edited, s.task(sub.id))
        assertEquals(listOf(sub.id), s.subtasksOf(parent.id).map { it.id })
        assertTrue(s.topLevel(l.id).none { it.id == sub.id })
    }

    @Test fun `deleting a task deletes its subtasks, a list its tasks, a project its lists`() {
        val r = repo()
        r.seedIfEmpty()
        val s0 = r.snapshot()
        val task = s0.topLevel(s0.lists.first().id).first()
        r.deleteTask(task.id)
        assertEquals(s0.tasks.size - 3, r.snapshot().tasks.size)
        r.deleteList(s0.lists.first().id)
        assertEquals(1, r.snapshot().lists.size)
        r.deleteProject(s0.projects.single().id)
        val empty = r.snapshot()
        assertTrue(empty.lists.isEmpty() && empty.tasks.isEmpty())
    }

    @Test fun `status changes persist`() {
        val r = repo()
        val t = r.addTask(r.addList(r.addProject("P").id, "L").id, "T")
        TaskStatus.entries.forEach {
            r.setStatus(t.id, it)
            assertEquals(it, r.snapshot().task(t.id)!!.status)
        }
    }

    // ── tags ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `tags are many-to-many and deleting one removes it from every task`() {
        val r = repo()
        val l = r.addList(r.addProject("P").id, "L")
        val a = r.addTag("a", 1); val b = r.addTag("b", 2)
        val t1 = r.addTask(l.id, "t1"); val t2 = r.addTask(l.id, "t2")
        r.saveTask(t1.copy(tagIds = setOf(a.id, b.id)))
        r.saveTask(t2.copy(tagIds = setOf(a.id)))
        assertEquals(setOf(a.id, b.id), r.snapshot().task(t1.id)!!.tagIds)
        r.deleteTag(a.id)
        val s = r.snapshot()
        assertEquals(setOf(b.id), s.task(t1.id)!!.tagIds)
        assertEquals(emptySet<String>(), s.task(t2.id)!!.tagIds)
        assertEquals(listOf("b"), s.tags.map { it.name })
    }

    @Test fun `tags can be renamed and recoloured, names stay unique`() {
        val r = repo()
        val a = r.addTag("Home", 0xFF111111); r.addTag("Work", 0xFF222222)
        assertTrue(r.renameTag(a.id, "House"))
        assertFalse("clashes with Work, any case", r.renameTag(a.id, "work"))
        assertFalse(r.renameTag(a.id, "  "))
        r.recolorTag(a.id, 0xFF00FF00)
        val now = r.snapshot().tag(a.id)!!
        assertEquals("House", now.name)
        assertEquals(0xFF00FF00, now.color)
        assertEquals(a.id, r.addTag("HOUSE", 5).id)
    }

    // ── agent: declaration and proposals ────────────────────────────────────────────────────────────────

    @Test fun `the agent declaration is stored and reloaded as saved, and the run columns survive a save`() {
        val r = repo()
        assertFalse(r.agentConfig().enabled)
        val cfg = r.agentConfig().copy(enabled = true, account = "acc", mailboxes = "inbox, bills", frequencyMinutes = 60, model = "m", extractInvoices = false, targetListId = "L")
        r.saveAgentConfig(cfg)
        r.recordAgentRun(123, "backend_not_deployed", "x")
        r.saveAgentConfig(cfg.copy(account = "other"))
        val back = r.agentConfig()
        assertEquals("other", back.account)
        assertEquals(setOf("inbox", "bills"), back.mailboxSet)
        assertEquals(listOf(ProposalKind.EVENT, ProposalKind.BOOKING, ProposalKind.DEADLINE), back.kinds)
        assertEquals(123L, back.lastRunAt)
        assertEquals("backend_not_deployed", back.lastStatus)
    }

    @Test fun `a proposal creates nothing until it is approved, and approving creates the task once`() {
        val r = repo()
        val l = r.addList(r.addProject("P").id, "L")
        val d = ProposalDraft(ProposalKind.DEADLINE, "Pay rent", "due", null, "2026-12-01", "", "mail:acc/m9", "Rent reminder", 0.9)
        assertEquals(1, r.addProposals(listOf(d)))
        assertEquals(0, r.addProposals(listOf(d)))
        assertTrue(r.snapshot().tasks.isEmpty())
        val id = r.proposals().single().id
        val task = r.decideProposal(id, approve = true, listId = l.id)
        assertNotNull(task)
        val stored = r.snapshot().task(task!!.id)!!
        assertEquals("Pay rent", stored.name)
        assertEquals("2026-12-01", stored.dateDeadline)
        assertEquals(AttachmentKind.MAIL, stored.attachments.single().kind)
        assertTrue(r.proposals().isEmpty())
        assertEquals(ProposalStatus.APPROVED, r.proposals(ProposalStatus.APPROVED).single().status)
        assertNull("deciding twice does nothing", r.decideProposal(id, approve = true, listId = l.id))
        assertEquals(1, r.snapshot().tasks.size)
    }

    @Test fun `rejecting a proposal creates no task and keeps it from coming back`() {
        val r = repo()
        val d = ProposalDraft(ProposalKind.EVENT, "Concert", sourceRef = "mail:a/1")
        r.addProposals(listOf(d))
        assertNull(r.decideProposal(r.proposals().single().id, approve = false, listId = null))
        assertTrue(r.snapshot().tasks.isEmpty())
        assertEquals(0, r.addProposals(listOf(d)))
        assertEquals(1, r.proposals(ProposalStatus.REJECTED).size)
    }
}
