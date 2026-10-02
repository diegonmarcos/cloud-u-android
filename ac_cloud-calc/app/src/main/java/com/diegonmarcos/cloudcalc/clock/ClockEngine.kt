package com.diegonmarcos.cloudcalc.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.app.AlarmManagerCompat
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * build.json::ui.modes' `clock` objects, read once per use. No number below is the app's: each is
 * the declaration's, with the fallback only for a declaration that leaves it out.
 */
object ClockDecl {
    data class IntervalPreset(val label: String, val workS: Long, val restS: Long, val longRestS: Long, val longEvery: Int, val rounds: Int)

    fun of(kind: String): JSONObject =
        runCatching { JSONObject(Declarations.modes.first { it.kind == kind }.clock) }.getOrDefault(JSONObject())

    fun modeId(kind: String): String? = Declarations.modes.firstOrNull { it.kind == kind }?.id

    val ringMs: Long get() = of("alarms").optLong("ring_minutes", 10) * ClockLogic.MINUTE_MS
    val snoozeChoices: List<Int> get() = ints(of("alarms").optJSONArray("snooze_choices"))
    val snoozeDefault: Int get() = of("alarms").optInt("snooze_default", 10)
    val vibrateDefault: Boolean get() = of("alarms").optBoolean("vibrate_default", true)
    val zones: List<String> get() = of("worldclock").optJSONArray("zones").let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) } }
    val timerPresets: List<Long> get() = ints(of("timers").optJSONArray("presets")).map { it.toLong() }
    val sleepChoices: List<Int> get() = ints(of("bedtime").optJSONArray("sleep_choices"))
    val bedtimeDefault: Int get() = ClockLogic.parseTime(of("bedtime").optString("bedtime_default")) ?: (23 * 60)
    val intervalPresets: List<IntervalPreset> get() = of("interval").optJSONArray("presets").let { a ->
        if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
            IntervalPreset(p.getString("label"), p.getLong("work"), p.optLong("rest"), p.optLong("long_rest"), p.optInt("long_every"), p.getInt("rounds"))
        }
    }

    private fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else (0 until a.length()).map { a.getInt(it) }
}

/**
 * THE ONE WAY the Clock changes. Every screen, notification button, debug route and wakeup calls
 * one of these; each writes the store, re-plans every AlarmManager wakeup from it ([ClockLogic.plan])
 * and settles the foreground service. Nothing is kept in memory but the [changes] counter the
 * screens observe, so a process killed between any two calls loses nothing: the next process
 * reads the same store, and the alarms it set are the system's, not ours.
 */
object ClockEngine {
    const val PREFS = "cloud_clock"
    private const val KEY_DATA = "data"
    private const val KEY_SCHEDULED = "scheduled"
    const val ACTION_FIRE = "com.diegonmarcos.cloudcalc.clock.FIRE"
    const val ACTION_CONTROL = "com.diegonmarcos.cloudcalc.clock.CONTROL"
    const val EXTRA_KEY = "key"
    const val EXTRA_OP = "op"
    /** MainActivity opens this mode id (a notification tap, the status-bar alarm icon). */
    const val EXTRA_MODE = "clock_mode"

    private val lock = Any()

    /** The clock and zone every rule reads; the JVM suites move them. */
    @Volatile var now: () -> Long = { System.currentTimeMillis() }
    @Volatile var zone: () -> ZoneId = { ZoneId.systemDefault() }

    /** Bumped on every write: the screens and the ring screen re-read the store on it. */
    val changes = MutableStateFlow(0L)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): ClockData = ClockCodec.decode(prefs(ctx).getString(KEY_DATA, null))

    fun update(ctx: Context, f: (ClockData) -> ClockData): ClockData {
        val d = synchronized(lock) {
            f(load(ctx)).also { prefs(ctx).edit().putString(KEY_DATA, ClockCodec.encode(it)).commit() }
        }
        settle(ctx, d)
        return d
    }

    /** Re-plan from the store alone: boot, app update, time or zone change, exact-alarm grant. */
    fun reschedule(ctx: Context) = settle(ctx, load(ctx))

    private fun settle(ctx: Context, d: ClockData) {
        schedule(ctx, ClockLogic.plan(d, now(), zone(), ClockDecl.ringMs))
        ClockService.sync(ctx, d)
        changes.value = changes.value + 1
    }

    // ── what the screens and buttons do ─────────────────────────────────────────────────────

    /** Adds [a] when its id is 0, else replaces the alarm with that id (and drops its snooze). */
    fun saveAlarm(ctx: Context, a: Alarm): Long {
        var id = a.id
        update(ctx) { d ->
            if (a.id == 0L) {
                id = d.seq + 1
                d.copy(seq = id, alarms = d.alarms + a.copy(id = id))
            } else {
                d.copy(alarms = d.alarms.map { if (it.id == a.id) a.copy(snoozedUntil = 0) else it })
            }
        }
        return id
    }

    fun deleteAlarm(ctx: Context, id: Long) = update(ctx) { d ->
        d.copy(alarms = d.alarms.filter { it.id != id }, ringing = d.ringing.filter { it.key != ClockLogic.alarmKey(id) })
    }

    fun addTimer(ctx: Context, label: String, durationMs: Long, start: Boolean): Long {
        var id = 0L
        update(ctx) { d ->
            id = d.seq + 1
            val t = Timer(id, label, durationMs)
            d.copy(seq = id, timers = d.timers + if (start) ClockLogic.start(t, now()) else t)
        }
        return id
    }

    /** op: start | pause | add | reset | delete. A ringing timer stops ringing on add, reset and delete. */
    fun timer(ctx: Context, id: Long, op: String) = update(ctx) { d ->
        val key = ClockLogic.timerKey(id)
        val ringing = if (op == "add" || op == "reset" || op == "delete") d.ringing.filter { it.key != key } else d.ringing
        val timers = if (op == "delete") d.timers.filter { it.id != id } else d.timers.map { t ->
            if (t.id != id) t else when (op) {
                "start" -> ClockLogic.start(t, now())
                "pause" -> ClockLogic.pause(t, now())
                "add" -> ClockLogic.addMinute(t, now())
                "reset" -> ClockLogic.reset(t)
                else -> t
            }
        }
        d.copy(timers = timers, ringing = ringing)
    }

    /** op: toggle | lap | reset. */
    fun stopwatch(ctx: Context, op: String) = update(ctx) { d ->
        d.copy(stopwatch = when (op) {
            "toggle" -> ClockLogic.toggle(d.stopwatch, now())
            "lap" -> ClockLogic.lap(d.stopwatch, now())
            "reset" -> Stopwatch()
            else -> d.stopwatch
        })
    }

    fun startInterval(ctx: Context, p: ClockDecl.IntervalPreset) = update(ctx) { d ->
        d.copy(interval = Interval(
            p.label, p.workS * ClockLogic.SECOND_MS, p.restS * ClockLogic.SECOND_MS, p.rounds,
            p.longRestS * ClockLogic.SECOND_MS, p.longEvery, now(),
        ))
    }

    fun stopInterval(ctx: Context) = update(ctx) { d -> d.copy(interval = null, ringing = d.ringing.filter { it.key != ClockLogic.INTERVAL }) }

    fun startSleep(ctx: Context, minutes: Int) = update(ctx) { it.copy(sleepUntil = now() + minutes * ClockLogic.MINUTE_MS) }
    fun cancelSleep(ctx: Context) = update(ctx) { it.copy(sleepUntil = 0) }

    fun setZones(ctx: Context, zones: List<String>) = update(ctx) { it.copy(zones = zones) }

    fun dismiss(ctx: Context, key: String) = update(ctx) { ClockLogic.dismiss(it, key) }
    fun dismissAll(ctx: Context) = update(ctx) { d -> d.ringing.fold(d) { acc, r -> ClockLogic.dismiss(acc, r.key) } }
    fun snooze(ctx: Context, key: String) = update(ctx) { ClockLogic.snooze(it, key, now()) }

    /** A notification button (ClockReceiver ACTION_CONTROL). */
    fun control(ctx: Context, op: String, key: String) {
        val id = key.substringAfter(':').toLongOrNull() ?: 0L
        when (op) {
            "dismiss" -> dismiss(ctx, key)
            "dismiss_all" -> dismissAll(ctx)
            "snooze" -> snooze(ctx, key)
            "timer_start" -> timer(ctx, id, "start")
            "timer_pause" -> timer(ctx, id, "pause")
            "timer_add" -> timer(ctx, id, "add")
            "timer_reset" -> timer(ctx, id, "reset")
            "stopwatch_toggle" -> stopwatch(ctx, "toggle")
            "stopwatch_lap" -> stopwatch(ctx, "lap")
            "interval_stop" -> stopInterval(ctx)
            "sleep_cancel" -> cancelSleep(ctx)
        }
    }

    // ── a wakeup arrived ────────────────────────────────────────────────────────────────────

    fun fire(ctx: Context, key: String) {
        var fired: ClockLogic.Fired? = null
        update(ctx) { d -> ClockLogic.fire(d, key, now(), zone(), ClockDecl.ringMs).also { fired = it }.data }
        val f = fired ?: return
        when (f.effect) {
            ClockLogic.Effect.NOTIFY -> ClockNotifications.reminder(ctx, f.label)
            ClockLogic.Effect.BEEP -> beep(ctx)
            ClockLogic.Effect.PAUSE_MEDIA -> pauseMedia(ctx)
            // RING and SILENCE are the service's: settle() already handed it the new ringing list.
            else -> Unit
        }
    }

    /** The alarm stream's attributes: it sounds in silent and Do Not Disturb's alarms-only modes. */
    internal val alarmAudio: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    /** An interval phase change: a short tone on the alarm stream (it sounds in silent mode, like the timer). */
    private fun beep(ctx: Context) {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 800)
        }
        ClockService.buzz(ctx, longArrayOf(0, 200, 120, 200))
    }

    /**
     * The sleep timer ran out. Taking permanent audio focus is the platform's own "something else
     * is playing now" signal: well-behaved players pause on AUDIOFOCUS_LOSS and do not resume when
     * it is abandoned, so this stops music and podcasts without touching any other app.
     */
    private fun pauseMedia(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching {
            am.requestAudioFocus(req)
            am.abandonAudioFocusRequest(req)
        }
    }

    // ── AlarmManager ────────────────────────────────────────────────────────────────────────

    fun canExact(ctx: Context): Boolean =
        ctx.getSystemService(AlarmManager::class.java)?.let { AlarmManagerCompat.canScheduleExactAlarms(it) } ?: false

    /**
     * Sets exactly [plan] and cancels whatever this app set before and the plan no longer holds.
     * A user-set alarm is an alarm CLOCK: exact in Doze, shown in the status bar, and the one kind
     * the system lets wake the device for a ring. Everything else is exact and allowed while idle.
     * Without exact-alarm permission (API 31/32 before the grant; USE_EXACT_ALARM covers 33+) the
     * wakeups are still set, inexactly, and /api/clock/status says so.
     */
    private fun schedule(ctx: Context, plan: List<ClockLogic.Wakeup>) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = prefs(ctx)
        val keys = plan.map { it.key }.toSet()
        (p.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty() - keys).forEach { stale ->
            fireIntent(ctx, stale, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        }
        val exact = canExact(ctx)
        plan.forEach { w ->
            val pi = fireIntent(ctx, w.key, PendingIntent.FLAG_UPDATE_CURRENT) ?: return@forEach
            val set = runCatching {
                when {
                    w.clock && exact -> AlarmManagerCompat.setAlarmClock(am, w.at, openIntent(ctx, ClockDecl.modeId("alarms")), pi)
                    exact -> AlarmManagerCompat.setExactAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, w.at, pi)
                    else -> AlarmManagerCompat.setAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, w.at, pi)
                }
            }
            // The permission can be revoked between the check and the call: never lose the wakeup.
            if (set.isFailure) AlarmManagerCompat.setAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, w.at, pi)
        }
        p.edit().putStringSet(KEY_SCHEDULED, keys).commit()
    }

    /** The wakeup for [key]. Its data URI makes each key its own PendingIntent (extras do not). */
    fun fireIntent(ctx: Context, key: String, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, ClockReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("cloudclock:$key")).putExtra(EXTRA_KEY, key),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Whether the system still holds this app's wakeup for [key]. */
    fun isScheduled(ctx: Context, key: String): Boolean = fireIntent(ctx, key, PendingIntent.FLAG_NO_CREATE) != null

    fun openIntent(ctx: Context, modeId: String?): PendingIntent = PendingIntent.getActivity(
        ctx, (modeId ?: "").hashCode(),
        Intent(ctx, MainActivity::class.java).putExtra(EXTRA_MODE, modeId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
