package org.fossify.phone.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadsTest {
    private fun msg(id: Long, thread: Long, date: Long, type: Int = SmsType.INBOX, read: Boolean = true, address: String = "555", body: String = "m$id", status: Int = SmsStatusCode.NONE) =
        SmsMessage(id, thread, address, body, date, type, read, status)

    @Test
    fun messagesGroupByThreadOldestFirst() {
        val grouped = Threads.group(listOf(msg(3, 1, 300), msg(1, 1, 100), msg(2, 2, 200), msg(4, 1, 200)))
        assertEquals(setOf(1L, 2L), grouped.keys)
        assertEquals(listOf(1L, 4L, 3L), grouped.getValue(1).map { it.id })
        assertEquals(listOf(2L), grouped.getValue(2).map { it.id })
    }

    @Test
    fun looseMessagesBecomeConversationsNewestFirstWithUnreadCounts() {
        val list = Threads.conversationsOf(
            listOf(
                msg(1, 1, 100, read = false, address = "111"), msg(2, 1, 400, read = false, address = "111"), msg(3, 1, 500, type = SmsType.SENT, address = "111"),
                msg(4, 2, 450, address = "222"),
            ),
            nameOf = { if (it == "111") "Ann" else null },
        )
        assertEquals(listOf(1L, 2L), list.map { it.threadId })
        assertEquals("Ann", list[0].title)
        assertEquals(2, list[0].unreadCount)
        assertEquals(3, list[0].messageCount)
        assertEquals("m3", list[0].snippet)
        assertEquals("222", list[1].title)
        assertEquals(0, list[1].unreadCount)
    }

    @Test
    fun threadRowsResolveRecipientsNamesUnreadAndDrafts() {
        val rows = listOf(
            ThreadRow(1, 1000, 5, "10", "hello"),
            ThreadRow(2, 3000, 2, "11 12", "group text"),
            ThreadRow(3, 2000, 0, "13", ""),            // an empty husk of a deleted conversation
            ThreadRow(4, 500, 1, "99", "unresolvable"), // recipient id with no address
        )
        val addresses = mapOf(10L to "111", 11L to "222", 12L to "333", 13L to "444")
        val list = Conversations.build(rows, { addresses[it] }, { if (it == "111") "Ann" else null }, unread = mapOf(1L to 3), drafts = mapOf(1L to "half typed"))
        assertEquals(listOf(2L, 1L), list.map { it.threadId })
        val group = list[0]
        assertTrue(group.isGroup)
        assertEquals("222, 333", group.title)
        val ann = list[1]
        assertEquals("Ann", ann.title)
        assertEquals(3, ann.unreadCount)
        assertTrue(ann.isUnread)
        assertEquals("half typed", ann.snippet)
        assertEquals("half typed", ann.draft)
    }

    @Test
    fun pinnedConversationsComeFirstAndArchivedLeaveTheInbox() {
        val rows = listOf(ThreadRow(1, 100, 1, "10", "old"), ThreadRow(2, 300, 1, "11", "new"), ThreadRow(3, 200, 1, "12", "mid"))
        val addr = mapOf(10L to "a", 11L to "b", 12L to "c")
        val all = Conversations.build(rows, { addr[it] }, { null }, pinned = setOf(1L), archived = setOf(3L))
        assertEquals(listOf(1L, 2L, 3L), all.map { it.threadId })
        assertEquals(listOf(1L, 2L), Conversations.visible(all, showArchived = false, query = "").map { it.threadId })
        assertEquals(listOf(3L), Conversations.visible(all, showArchived = true, query = "").map { it.threadId })
    }

    @Test
    fun searchMatchesTitleNumberAndSnippet() {
        val rows = listOf(ThreadRow(1, 100, 1, "10", "dinner at eight"), ThreadRow(2, 200, 1, "11", "see you"))
        val addr = mapOf(10L to "+15550001", 11L to "+15550002")
        val all = Conversations.build(rows, { addr[it] }, { if (it == "+15550002") "Bob Marley" else null })
        assertEquals(listOf(1L), Conversations.visible(all, false, "dinner").map { it.threadId })
        assertEquals(listOf(2L), Conversations.visible(all, false, "marley").map { it.threadId })
        assertEquals(listOf(1L), Conversations.visible(all, false, "0001").map { it.threadId })
        assertEquals(2, Conversations.visible(all, false, "  ").size)
        assertTrue(Conversations.visible(all, false, "zzz").isEmpty())
    }

    @Test
    fun deliveryStateFollowsTypeAndStatus() {
        assertEquals(DeliveryState.Received, msg(1, 1, 1, SmsType.INBOX).deliveryState())
        assertEquals(DeliveryState.Sending, msg(1, 1, 1, SmsType.OUTBOX).deliveryState())
        assertEquals(DeliveryState.Sending, msg(1, 1, 1, SmsType.QUEUED).deliveryState())
        assertEquals(DeliveryState.Sent, msg(1, 1, 1, SmsType.SENT).deliveryState())
        assertEquals(DeliveryState.Delivered, msg(1, 1, 1, SmsType.SENT, status = SmsStatusCode.COMPLETE).deliveryState())
        assertEquals(DeliveryState.Failed, msg(1, 1, 1, SmsType.FAILED).deliveryState())
        assertEquals(DeliveryState.Failed, msg(1, 1, 1, SmsType.SENT, status = SmsStatusCode.FAILED).deliveryState())
        assertEquals(DeliveryState.Draft, msg(1, 1, 1, SmsType.DRAFT).deliveryState())
        assertTrue(msg(1, 1, 1, SmsType.FAILED).canRetry)
        assertFalse(msg(1, 1, 1, SmsType.SENT).canRetry)
        assertFalse(msg(1, 1, 1, SmsType.INBOX).isOutgoing)
        assertTrue(msg(1, 1, 1, SmsType.SENT).isOutgoing)
    }

    @Test
    fun dayStartsMarkEachNewDay() {
        val day = 86_400_000L
        val list = listOf(msg(1, 1, 10), msg(2, 1, 20), msg(3, 1, day + 5), msg(4, 1, 3 * day))
        assertEquals(listOf(0, 2, 3), Threads.dayStarts(list) { it / day })
    }

    @Test
    fun recipientSuggestionsMatchNameOrNumberAndOfferATypedNumber() {
        val all = listOf(Recipient("Ann Lee", "+1 555 000 1", null), Recipient("Bob", "+1 555 000 2", null))
        assertEquals(listOf("Ann Lee"), Recipients.suggest(all, "ann").map { it.name })
        assertTrue("Bob" in Recipients.suggest(all, "0002").map { it.name })
        assertFalse("Ann Lee" in Recipients.suggest(all, "0002").map { it.name })
        val typed = Recipients.suggest(all, "+49301234567")
        assertEquals("+49301234567", typed.first().number)
        assertEquals(2, Recipients.suggest(all, "").size)
    }
}
