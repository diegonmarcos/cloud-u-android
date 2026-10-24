package com.diegonmarcos.superapp.light

/**
 * Light tool (#1155) - the pure half: settings, persistence, quick-tap rule and the engine that
 * drives the rear torch, the front (screen) light, the strobe and the timer. No Android imports on
 * purpose: the torch, the clock and the store are injected, so every rule below is a JVM unit test.
 */
enum class LightTarget { REAR, FRONT, BOTH }
enum class LightMode { SPOTLIGHT, FLASHING }

data class LightSettings(
    val defaultTarget: LightTarget = LightTarget.REAR,
    val mode: LightMode = LightMode.SPOTLIGHT,
    val strobeHz: Float = LightLimits.DEFAULT_STROBE_HZ,
    val rearPercent: Int = 100,
    val frontPercent: Int = 100,
    val timerOn: Boolean = false,
    val timerMinutes: Int = 5,
    val keepOn: Boolean = false,
)

object LightLimits {
    /** Photosensitivity: a strobe never goes faster than 3 flashes a second, whatever is stored or asked. */
    const val MAX_STROBE_HZ = 3f
    const val MIN_STROBE_HZ = 0.5f
    const val DEFAULT_STROBE_HZ = 2f
    const val MIN_TIMER_MINUTES = 1
    const val MAX_TIMER_MINUTES = 180
    fun clampHz(hz: Float): Float = if (hz.isNaN()) DEFAULT_STROBE_HZ else hz.coerceIn(MIN_STROBE_HZ, MAX_STROBE_HZ)
    fun clampPercent(p: Int): Int = p.coerceIn(1, 100)
    fun clampMinutes(m: Int): Int = m.coerceIn(MIN_TIMER_MINUTES, MAX_TIMER_MINUTES)
    /** Rear strength level for a percentage; a torch without levels (max <= 1) is on/off only. */
    fun rearLevel(percent: Int, maxLevel: Int): Int =
        if (maxLevel <= 1) 1 else ((clampPercent(percent) * maxLevel + 99) / 100).coerceIn(1, maxLevel)
}

/** What the tile tap does: the lights named by the default target flip together. */
object QuickTap {
    fun next(rearOn: Boolean, frontOn: Boolean, target: LightTarget): Pair<Boolean, Boolean> {
        val r = target != LightTarget.FRONT
        val f = target != LightTarget.REAR
        val turnOff = (r && rearOn) || (f && frontOn)
        return Pair(if (r) !turnOff else rearOn, if (f) !turnOff else frontOn)
    }
}

/** Key/value port over the preferences file `light_prefs` (declared in fleet-config.json). */
interface LightKv {
    fun int(key: String, def: Int): Int
    fun bool(key: String, def: Boolean): Boolean
    fun float(key: String, def: Float): Float
    fun str(key: String, def: String): String
    fun put(values: Map<String, Any>)
}

object LightPrefs {
    const val FILE = "light_prefs"
    fun load(kv: LightKv): LightSettings {
        val d = LightSettings()
        return LightSettings(
            defaultTarget = runCatching { LightTarget.valueOf(kv.str("default_target", d.defaultTarget.name)) }.getOrDefault(d.defaultTarget),
            mode = runCatching { LightMode.valueOf(kv.str("mode", d.mode.name)) }.getOrDefault(d.mode),
            strobeHz = LightLimits.clampHz(kv.float("strobe_hz", d.strobeHz)),
            rearPercent = LightLimits.clampPercent(kv.int("rear_percent", d.rearPercent)),
            frontPercent = LightLimits.clampPercent(kv.int("front_percent", d.frontPercent)),
            timerOn = kv.bool("timer_on", d.timerOn),
            timerMinutes = LightLimits.clampMinutes(kv.int("timer_minutes", d.timerMinutes)),
            keepOn = kv.bool("keep_on", d.keepOn),
        )
    }
    fun save(kv: LightKv, s: LightSettings) = kv.put(mapOf(
        "default_target" to s.defaultTarget.name, "mode" to s.mode.name, "strobe_hz" to s.strobeHz,
        "rear_percent" to s.rearPercent, "front_percent" to s.frontPercent, "timer_on" to s.timerOn,
        "timer_minutes" to s.timerMinutes, "keep_on" to s.keepOn))
}

sealed class TorchResult {
    object Ok : TorchResult()
    object InUse : TorchResult()
    data class Unavailable(val why: String) : TorchResult()
}

interface Torch {
    /** Highest strength level; 1 means on/off only. */
    val maxLevel: Int
    fun set(on: Boolean, level: Int): TorchResult
}

interface LightClock {
    fun now(): Long
    fun postDelayed(ms: Long, r: Runnable): Any
    fun cancel(token: Any)
}

data class LightState(
    val rearOn: Boolean = false,
    val frontOn: Boolean = false,
    /** Strobe phase: false during the dark half of a flash. Always true in Spotlight. */
    val lit: Boolean = true,
    val timerEndAt: Long = 0L,
    val error: String? = null,
) {
    val anyOn get() = rearOn || frontOn
    val timerRunning get() = timerEndAt > 0L
}

class LightEngine(private val torch: Torch, private val clock: LightClock, private val kv: LightKv) {
    var settings: LightSettings = LightPrefs.load(kv); private set
    var state: LightState = LightState(); private set
    private val listeners = mutableListOf<() -> Unit>()
    private var strobeTok: Any? = null
    private var timerTok: Any? = null
    private var torchOn = false

    val rearMaxLevel get() = torch.maxLevel
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun update(f: (LightSettings) -> LightSettings) {
        val old = settings
        val n = f(old)
        settings = n.copy(
            strobeHz = LightLimits.clampHz(n.strobeHz), rearPercent = LightLimits.clampPercent(n.rearPercent),
            frontPercent = LightLimits.clampPercent(n.frontPercent), timerMinutes = LightLimits.clampMinutes(n.timerMinutes))
        LightPrefs.save(kv, settings)
        if (old.timerMinutes != settings.timerMinutes || old.timerOn != settings.timerOn) cancelTimer()
        if (old.strobeHz != settings.strobeHz || old.mode != settings.mode) cancelStrobe()
        changed()
    }

    fun setRear(on: Boolean) { state = state.copy(rearOn = on, error = null); changed() }
    fun setFront(on: Boolean) { state = state.copy(frontOn = on, error = null); changed() }

    /** The tile tap: flips the default light(s) at once. */
    fun quickTap() {
        val (r, f) = QuickTap.next(state.rearOn, state.frontOn, settings.defaultTarget)
        state = state.copy(rearOn = r, frontOn = f, error = null); changed()
    }

    fun offAll() { state = state.copy(rearOn = false, frontOn = false); changed() }

    /** Page left or app backgrounded: the screen light always ends; the torch stays only for a timer or Keep-on. */
    fun onLeave() {
        val keep = state.timerRunning || settings.keepOn
        state = state.copy(frontOn = false, rearOn = state.rearOn && keep); changed()
    }

    /** The system said the torch was taken by another app (camera in use). */
    fun onTorchUnavailable() {
        if (!state.rearOn) return
        torchOn = false
        state = state.copy(rearOn = false, error = MSG_IN_USE); changed()
    }

    private fun changed() {
        if (!state.anyOn) { cancelTimer(); cancelStrobe(); state = state.copy(lit = true, timerEndAt = 0L) }
        else {
            if (settings.timerOn && !state.timerRunning) startTimer()
            if (settings.mode == LightMode.FLASHING) startStrobe() else { cancelStrobe(); state = state.copy(lit = true) }
        }
        applyTorch()
        listeners.toList().forEach { it() }
    }

    private fun applyTorch() {
        val want = state.rearOn && state.lit
        if (!want && !torchOn) return
        when (val r = torch.set(want, LightLimits.rearLevel(settings.rearPercent, torch.maxLevel))) {
            TorchResult.Ok -> torchOn = want
            TorchResult.InUse -> fail(MSG_IN_USE)
            is TorchResult.Unavailable -> fail(MSG_UNAVAILABLE)
        }
    }

    private fun fail(msg: String) {
        torchOn = false; cancelStrobe()
        state = state.copy(rearOn = false, lit = true, error = msg)
        if (!state.anyOn) { cancelTimer(); state = state.copy(timerEndAt = 0L) }
    }

    private fun startStrobe() {
        if (strobeTok != null) return
        val half = (500f / LightLimits.clampHz(settings.strobeHz)).toLong()
        strobeTok = clock.postDelayed(half, object : Runnable {
            override fun run() {
                strobeTok = null
                if (!state.anyOn || settings.mode != LightMode.FLASHING) return
                state = state.copy(lit = !state.lit)
                applyTorch()
                if (state.anyOn) strobeTok = clock.postDelayed(half, this)
                listeners.toList().forEach { it() }
            }
        })
    }
    private fun cancelStrobe() { strobeTok?.let(clock::cancel); strobeTok = null }

    private fun startTimer() {
        val ms = settings.timerMinutes * 60_000L
        state = state.copy(timerEndAt = clock.now() + ms)
        timerTok = clock.postDelayed(ms, Runnable { timerTok = null; offAll() })
    }
    private fun cancelTimer() { timerTok?.let(clock::cancel); timerTok = null; state = state.copy(timerEndAt = 0L) }

    companion object {
        const val MSG_IN_USE = "The camera is in use by another app. Close it and try again."
        const val MSG_UNAVAILABLE = "The flashlight is not available on this phone right now."
    }
}
