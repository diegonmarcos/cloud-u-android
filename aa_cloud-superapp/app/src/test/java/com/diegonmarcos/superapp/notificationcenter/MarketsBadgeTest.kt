package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #517 — the Markets badge, proven on the SHIPPED declaration and on the code
 * that turns a quote into text.
 *
 * WHY THIS IS NOT MORE OF BadgeDeclarationTest. That file drives the resolver
 * with a declaration of its own making, by design, and that is the right shape
 * for testing the resolver — but it means an assertion written there proves the
 * PARSER works, not that the badge is in the app's real `build.json`. A Markets
 * badge that parses beautifully out of a fixture and is absent from the shipped
 * declaration is exactly the hollow green this fleet keeps paying for.
 *
 * So the first half of this file asserts against [BadgeServices.declared] —
 * `BuildConfig.UI_NOTIFICATION_CENTER_B64`, the same bytes the APK carries and
 * the same resolution path `BadgeRestartReceiver` and `BadgePanes` run.
 * Delete the producer from build.json and these go red.
 *
 * The second half is the honesty rule: a fetch that failed must never render
 * as a price. [MarketsQuotes.parse] and [MarketsQuotes.row] are pure, so that
 * is provable here with no device and no network — which matters, because the
 * failure being guarded against only happens when Yahoo is unreachable, and a
 * test that needed Yahoo to be unreachable could never run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MarketsBadgeTest {

    private fun markets(): BadgeDeclaration.Badge? =
        BadgeServices.declared.firstOrNull { it.id == MarketsBadgeService.BADGE_ID }

    // ── The badge is in the declaration THIS BUILD SHIPS ─────────────────

    /**
     * THE CHECK THE TICKET ASKS FOR. Not "does build.json contain the word
     * markets" — does the resolved set the app renders from contain this badge,
     * as a badge, persistent, owned by a service that can be restarted.
     */
    @Test
    fun `the shipped declaration carries the Markets badge`() {
        val all = BadgeServices.declared
        assertFalse("the shipped declaration did not resolve at all", all.isEmpty())

        val m = markets()
        assertTrue(
            "markets_prices is not in the SHIPPED declaration — resolved ids: " +
                all.map { it.id },
            m != null,
        )
        m!!
        assertTrue("Markets must be a badge, or the Push pane never draws it", m.isBadge)
        assertTrue("Markets is declared persistent so it survives an update", m.persistent)
        assertEquals(
            "com.diegonmarcos.superapp.notificationcenter.MarketsBadgeService",
            m.service,
        )
        assertTrue(
            "the Push pane renders BadgeDeclaration.badges — Markets must be in it",
            BadgeDeclaration.badges(all).map { it.id }.contains(MarketsBadgeService.BADGE_ID),
        )
    }

    /** Persistent-by-declaration must mean restarted-by-mechanism: #515's whole
     *  point is that a new badge is covered with no Kotlin edit. */
    @Test
    fun `the Markets service is in the shipped restart set`() {
        assertTrue(
            "MarketsBadgeService is not re-ensured after an update",
            BadgeDeclaration.restartServices(BadgeServices.declared)
                .contains("com.diegonmarcos.superapp.notificationcenter.MarketsBadgeService"),
        )
    }

    /**
     * The four instruments Diego asked for, in the shipped data.
     *
     * BRL DIRECTION IS ASSERTED, not assumed. Yahoo's `BRL=X` is the USD->BRL
     * cross — reais per dollar, ~5.x — so the caption must read USD/BRL. A
     * caption of "BRL/USD" over that number is a factual error of a factor of
     * ~27, and it is the kind that survives review because both strings look
     * plausible.
     */
    @Test
    fun `the shipped instruments are the four asked for, captioned the way they run`() {
        val m = markets()!!
        assertEquals(
            listOf("BRL=X", "EURUSD=X", "CL=F", "GC=F"),
            m.instruments.map { it.symbol },
        )
        assertEquals(
            "BRL=X is Yahoo's USD->BRL cross; the caption has to say so",
            "USD/BRL",
            m.instruments.first { it.symbol == "BRL=X" }.label,
        )
        // Every instrument is tappable, and every tap stays on Yahoo Finance.
        m.instruments.forEach {
            assertTrue("${it.symbol} has no ticker page", it.url.startsWith("https://finance.yahoo.com/quote/"))
            assertTrue("${it.symbol} declared nonsense precision", it.decimals in 0..8)
        }
    }

    /** The cadence is declared, so it can be seen and changed without a build.
     *  A badge that polls a public endpoint every minute is the one that gets
     *  the IP rate-limited. */
    @Test
    fun `the refresh cadence is declared and never tighter than a quarter hour`() {
        val opt = markets()!!.customization.firstOrNull { it.key == "refresh_minutes" }
        assertTrue("no refresh_minutes option — the interval would be hardcoded", opt != null)
        val options = opt!!.options.mapNotNull(String::toLongOrNull)
        assertFalse("refresh_minutes declares no choices", options.isEmpty())
        assertTrue("a sub-15-minute poll is declared: $options", (options.minOrNull() ?: 0L) >= 15L)
        assertTrue("the default is not one of the declared choices", opt.options.contains(opt.default))
    }

    // ── The honesty rule: a failure is never a number ────────────────────

    private val brl = BadgeDeclaration.Instrument(
        symbol = "BRL=X", label = "USD/BRL", decimals = 4,
        url = "https://finance.yahoo.com/quote/BRL%3DX",
    )

    private fun okQuote(price: Double, asOf: Long) =
        MarketsQuotes.Quote("BRL=X", price, asOf, MarketsQuotes.Status.OK)

    /** A real v8 payload, trimmed to the fields that matter — the same shape
     *  `query1.finance.yahoo.com/v8/finance/chart/BRL=X` actually returned. */
    private val realBody = """
        {"chart":{"result":[{"meta":{"currency":"BRL","symbol":"BRL=X",
          "shortName":"USD/BRL","regularMarketPrice":5.1421,
          "regularMarketTime":1789769403}}],"error":null}}
    """.trimIndent()

    @Test
    fun `a good payload parses to the price and its own timestamp`() {
        val q = MarketsQuotes.parse("BRL=X", realBody)
        assertEquals(MarketsQuotes.Status.OK, q.status)
        assertEquals(5.1421, q.price!!, 0.00001)
        // Yahoo sends seconds; the badge works in millis.
        assertEquals(1789769403_000L, q.asOfMillis)
    }

    /**
     * THE MUTATION TARGET. Every way a fetch can fail — offline, 401, 429, a
     * body that is not JSON, a payload with no price — must produce no price at
     * all. If any of these ever yields a number, the badge will print it.
     */
    @Test
    fun `no failure shape ever produces a price`() {
        val failures = mapOf(
            "empty body" to MarketsQuotes.parse("BRL=X", ""),
            "null body (offline)" to MarketsQuotes.parse("BRL=X", null),
            "not json" to MarketsQuotes.parse("BRL=X", "<html>429 Too Many Requests</html>"),
            "json, no chart" to MarketsQuotes.parse("BRL=X", """{"finance":{"error":"Unauthorized"}}"""),
            "chart, no result" to MarketsQuotes.parse("BRL=X", """{"chart":{"result":[],"error":null}}"""),
            "meta, no price" to MarketsQuotes.parse("BRL=X",
                """{"chart":{"result":[{"meta":{"symbol":"BRL=X"}}]}}"""),
            "price is a string" to MarketsQuotes.parse("BRL=X",
                """{"chart":{"result":[{"meta":{"regularMarketPrice":"n/a"}}]}}"""),
            "http refusal" to MarketsQuotes.unavailable("BRL=X", "Yahoo refused the request (401)."),
        )
        failures.forEach { (shape, q) ->
            assertEquals("$shape produced a status other than UNAVAILABLE",
                MarketsQuotes.Status.UNAVAILABLE, q.status)
            assertNull("$shape produced a price", q.price)
            assertTrue("$shape gave the owner no reason", q.reason.isNotBlank())
        }
    }

    /**
     * THE SECOND CHECK THE TICKET ASKS FOR: a failed fetch must not render as a
     * live price. Asserted on the RENDERED TEXT, because that is what the owner
     * reads — and asserted as "contains no digit", so it cannot be satisfied by
     * a number that merely looks different.
     */
    @Test
    fun `a failed fetch renders as a dash, with no number anywhere in the row`() {
        val failed = MarketsQuotes.unavailable("BRL=X", "Could not reach Yahoo: UnknownHostException.")
        val text = MarketsQuotes.row(brl, failed, NOW, STALE_AFTER)

        assertTrue("a failed row must show the dash: $text", text.contains(MarketsQuotes.DASH))
        assertFalse("a failed row printed a digit — that is a price: $text", text.any(Char::isDigit))
        assertEquals("USD/BRL ${MarketsQuotes.DASH}", text)

        // And a symbol that was simply never fetched reads the same way, rather
        // than vanishing from the badge as though it were not declared.
        assertEquals("USD/BRL ${MarketsQuotes.DASH}", MarketsQuotes.row(brl, null, NOW, STALE_AFTER))
    }

    /**
     * A price older than the badge's own cadence carries its age. This is the
     * other half of the same rule: the defect is not only "shows a wrong
     * number", it is "shows a real but old number as though it were live".
     */
    @Test
    fun `a dated price is shown with its age and a fresh one without`() {
        val fresh = MarketsQuotes.row(brl, okQuote(5.1421, NOW - 60_000L), NOW, STALE_AFTER)
        assertEquals("USD/BRL 5.1421", fresh)

        val stale = MarketsQuotes.row(brl, okQuote(5.1421, NOW - 3 * 24 * 3_600_000L), NOW, STALE_AFTER)
        assertTrue("a three-day-old price must carry its age: $stale", stale.contains("3d ago"))
        assertFalse("a dated row must not read like a fresh one",
            stale == fresh)

        // A price with no timestamp cannot be called fresh, because nothing
        // knows that it is.
        val undated = MarketsQuotes.row(brl, okQuote(5.1421, 0L), NOW, STALE_AFTER)
        assertTrue("an undated price claimed freshness: $undated", undated.contains("age unknown"))
    }

    /** The collapsed badge shows one row per declared instrument, in
     *  declaration order — so a symbol that failed still holds its place and
     *  the owner can see WHICH one is missing. */
    @Test
    fun `the summary keeps every declared instrument, failed ones included`() {
        val instruments = markets()!!.instruments
        val onlyGold = mapOf("GC=F" to MarketsQuotes.Quote("GC=F", 4424.9, NOW, MarketsQuotes.Status.OK))
        val line = MarketsQuotes.summary(instruments, onlyGold, NOW, STALE_AFTER)

        instruments.forEach { assertTrue("${it.label} fell out of the badge: $line", line.contains(it.label)) }
        assertEquals("three of four failed, so three dashes",
            3, line.split(MarketsQuotes.DASH).size - 1)
        assertTrue("the one good price is missing: $line", line.contains("4424.90"))
    }

    private companion object {
        /** Fixed clock: a test whose verdict depends on the wall clock is a
         *  test that fails on a Sunday. */
        const val NOW = 1_789_800_000_000L
        const val STALE_AFTER = 90 * 60_000L
    }
}
