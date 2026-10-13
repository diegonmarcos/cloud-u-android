package app.sterna.core.data.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A task attaches to a mail, and both its dates (and every detail) survive the task store and Mail's own copy. */
class MailTaskTest {

    private val day = 86_400_000L
    private val task = MailTask(
        uid = "t1", title = "Answer the offer; today", accountId = "acc-1", emailId = "M42",
        mailSubject = "Offer, final", messageId = "<abc@example.org>",
        deadlineMillis = 20_000 * day, targetMillis = 19_990 * day,
        notes = "line one\nline two", links = listOf("https://example.org/a,b"), location = "Berlin; office",
        priority = 1, status = MailTask.STATUS_DOING, people = listOf("ana@example.org", "Bob Smith"),
    )

    @Test fun `the store's json carries the deadline as DUE and the target as DTSTART`() {
        val json = toTodoJson(task)
        assertTrue(json, json.contains("\"dueUtcMillis\":${20_000 * day}"))
        val lines = extraLines(task)
        assertTrue(lines.toString(), "DTSTART;VALUE=DATE:20240924" in lines)
        assertTrue(lines.toString(), "URL:mid:abc@example.org" in lines)
        assertTrue(lines.toString(), "X-CLOUD-MAIL:acc-1;M42" in lines)
    }

    @Test fun `the task reads back whole - attached to its mail, both dates kept`() {
        assertEquals(task, mailTaskFromTodoJson(toTodoJson(task))?.copy(messageId = "<abc@example.org>"))
        val back = mailTaskFromTodoJson(toTodoJson(task))!!
        assertEquals(20_000 * day, back.deadlineMillis)
        assertEquals(19_990 * day, back.targetMillis)
        assertEquals("acc-1" to "M42", back.accountId to back.emailId)
    }

    @Test fun `mail's own list persists both dates across a save`() {
        val saved = decodeTaskList(encodeTaskList(upsertTask(emptyList(), task)))
        assertEquals(1, saved.size)
        assertEquals(task.deadlineMillis, saved[0].deadlineMillis)
        assertEquals(task.targetMillis, saved[0].targetMillis)
        assertEquals(listOf(saved[0]), tasksOfMail(saved, "acc-1", "M42"))
    }

    @Test fun `a task of the store with no mail is not an Assigned one`() {
        assertNull(mailTaskFromTodoJson("""{"uid":"x","summary":"buy milk","unknownLines":[]}"""))
    }

    @Test fun `assigned sorts by either date, undated last`() {
        val a = task.copy(uid = "a", deadlineMillis = 3, targetMillis = 1)
        val b = task.copy(uid = "b", deadlineMillis = 1, targetMillis = 3)
        val c = task.copy(uid = "c", deadlineMillis = null, targetMillis = null)
        assertEquals(listOf("b", "a", "c"), sortAssigned(listOf(c, a, b), AssignedSort.DEADLINE).map { it.uid })
        assertEquals(listOf("a", "b", "c"), sortAssigned(listOf(c, b, a), AssignedSort.TARGET).map { it.uid })
    }
}
