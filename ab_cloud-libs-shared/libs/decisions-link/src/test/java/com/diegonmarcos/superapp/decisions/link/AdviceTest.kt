package com.diegonmarcos.superapp.decisions.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AdviceTest {

    @Test fun withinReturnsTheValueOrNullOnTimeoutOrFailure() {
        assertEquals("x", Advice.within(1000) { "x" })
        assertNull(Advice.within(50) { Thread.sleep(500); "late" })
        assertNull(Advice.within(1000) { error("boom") })
        assertNull(Advice.within<String>(1000) { null })
    }

    @Test fun aMissStartsOneBackgroundAskAndTheNextPeekSeesItsAnswer() {
        val s = Advice.Suggestions()
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val done = CountDownLatch(1)
        val first = s.peekOrAsk("k", 60_000) { asks.incrementAndGet(); done.countDown(); Advice.Suggestion("a", "c1") }
        assertNull("a miss is today's behaviour: nothing yet", first)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        var got: Advice.Suggestion? = null
        for (i in 1..50) { got = s.peek("k"); if (got != null) break; Thread.sleep(20) }
        assertEquals("a", got?.pick)
        assertEquals("c1", got?.call)
        s.peekOrAsk("k", 60_000) { asks.incrementAndGet(); null }
        assertEquals("a standing answer is not asked again", 1, asks.get())
    }

    @Test fun noOpinionIsRememberedToo() {
        val s = Advice.Suggestions()
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val done = CountDownLatch(1)
        s.peekOrAsk("k", 60_000) { asks.incrementAndGet(); done.countDown(); null }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertNull(s.peekOrAsk("k", 60_000) { asks.incrementAndGet(); Advice.Suggestion("z", null) })
        assertEquals(1, asks.get())
    }

    @Test fun anAnswerExpiresAfterItsTtl() {
        val s = Advice.Suggestions()
        s.put("k", Advice.Suggestion("a", null), 1000, now = 10_000)
        assertEquals("a", s.peek("k", now = 10_999)?.pick)
        assertNull(s.peek("k", now = 11_000))
    }

    @Test fun theMemoIsBounded() {
        val s = Advice.Suggestions(max = 2)
        s.put("a", Advice.Suggestion("1", null), 1000, now = 0)
        s.put("b", Advice.Suggestion("2", null), 1000, now = 0)
        s.put("c", Advice.Suggestion("3", null), 1000, now = 0)
        assertNull(s.peek("a", now = 1))
        assertEquals("3", s.peek("c", now = 1)?.pick)
    }

    private fun tmp() = File.createTempFile("pending", ".json").also { it.delete() }

    @Test fun pendingSuggestionsAreReportedOnlyOncePastTheWindow() {
        val p = Advice.Pending(tmp())
        p.add(Advice.Entry("u", "c1", "a", at = 1000))
        p.add(Advice.Entry("u", "c2", true, at = 5000))
        assertTrue(p.takeDue(now = 1500, windowMs = 1000).isEmpty())
        val due = p.takeDue(now = 2000, windowMs = 1000)
        assertEquals(listOf("c1"), due.map { it.call })
        assertEquals("a", due.single().label)
        assertTrue("taken once", p.takeDue(now = 2000, windowMs = 1000).isEmpty())
        assertEquals(listOf(true), p.takeDue(now = 9000, windowMs = 1000).map { it.label })
    }

    @Test fun anExplicitCorrectionRemovesThePendingEntry() {
        val p = Advice.Pending(tmp())
        p.add(Advice.Entry("u", "c1", "a", at = 0))
        p.drop("c1")
        assertTrue(p.takeDue(now = 1_000_000, windowMs = 1).isEmpty())
    }

    @Test fun theFileIsBoundedAndATornFileIsEmptyNotACrash() {
        val f = tmp()
        val p = Advice.Pending(f, max = 3)
        for (i in 1..5) p.add(Advice.Entry("u", "c$i", "a", at = 0))
        assertEquals(listOf("c3", "c4", "c5"), p.takeDue(1, 1).map { it.call })
        f.writeText("{not json")
        assertTrue(p.takeDue(1, 1).isEmpty())
        p.add(Advice.Entry("u", "c9", "a", at = 0))
        assertEquals(listOf("c9"), p.takeDue(1, 1).map { it.call })
    }

    @Test fun aRepeatedCallReplacesItsEntry() {
        val p = Advice.Pending(tmp())
        p.add(Advice.Entry("u", "c1", "a", at = 0))
        p.add(Advice.Entry("u", "c1", "b", at = 5))
        val due = p.takeDue(100, 1)
        assertEquals(1, due.size)
        assertEquals("b", due.single().label)
    }
}
