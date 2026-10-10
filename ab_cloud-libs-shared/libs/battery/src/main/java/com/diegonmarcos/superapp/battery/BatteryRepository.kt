package com.diegonmarcos.superapp.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.diegonmarcos.superapp.adbdebug.ShellChannels

/**
 * The battery SoT's Android half: it READS the battery ([probe]), RECORDS the
 * history ([record]) and hands [BatteryTruth.compute] both ([report]). The
 * badge, the popup and Configs › About › Battery all call [report]; nothing
 * else in the app reads CURRENT_NOW for display or keeps a rate of its own.
 *
 * RECORDER. A sample is written when the battery service runs (every
 * ACTION_BATTERY_CHANGED it receives, coalesced, plus screen on/off), on the
 * 15-minute BatterySessionWorker tick, and whenever a surface asks for a
 * report — never on a timer of its own and never under a wakelock. A sample
 * is kept only when something moved (level, plug, status, screen) or every
 * [BatteryHistory.HEARTBEAT_MS] otherwise, so 14 days stay a few thousand rows. The history
 * lives in battery_sot.db (SQLite, like libs:battery's energy_watchdog.db).
 *
 * The "now" current is an EMA ([BatteryMath.ema]) kept in memory and rebuilt
 * from the newest recorded currents after a process restart.
 */
object BatteryRepository {

    private val lock = Any()
    private var db: Db? = null
    private var cache: ArrayList<BatterySample>? = null
    private var ema: BatteryMath.Ema? = null
    private var scale = BatteryMath.CurrentScale.UNKNOWN

    // ── reading ──────────────────────────────────────────────────────────

    /** The battery right now: the sticky ACTION_BATTERY_CHANGED + BatteryManager properties. */
    fun probe(ctx: Context, sticky: Intent? = null, now: Long = System.currentTimeMillis()): BatteryReading? {
        val i = sticky ?: runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scaleMax = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        fun prop(id: Int): Int? = runCatching { bm?.getIntProperty(id) }.getOrNull()?.takeIf { it != Int.MIN_VALUE }
        val counter = runCatching { bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrNull()
            ?.takeIf { it != Long.MIN_VALUE && it > 0L }
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return BatteryReading(
            nowMs = now,
            levelPct = if (level >= 0 && scaleMax > 0) level * 100 / scaleMax else -1,
            status = status,
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 1),
            technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            tempDc = temp.takeIf { it != Int.MIN_VALUE },
            voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
            rawCurrentNow = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            rawCurrentAvg = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            counterUah = counter,
            systemCycles = if (Build.VERSION.SDK_INT >= 34)
                i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it > 0 } else null,
            systemChargeRemainingMs = if (Build.VERSION.SDK_INT >= 28 && status == BatteryManager.BATTERY_STATUS_CHARGING)
                runCatching { bm?.computeChargeTimeRemaining() }.getOrNull() ?: -1L else -1L,
            screenOn = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive }.getOrNull(),
        )
    }

    // ── recording ────────────────────────────────────────────────────────

    /** Record the battery now (the badge's receiver, the worker tick). Returns the reading it recorded. */
    fun record(ctx: Context, sticky: Intent? = null): BatteryReading? {
        val r = probe(ctx, sticky) ?: return null
        synchronized(lock) { fold(ctx, r) }
        return r
    }

    /** Learn the scale, advance the EMA, store the sample when it carries news. Under [lock]. */
    private fun fold(ctx: Context, r: BatteryReading): List<BatterySample> {
        val list = load(ctx)
        val learned = BatteryMath.learnScale(scale, r.rawCurrentNow)
        if (learned != scale) { scale = learned; runCatching { db(ctx).putMeta(K_SCALE, learned.name) } }
        val sample = BatteryTruth.sampleOf(r, scale)
        sample.currentMa?.let { ema = BatteryMath.ema(ema, it.toDouble(), r.nowMs) }
        if (r.levelPct in 0..100 && BatteryHistory.shouldStore(list.lastOrNull(), sample)) {
            runCatching { db(ctx).insert(sample, r.nowMs - BatteryHistory.RETENTION_MS) }
            list += sample
            val cut = r.nowMs - BatteryHistory.RETENTION_MS
            if (list.isNotEmpty() && list.first().ts < cut) list.removeAll { it.ts < cut }
        }
        return list
    }

    // ── the report ───────────────────────────────────────────────────────

    /** THE read every battery surface makes. Records the reading as a side effect. */
    fun report(ctx: Context, sticky: Intent? = null): BatteryReport? {
        val r = probe(ctx, sticky) ?: return null
        val (rated, cycles) = extras(ctx, r.nowMs)
        return synchronized(lock) {
            val list = fold(ctx, r)
            val sample = BatteryTruth.sampleOf(r, scale)
            val samples = if (list.lastOrNull()?.ts == sample.ts) list.toList() else list + sample
            BatteryTruth.compute(r, samples, ema, BatteryExtras(ratedMah = rated, cycleEstimate = cycles, scale = scale))
        }
    }

    /** The extras change slowly and their read forks `dumpsys battery` while charging: refreshed every [EXTRAS_TTL_MS]. */
    const val EXTRAS_TTL_MS = 5 * 60_000L
    @Volatile private var extrasAt = 0L
    @Volatile private var extras: Pair<Int?, Double?> = null to null

    /** libs:battery keeps the CHARGE_COUNTER cycle count and the rated capacity; read, not re-derived. */
    private fun extras(ctx: Context, now: Long): Pair<Int?, Double?> {
        if (extrasAt != 0L && now - extrasAt in 0 until EXTRAS_TTL_MS) return extras
        val session = runCatching { BatterySessionStats.read(ctx, now) }.getOrNull()
        val cap = runCatching { BatteryCapacity.read(ctx, session?.peakChargeCounterUah ?: 0L) }.getOrNull()
        extras = cap?.ratedMah?.takeIf { it > 0 } to session?.cycleCount
        extrasAt = now
        return extras
    }

    /** The recorded history from [fromTs] (the stats page's graph and spreads). */
    fun samples(ctx: Context, fromTs: Long = 0L): List<BatterySample> =
        synchronized(lock) { load(ctx).filter { it.ts >= fromTs } }

    /** The privileged facts (sysfs capacity/cycles, per-app power) — blocking, call off the main thread. */
    fun privileged(ctx: Context): BatteryPrivileged.Facts? {
        val ch = runCatching { ShellChannels.active(ctx.applicationContext) }.getOrNull() ?: return null
        return BatteryPrivileged.parse(runCatching { ch.exec(ctx.applicationContext, BatteryPrivileged.COMMAND) }.getOrNull())
    }

    /** "Channel name" when a privileged shell is up, else null. */
    fun privilegedChannel(ctx: Context): String? =
        runCatching { ShellChannels.active(ctx.applicationContext)?.name() }.getOrNull()

    // ── storage ──────────────────────────────────────────────────────────

    private fun db(ctx: Context): Db = db ?: Db(ctx.applicationContext).also { db = it }

    private fun load(ctx: Context): ArrayList<BatterySample> {
        cache?.let { return it }
        val d = db(ctx)
        val now = System.currentTimeMillis()
        val list = ArrayList(runCatching { d.since(now - BatteryHistory.RETENTION_MS) }.getOrDefault(emptyList()))
        scale = runCatching { d.meta(K_SCALE) }.getOrNull()
            ?.let { s -> BatteryMath.CurrentScale.entries.firstOrNull { it.name == s } } ?: scale
        if (ema == null) ema = BatteryHistory.replayEma(list.filter { it.ts >= now - BatteryMath.EMA_MAX_GAP_MS })
        cache = list
        return list
    }

    private const val K_SCALE = "current_scale"

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, 1) {
        private val app = ctx

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sample (ts INTEGER PRIMARY KEY, level INTEGER, status INTEGER, plugged INTEGER, " +
                "ma INTEGER, mv INTEGER, temp_dc INTEGER, counter INTEGER, screen INTEGER)")
            db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
            runCatching { LegacyImport.into(app) { s -> write(db, s) } }
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            db.execSQL("DROP TABLE IF EXISTS sample"); db.execSQL("DROP TABLE IF EXISTS meta"); onCreate(db)
        }

        fun insert(s: BatterySample, pruneBefore: Long) {
            val db = writableDatabase
            write(db, s)
            db.execSQL("DELETE FROM sample WHERE ts < ?", arrayOf<Any?>(pruneBefore))
        }

        private fun write(db: SQLiteDatabase, s: BatterySample) = db.execSQL(
            "INSERT OR REPLACE INTO sample VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(s.ts, s.levelPct, s.status, s.plugged, s.currentMa, s.voltageMv, s.tempDc, s.counterUah,
                s.screenOn?.let { if (it) 1 else 0 }))

        fun since(fromTs: Long): List<BatterySample> {
            val out = ArrayList<BatterySample>()
            readableDatabase.rawQuery("SELECT * FROM sample WHERE ts >= ? ORDER BY ts ASC", arrayOf(fromTs.toString())).use { c ->
                fun intOrNull(i: Int) = if (c.isNull(i)) null else c.getInt(i)
                while (c.moveToNext()) out += BatterySample(
                    ts = c.getLong(0), levelPct = c.getInt(1), status = c.getInt(2), plugged = c.getInt(3),
                    currentMa = intOrNull(4), voltageMv = intOrNull(5), tempDc = intOrNull(6),
                    counterUah = if (c.isNull(7)) null else c.getLong(7),
                    screenOn = intOrNull(8)?.let { it == 1 })
            }
            return out
        }

        fun meta(k: String): String? =
            readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

        fun putMeta(k: String, v: String) =
            writableDatabase.execSQL("INSERT OR REPLACE INTO meta VALUES (?, ?)", arrayOf<Any?>(k, v))

        companion object { const val NAME = "battery_sot.db" }
    }

    /**
     * One-time move of the history the SoT replaces: the completed sessions of
     * the old "battery_history" ledger (two samples each, start and end) and the
     * unplug anchor libs:battery holds, so "since last charge" and the cycle
     * tables are not empty on the first day. The old ledger is cleared after.
     */
    private object LegacyImport {
        private const val PREFS = "battery_history"
        /** The old ledger never recorded the plug type: "on power, source unknown". */
        private const val PLUG_UNKNOWN = 0x80

        fun into(ctx: Context, write: (BatterySample) -> Unit) {
            val cut = System.currentTimeMillis() - BatteryHistory.RETENTION_MS
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val rows = (sp.getString("sessions", "") ?: "").split('\n').mapNotNull { line ->
                val f = line.split('|')
                if (f.size != 5) return@mapNotNull null
                val start = f[1].toLongOrNull() ?: return@mapNotNull null
                val end = f[2].toLongOrNull() ?: return@mapNotNull null
                val a = f[3].toIntOrNull() ?: return@mapNotNull null
                val b = f[4].toIntOrNull() ?: return@mapNotNull null
                if (end < cut || a !in 0..100 || b !in 0..100) null else listOf(f[0] == "C", start, end, a, b)
            }.sortedBy { it[1] as Long }
            for (r in rows) {
                val on = r[0] as Boolean
                val plug = if (on) PLUG_UNKNOWN else 0
                val st = if (on) BatteryMath.STATUS_CHARGING else BatteryMath.STATUS_DISCHARGING
                write(BatterySample(r[1] as Long, r[3] as Int, st, plug))
                write(BatterySample(r[2] as Long, r[4] as Int, st, plug))
            }
            val anchor = runCatching { BatterySessionStats.read(ctx) }.getOrNull()
            if (anchor != null && anchor.unplugTs > cut && anchor.unplugPct in 0..100)
                write(BatterySample(anchor.unplugTs, anchor.unplugPct, BatteryMath.STATUS_DISCHARGING, 0))
            sp.edit().clear().apply()
        }
    }
}
