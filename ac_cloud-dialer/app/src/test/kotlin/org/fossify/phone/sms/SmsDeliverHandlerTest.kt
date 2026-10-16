package org.fossify.phone.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SMS_DELIVER path: the receiver writes to the provider when (and only when) we are the default SMS app. */
class SmsDeliverHandlerTest {
    private class Row(val address: String, val body: String, val date: Long, val sub: Int, val read: Boolean)

    private class FakeInbox(private val refuse: Boolean = false) : SmsInbox {
        val rows = ArrayList<Row>()
        override fun insertReceived(address: String, body: String, dateMs: Long, subscriptionId: Int, read: Boolean): Long? {
            if (refuse) return null
            rows.add(Row(address, body, dateMs, subscriptionId, read))
            return rows.size.toLong()
        }
    }

    private class FakeNotifier : SmsNotifier {
        val notified = ArrayList<Pair<String, String>>()
        override fun notifyReceived(address: String, body: String, messageId: Long) { notified.add(address to body) }
    }

    private fun handler(default: Boolean, inbox: SmsInbox, notifier: SmsNotifier, blocked: Set<String> = emptySet()) =
        SmsDeliverHandler({ default }, inbox, { it in blocked }, notifier)

    @Test
    fun whenNotTheDefaultAppNothingIsWrittenOrPosted() {
        val inbox = FakeInbox()
        val notifier = FakeNotifier()
        val result = handler(false, inbox, notifier).handle(listOf(IncomingSms("+1555", "hi", 1000, 1)))
        assertTrue(result.ignored)
        assertTrue(inbox.rows.isEmpty())
        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun whenTheDefaultAppTheMessageIsStoredUnreadAndNotified() {
        val inbox = FakeInbox()
        val notifier = FakeNotifier()
        val result = handler(true, inbox, notifier).handle(listOf(IncomingSms("+1555", "hi", 1000, 3)))
        assertFalse(result.ignored)
        assertEquals(1, result.stored)
        assertEquals(1, result.notified)
        val row = inbox.rows.single()
        assertEquals("+1555", row.address)
        assertEquals("hi", row.body)
        assertEquals(1000L, row.date)
        assertEquals(3, row.sub)
        assertFalse(row.read)
        assertEquals(listOf("+1555" to "hi"), notifier.notified)
    }

    @Test
    fun thePartsOfOneMultipartMessageBecomeOneRow() {
        val inbox = FakeInbox()
        val notifier = FakeNotifier()
        val result = handler(true, inbox, notifier).handle(
            listOf(IncomingSms("+1555", "first ", 1000, 1), IncomingSms("+1555", "second", 1000, 1), IncomingSms("+1666", "other", 1100, 1)),
        )
        assertEquals(2, result.stored)
        assertEquals(listOf("first second", "other"), inbox.rows.map { it.body })
        assertEquals(2, notifier.notified.size)
    }

    @Test
    fun aBlockedSenderIsStoredReadAndNeverNotified() {
        val inbox = FakeInbox()
        val notifier = FakeNotifier()
        val result = handler(true, inbox, notifier, blocked = setOf("+1999")).handle(
            listOf(IncomingSms("+1999", "buy now", 1000, 1), IncomingSms("+1555", "hi", 1001, 1)),
        )
        assertEquals(2, result.stored)
        assertEquals(1, result.blocked)
        assertEquals(1, result.notified)
        assertTrue(inbox.rows.first { it.address == "+1999" }.read)
        assertFalse(inbox.rows.first { it.address == "+1555" }.read)
        assertEquals(listOf("+1555" to "hi"), notifier.notified)
    }

    @Test
    fun aRefusedWriteIsNotCountedOrNotified() {
        val notifier = FakeNotifier()
        val result = handler(true, FakeInbox(refuse = true), notifier).handle(listOf(IncomingSms("+1555", "hi", 1000, 1)))
        assertEquals(0, result.stored)
        assertEquals(0, result.notified)
        assertTrue(notifier.notified.isEmpty())
    }
}
