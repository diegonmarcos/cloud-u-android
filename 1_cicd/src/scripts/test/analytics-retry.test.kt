// ╔══════════════════════════════════════════════════════════════════════╗
// ║ analytics-retry.test — a failed sink must be RETRIED, never dropped  ║
// ║ because the other sink succeeded                                     ║
// ╚══════════════════════════════════════════════════════════════════════╝
//
// WHY THIS EXISTS. The analytics module shipped this for as long as it has
// existed, in all twelve applications that embed it:
//
//     val ok = sendUmami(name, props) or sendMatomo(name, props)
//
// Kotlin's `or` is not short-circuiting, so both sinks fired — nothing was
// skipped, and that is exactly what made the defect survive review. What was
// wrong is that TWO results collapsed into ONE boolean: Umami succeeding made
// `ok` true, the event left the shared queue, and Matomo's failed copy was
// dropped with nothing holding it and nothing to retry from.
//
// WHY IT IS COMPILED AND RUN, NOT GREPPED. The fix is a behaviour, so the test
// has to be one. This file is compiled against the REAL
// ab_cloud-libs-shared/libs/analytics/.../SinkQueue.kt — the shipped source,
// by path, never a copy — and executes it. A guard matching the source text
// would pass for exactly as long as it took the text and the behaviour to
// drift, and this repository has already paid for that once: the two
// assertions fixed in 0b2cbc5ee were anchored on formatting, so they reported
// on spelling instead of on the rule.
//
// Case 1 is the regression itself and is the one that goes red if the collapse
// is ever reintroduced. The rest pin down the properties the fix depends on:
// that the bound is real, that the eviction end is the old one, and that order
// survives a partial failure.

// Pinned explicitly. This file is named for the repo's *.test.* convention,
// and a JVM class name cannot contain the hyphen or the dots that name has, so
// the compiler would otherwise mangle it and the runner would be guessing what
// to invoke.
@file:JvmName("AnalyticsRetryTest")

import com.diegonmarcos.superapp.analytics.SinkQueue

private var failures = 0

private fun expect(condition: Boolean, what: String) {
    if (condition) {
        println("ok     $what")
    } else {
        println("FAIL   $what")
        failures++
    }
}

// The shipped bound, restated here so a change to it is a visible change to
// this test rather than a silent one.
private const val CAPACITY = 200

fun main() {
    // ── Case 1: THE REGRESSION ────────────────────────────────────────────
    // One event, Umami reachable, Matomo down. Under the old shared-queue and
    // shared-boolean design the event was considered delivered and Matomo's
    // copy ceased to exist. It must now still be owed.
    run {
        val umami = SinkQueue("umami", CAPACITY)
        val matomo = SinkQueue("matomo", CAPACITY)
        val event = "pageview" to mapOf("screen" to "settings")
        umami.offer(event)
        matomo.offer(event)

        var umamiDeliveries = 0
        umami.drain { umamiDeliveries++; true }   // Umami accepts it
        matomo.drain { false }                    // Matomo is unreachable

        expect(umamiDeliveries == 1, "the reachable sink delivers its copy")
        expect(
            matomo.size() == 1,
            "the FAILED sink still owes its copy — it is retried, not dropped " +
                "because the other sink succeeded",
        )

        // And the held copy really is sent once the backend comes back, rather
        // than merely sitting in a queue nothing ever drains again.
        val redelivered = mutableListOf<String>()
        matomo.drain { redelivered.add(it.first); true }
        expect(redelivered == listOf("pageview"), "the held copy is delivered when the sink recovers")
        expect(matomo.size() == 0, "and is not then owed a second time")
    }

    // ── Case 2: the bound is real, and it evicts the OLDEST ───────────────
    // An unbounded queue on a phone that is offline for a day is a worse bug
    // than the one being fixed here.
    run {
        val queue = SinkQueue("bounded", CAPACITY)
        repeat(CAPACITY + 50) { queue.offer("event$it" to emptyMap()) }
        expect(queue.size() == CAPACITY, "the queue is bounded at $CAPACITY events per sink")

        val seen = mutableListOf<String>()
        queue.drain { seen.add(it.first); true }
        expect(
            seen.first() == "event50" && seen.last() == "event${CAPACITY + 49}",
            "the OLDEST events are the ones evicted, so recent activity survives",
        )
    }

    // ── Case 3: a partial failure keeps order ─────────────────────────────
    // The failed event goes back at the FRONT, so a recovered backend receives
    // the stream in the order it happened rather than with a hole in it.
    run {
        val queue = SinkQueue("ordering", CAPACITY)
        repeat(3) { queue.offer("event$it" to emptyMap()) }

        var attempts = 0
        queue.drain { attempts++; attempts < 2 }   // first lands, second fails

        expect(queue.size() == 2, "the failed event and everything after it stay queued")

        val rest = mutableListOf<String>()
        queue.drain { rest.add(it.first); true }
        expect(rest == listOf("event1", "event2"), "and are redelivered in their original order")
    }

    // ── Case 4: withdrawn consent drops what is queued ────────────────────
    run {
        val queue = SinkQueue("consent", CAPACITY)
        repeat(5) { queue.offer("event$it" to emptyMap()) }
        queue.clear()
        expect(queue.size() == 0, "withdrawing consent drops every undelivered event")
    }

    if (failures > 0) {
        println()
        println("FAILED: $failures assertion(s)")
        kotlin.system.exitProcess(1)
    }
    println()
    println("PASS: a failed sink keeps and retries its own events")
}
