package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.decisions.link.Answer
import com.diegonmarcos.superapp.decisions.link.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #883 alert_interrupt: THE FALLBACK TEST. "Jev has no opinion" must be exactly today's behaviour: the fresh
 * alert rings and every other alert is silent ([AlertsNotifier.silent]), whatever the engine said or did not.
 */
class JevNotifyTest {

    private fun verdict(value: Boolean, confident: Boolean) = Verdict(
        "alert_interrupt", "user_facing", false, 0.8, false,
        mapOf("worth" to Answer("noul", if (value) 0.95 else 0.05, confident, value, null, null, emptyMap())),
    )

    @Test fun noVerdictIsTodaysBehaviourForEveryAlert() {
        val ids = listOf("a", "b", "c")
        for (fresh in listOf(null, "a", "c", "zzz")) for (id in ids) {
            assertEquals("fresh=$fresh id=$id", id != fresh, AlertsNotifier.silent(id, fresh, emptySet()))
        }
        assertFalse(JevNotify.quiet(null))
    }

    @Test fun onlyAConfidentNotWorthItMakesTheFreshAlertSilent() {
        assertTrue(JevNotify.quiet(verdict(value = false, confident = true)))
        assertFalse("unsure is no answer", JevNotify.quiet(verdict(value = false, confident = false)))
        assertFalse("worth it rings", JevNotify.quiet(verdict(value = true, confident = true)))
        assertTrue(AlertsNotifier.silent("a", "a", setOf("a")))
        assertFalse(AlertsNotifier.silent("a", "a", setOf("b")))
        assertTrue("the others were silent anyway", AlertsNotifier.silent("b", "a", emptySet()))
    }

    @Test fun aQuickSwipeAwayIsTheOnlyObservedLabel() {
        assertEquals(false, JevNotify.worthLabel(0))
        assertEquals(false, JevNotify.worthLabel(JevNotify.QUICK_DISMISS_MS - 1))
        assertNull(JevNotify.worthLabel(JevNotify.QUICK_DISMISS_MS))
        assertNull(JevNotify.worthLabel(-1))
    }

    @Test fun theQuestionNamesTheAlertAndNeverItsBodyText() {
        val a = AlertStore.Alert("id1", "com.x.app", "Backup failed", "SECRET BODY TEXT", "error", "", "k", 1L)
        val s = JevNotify.state(a, 14).toString()
        assertTrue(s.contains("Backup failed") && s.contains("com.x.app") && s.contains("error") && s.contains("14"))
        assertFalse(s.contains("SECRET BODY TEXT"))
        val q = JevNotify.questions().getJSONObject("worth")
        assertEquals("noul", q.getString("type"))
    }
}
