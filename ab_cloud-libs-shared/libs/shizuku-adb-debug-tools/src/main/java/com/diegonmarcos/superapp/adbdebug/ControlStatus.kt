package com.diegonmarcos.superapp.adbdebug

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What the Store bar's two device controls SAY, as pure rules (no Android), so the
 * mapping and the probe timeout are unit-testable. The view only renders these.
 */
object ControlStatus {

    /** A reading the device may not give: UNKNOWN is a state, not a default. */
    enum class Tri { ON, OFF, UNKNOWN }

    /** The three verifier values as the device STORES them; null = no such key (or unreadable).
     *  PackageVerifier.state cannot say that - its defaults make absent and consented look alike. */
    data class Reading(val consent: Int?, val enable: Int?, val adb: Int?)

    /** `settings get` prints "null" for an absent key; anything non-numeric is no answer. */
    fun parseSetting(out: String?): Int? = out?.trim()?.toIntOrNull()

    /** Play Protect's install scan. Any key still arming it reads ON (GMS re-arms one at a
     *  time); none readable at all reads UNKNOWN rather than a default dressed up as a fact. */
    fun playProtect(r: Reading): Tri {
        val v = listOf(r.consent, r.enable, r.adb)
        if (v.all { it == null }) return Tri.UNKNOWN
        val armed = (r.consent != null && r.consent >= 0) ||
            (r.enable != null && r.enable != 0) || (r.adb != null && r.adb != 0)
        return if (armed) Tri.ON else Tri.OFF
    }

    enum class Channel { UP, DOWN, NOT_PAIRED }

    /** [via] = the channel that answered (UP only). */
    data class ChannelStatus(val state: Channel, val via: String? = null)

    enum class Action { OPEN_SETTINGS, RECONNECT, PAIR }

    /** What a tap on the channel control does. Reconnect needs Wireless Debugging on to
     *  have anything to discover, so with it off the useful step is the switch itself. */
    fun channelAction(s: ChannelStatus, wirelessDebugOn: Boolean): Action = when (s.state) {
        Channel.UP -> Action.OPEN_SETTINGS
        Channel.DOWN -> if (wirelessDebugOn) Action.RECONNECT else Action.OPEN_SETTINGS
        Channel.NOT_PAIRED -> Action.PAIR
    }

    /** `id` output proves a shell-level authorisation: uid 2000 (shell), or 0 under root. */
    internal fun isShellUid(idOutput: String?): Boolean {
        val uid = idOutput?.let { Regex("""uid=(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return uid == 2000 || uid == 0
    }

    /** The two questions the probe asks the device. Injected so a fake can stand in. */
    interface Backend {
        /** Was a privileged channel ever paired on this install. */
        fun paired(): Boolean
        /** (channel name, output of `id`) from the first ready channel, null if none is. Blocking. */
        fun id(): Pair<String, String?>?
    }

    /**
     * Live round trip, cached. [get] blocks (at most [timeoutMs]) so call it OFF the main
     * thread; a wedged channel that accepts and never answers resolves to DOWN at the
     * deadline instead of hanging the bar. Results are reused for [ttlMs].
     */
    class Probe(
        private val backend: Backend,
        private val timeoutMs: Long = 4_000,
        private val ttlMs: Long = 5_000,
        private val clock: () -> Long = System::currentTimeMillis,
    ) {
        private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "store-channel-probe").apply { isDaemon = true } }
        private var at = Long.MIN_VALUE
        private var cached: ChannelStatus? = null

        /** The last answer, without probing. */
        @Synchronized fun peek(): ChannelStatus? = cached

        /** True while the cached answer is younger than the TTL (a render uses it to decide whether to re-probe). */
        @Synchronized fun fresh(): Boolean = cached != null && clock() - at < ttlMs

        @Synchronized fun invalidate() { cached = null }

        @Synchronized fun get(force: Boolean = false): ChannelStatus {
            val c = cached
            if (!force && c != null && clock() - at < ttlMs) return c
            val s = probeNow()
            cached = s; at = clock()
            return s
        }

        private fun probeNow(): ChannelStatus {
            val f = pool.submit(Callable { backend.id() })
            val got = try { f.get(timeoutMs, TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) { f.cancel(true); null }
                catch (_: Exception) { null }
            if (got != null && isShellUid(got.second)) return ChannelStatus(Channel.UP, got.first)
            val paired = runCatching { backend.paired() }.getOrDefault(false)
            return ChannelStatus(if (paired) Channel.DOWN else Channel.NOT_PAIRED)
        }
    }
}
