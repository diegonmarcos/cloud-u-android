package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.cloud.GitHubFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #773 — the aggregator page's rules, now pure functions ([AggregatorModel]) behind the Compose page that replaced the
 * 3600-line View fragment. The page only paints what these return.
 *
 *   M1 read is EXPLICIT: a row with an id is unread until its id is in the read set; a row without one falls back to arrival
 *   M2 the chip counts ARRIVAL (since the previous visit), not attention
 *   M3 Show=Unread keeps the unread rows, always newest first; Sort orders the GROUPS, never the rows
 *   M4 an empty group is not drawn; a group the filter emptied leaves with its rows
 *   M5 a channel never measured, or whose poll failed, sorts last and makes no claim about when it spoke
 *   M6 the taxonomy rows: "All" keeps everything, a value is a SET of section prefixes
 *   M7 the run arithmetic: finished runs are the denominator, cancelled is not a failure, an empty window says so
 *   M8 the wording of an empty / stale feed names the cause
 */
class AggregatorModelTest {

    private fun row(id: String, ts: Long) = NotifRow(ts = ts, title = "t$id", text = "", id = id)
    private fun group(key: String, label: String, vararg rows: NotifRow) =
        NotifGroup(key = key, label = label, sub = key, rows = rows.toList())

    @Test
    fun `M1 an id'd row is unread until its id is read, an id-less row follows the arrival watermark`() {
        val r = row("phone:1", 500)
        assertTrue(AggregatorModel.isUnread(r, emptySet(), visitSeenAt = 9_999))
        assertFalse(AggregatorModel.isUnread(r, setOf("phone:1"), visitSeenAt = 0))
        val anon = NotifRow(ts = 500, title = "a", text = "")
        assertTrue(AggregatorModel.isUnread(anon, emptySet(), visitSeenAt = 100))
        assertFalse(AggregatorModel.isUnread(anon, emptySet(), visitSeenAt = 500))
    }

    @Test
    fun `M2 the chip counts what arrived since the previous visit`() {
        val g = group("k", "K", row("a", 100), row("b", 900), row("c", 950))
        val fresh = AggregatorModel.chipFor(g, visitSeenAt = 500, now = 1_000)
        assertEquals("2 new · 3", fresh.text)
        assertEquals(Tone.OK, fresh.tone)
        val quiet = AggregatorModel.chipFor(g, visitSeenAt = 5_000, now = 1_000 + 120_000)
        assertEquals("3 · 2m ago", quiet.text)
        assertEquals(Tone.DIM, quiet.tone)
    }

    @Test
    fun `M3 Show unread drops read rows, rows are newest first under either Sort, Sort orders the groups`() {
        val a = group("a", "Zulu", row("a1", 100), row("a2", 300), row("a3", 200))
        val b = group("b", "alpha", row("b1", 400))
        val read = setOf("a2")
        val unread = AggregatorModel.visibleGroups(listOf(a, b), "time", "unread", read, 0, 1_000)
        assertEquals(listOf("b", "a"), unread.map { it.group.key })          // newest group first
        assertEquals(listOf("a3", "a1"), unread[1].group.rows.map { it.id }) // a2 is read; newest first
        val byApp = AggregatorModel.visibleGroups(listOf(a, b), "app", "all", read, 0, 1_000)
        assertEquals(listOf("alpha", "Zulu"), byApp.map { it.group.label })   // case-insensitive A-Z
        assertEquals(listOf("a2", "a3", "a1"), byApp[1].group.rows.map { it.id })
    }

    @Test
    fun `M4 a group the filter emptied is not drawn`() {
        val a = group("a", "A", row("a1", 100))
        val shown = AggregatorModel.visibleGroups(listOf(a), "time", "unread", setOf("a1"), 0, 1_000)
        assertTrue(shown.isEmpty())
        assertEquals("Nothing new · 7 older", AggregatorModel.filteredAwayNote(7))
    }

    @Test
    fun `M5 an unmeasured or failed channel ranks last`() {
        val cache = mapOf(
            "up" to NtfyResult(true, "", listOf(row("x", 700))),
            "down" to NtfyResult(false, "no route", emptyList()),
        )
        assertEquals(700L, AggregatorModel.newestMeasured(cache, "up"))
        assertEquals(Long.MIN_VALUE, AggregatorModel.newestMeasured(cache, "down"))
        assertEquals(Long.MIN_VALUE, AggregatorModel.newestMeasured(cache, "never-polled"))
    }

    @Test
    fun `M6 the taxonomy rows narrow by a set of section prefixes`() {
        assertNull(AggregatorModel.wantedPrefixes("all", "all"))
        assertEquals("@", AggregatorModel.wantedPrefixes("@", "all"))
        assertEquals("-+", AggregatorModel.wantedPrefixes("all", "-+"))
        // "All" keeps every publisher, with or without a declared prefix.
        assertTrue(AggregatorModel.cloudTaxonomyKeeps(null, "anything"))
    }

    private fun run(conclusion: String, ts: Long) = GitHubFeed.Run(
        name = "ship", displayTitle = "t", status = if (conclusion.isBlank()) "in_progress" else "completed",
        conclusion = conclusion, htmlUrl = "", tsMillis = ts,
    )

    @Test
    fun `M7 the verdict counts finished runs and does not call a cancelled run a failure`() {
        val now = 10_000_000L
        val runs = listOf(run("success", now - 3_600_000), run("success", now - 7_200_000),
            run("failure", now - 100), run("cancelled", now - 50), run("", now - 10))
        val (text, severity) = AggregatorModel.runVerdict(runs, now)
        // 4 finished, 2 green -> 50%; only the failure counts as failed; the last green was an hour ago.
        assertEquals("5 runs · 50% green · 1 failed · last green 1h ago", text)
        assertEquals("error", severity)
        val none = AggregatorModel.runVerdict(listOf(run("", now), run("", now)), now)
        assertEquals("no verdicts yet (2 still running or queued)", none.first)
        assertEquals("idle", none.second)
        val clean = AggregatorModel.runVerdict(listOf(run("success", now)), now)
        assertEquals("info", clean.second)
        assertEquals("error", AggregatorModel.runSeverity("timed_out"))
        assertEquals("info", AggregatorModel.runSeverity("success"))
        assertEquals("warn", AggregatorModel.runSeverity("in_progress"))
    }

    @Test
    fun `M8 an empty feed names its cause, a stale one says it is cached`() {
        assertTrue(AggregatorModel.feedEmptyLabel(GitHubFeed.Status.RATE_LIMITED, "commits").contains("rate limited"))
        assertTrue(AggregatorModel.feedEmptyLabel(GitHubFeed.Status.UNREACHABLE, "commits").contains("unreachable"))
        assertEquals("(no recent commits)", AggregatorModel.feedEmptyLabel(GitHubFeed.Status.OK, "commits"))
        assertTrue(AggregatorModel.feedStaleLabel(GitHubFeed.Status.RATE_LIMITED).contains("rate limited"))
        assertTrue(AggregatorModel.feedStaleLabel(GitHubFeed.Status.UNREACHABLE).contains("offline"))
        assertEquals("never run", AggregatorModel.daguStatusLabel(0))
        assertEquals("idle", AggregatorModel.daguSeverity(0))
        assertEquals("error", AggregatorModel.daguSeverity(2))
        assertEquals("warn", AggregatorModel.daguSeverity(7))
        assertEquals("status 99", AggregatorModel.daguStatusLabel(99))
    }

    @Test
    fun `M9 age wording and a stable monogram colour`() {
        assertEquals("just now", AggregatorModel.ago(59_999))
        assertEquals("1m ago", AggregatorModel.ago(60_000))
        assertEquals("23h ago", AggregatorModel.ago(23 * 3_600_000L))
        assertEquals("2d ago", AggregatorModel.ago(2 * 86_400_000L))
        // A negative hashCode must still land inside the palette, and the same key must land on the same colour.
        val k = "polygon-art"
        assertEquals(AggregatorModel.monogramHue(k, 6), AggregatorModel.monogramHue(k, 6))
        for (key in listOf("a", "zzzzzzzzzzzzzzzzzzzzzzz", "éè", "")) {
            assertTrue(AggregatorModel.monogramHue(key, 6) in 0..5)
        }
    }
}
