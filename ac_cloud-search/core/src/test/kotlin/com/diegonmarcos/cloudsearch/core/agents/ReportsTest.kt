package com.diegonmarcos.cloudsearch.core.agents

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsTest {
    private fun report(id: String, at: Long, items: List<ReportItem> = emptyList()) =
        Report(id, "house_search", "run-$id", "house_search_digest", "Digest $id", at, "summary $id", items)

    @Test fun newestFirstAndReplaceById() {
        val b = ReportBook()
        b.publish(report("a", 100)); b.publish(report("c", 300)); b.publish(report("b", 200))
        assertEquals(listOf("c", "b", "a"), b.newestFirst().map { it.id })
        b.publish(report("a", 400).copy(summary = "again"))
        assertEquals(listOf("a", "c", "b"), b.newestFirst().map { it.id })
        assertEquals("again", b.get("a")!!.summary)
        assertEquals(3, b.newestFirst().size)
    }

    @Test fun theBookIsBounded() {
        val b = ReportBook(cap = 3)
        for (i in 1..5) b.publish(report("r$i", i.toLong()))
        assertEquals(listOf("r5", "r4", "r3"), b.newestFirst().map { it.id })
        assertNull(b.get("r1"))
        val loaded = ReportBook((1..5).map { report("x$it", it.toLong()) }, cap = 2)
        assertEquals(listOf("x5", "x4"), loaded.newestFirst().map { it.id })
    }

    @Test fun deleteAndItemStatus() {
        val b = ReportBook()
        b.publish(report("a", 1, listOf(ReportItem("i1", "t", "u", "m", ReportItem.DRAFT), ReportItem("i2", "t", "u", "m", ReportItem.DRAFT))))
        b.publish(report("b", 2, listOf(ReportItem("i1", "t", "u", "m", ReportItem.DRAFT))))
        b.setItemStatus("a", "i1", ReportItem.SENT_BY_ME)
        assertEquals(listOf(ReportItem.SENT_BY_ME, ReportItem.DRAFT), b.get("a")!!.items.map { it.status })
        assertEquals(ReportItem.DRAFT, b.get("b")!!.items[0].status)
        b.delete("a")
        assertNull(b.get("a"))
        assertEquals(1, b.newestFirst().size)
    }

    @Test fun jsonRoundTrip() {
        val b = ReportBook()
        b.publish(report("a", 5, listOf(ReportItem("i1", "Titel", "https://x.example/1", "Hallo\nWelt", ReportItem.DISMISSED, "note"))))
        b.publish(report("b", 9))
        val again = ReportBook.fromJson(b.toJson().toString())
        assertEquals(b.newestFirst(), again.newestFirst())
        assertEquals("note", again.get("a")!!.items[0].note)
    }

    @Test fun anUnreadableFileIsAnEmptyBook() {
        assertTrue(ReportBook.fromJson("{not json").newestFirst().isEmpty())
        assertTrue(ReportBook.fromJson("").newestFirst().isEmpty())
    }

    @Test fun itemDefaultsWhenFieldsAreMissing() {
        val i = ReportItem.fromJson(JSONObject("""{"id":"x"}"""))
        assertEquals(ReportItem.DRAFT, i.status)
        assertEquals("", i.url)
    }

    @Test fun runRecordsRoundTripAndTolerateGarbage() {
        val r = RunRecord("r1", "house_search", 10, 20, RunRecord.OK, "s", 2, 3, 3, 3, 2, 0.01, listOf(AuditEvent(10, AuditEvent.DRAFT, "u", "d")))
        val again = RunRecord.listFromJson(org.json.JSONArray(listOf(r.toJson())).toString())
        assertEquals(listOf(r), again)
        assertTrue(RunRecord.listFromJson("oops").isEmpty())
    }

    @Test fun theAuditLineIsBoundedAndOneLine() {
        val log = AuditLog(cap = 2)
        log.add(1, AuditEvent.MAIL_READ, "a\n b\t c", "x".repeat(400))
        log.add(2, AuditEvent.DRAFT, "u")
        log.add(3, AuditEvent.DRAFT, "dropped")
        assertEquals(2, log.events().size)
        assertEquals(1, log.dropped)
        assertEquals("a b c", log.events()[0].subject)
        assertEquals(AuditLog.MAX_FIELD, log.events()[0].detail.length)
        assertTrue(log.events()[0].detail.endsWith("…"))
        assertFalse(log.events().any { it.subject == "dropped" })
        assertEquals(log.events(), AuditLog.eventsFromJson(log.toJson()))
        assertTrue(AuditLog.eventsFromJson(null).isEmpty())
    }
}

class DigestTest {
    private val agent = com.diegonmarcos.cloudsearch.core.Fixtures.cfg.agents!!.agent("house_search")!!

    @Test fun aRunBecomesOneReportWithAnItemPerDraft() {
        val rec = RunRecord("r9", agent.id, 10, 50, RunRecord.OK, "2 alert mail(s)", 2, 2, 2, 2, 2, 0.0123, emptyList())
        val drafts = listOf(
            DraftRunner.Draft("1", "https://x.example/1", "Zimmer 1", "Hallo 1", true, true, emptyList(), "", 0.01),
            DraftRunner.Draft("2", "https://x.example/2", "Zimmer 2", "Hallo 2", false, false, listOf("name"), "missing: name", 0.0),
        )
        val r = Reports.digest(agent, DraftRunner.Outcome(rec, drafts, setOf("1")))
        assertEquals("r9", r.id); assertEquals("r9", r.runId); assertEquals(agent.id, r.agentId)
        assertEquals("house_search_digest", r.kind)
        assertEquals("House search digest", r.title)
        assertEquals(50L, r.createdAt)
        assertEquals("2 alert mail(s) · \$0.0123", r.summary)
        assertEquals(listOf("1", "2"), r.items.map { it.id })
        assertEquals("Hallo 2", r.items[1].body)
        assertEquals("missing: name", r.items[1].note)
        assertTrue(r.items.all { it.status == ReportItem.DRAFT })
    }

    @Test fun noCostNoCostInTheSummary() {
        val rec = RunRecord("r", agent.id, 1, 2, RunRecord.OK, "s", 0, 0, 0, 0, 0, 0.0, emptyList())
        assertEquals("s", Reports.digest(agent, DraftRunner.Outcome(rec, emptyList(), emptySet())).summary)
    }
}
