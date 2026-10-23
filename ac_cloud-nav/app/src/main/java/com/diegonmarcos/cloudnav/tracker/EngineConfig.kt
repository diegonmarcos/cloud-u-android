package com.diegonmarcos.cloudnav.tracker

/** Where a location fix comes from. */
enum class Provider { GPS, NETWORK }

/** What the engine can do on a tick. */
enum class Action { ACCURATE_FIX, CELL_UPDATE, MOTION_ESTIMATE }

/**
 * The tracking engine's settings (Configs > Tracker). Pure data: the rules that read it
 * ([SourceRules], [TriggerPlanner]) are plain functions so they are unit-tested on the JVM.
 *
 *   a) Source: [useGps] and/or [useCell]; at least one stays on ([withSources]).
 *   b) Accurate ("precise") fix triggers: every [accurateEveryHours] h and/or every [accurateEverySteps] steps.
 *   c) Low-power updates: a cell-tower fix every [cellEveryHours] h, a dead-reckoned estimate every [motionEverySteps] steps.
 */
data class EngineConfig(
    val enabled: Boolean = false,
    val useGps: Boolean = true,
    val useCell: Boolean = true,
    val accurateByTime: Boolean = true,
    val accurateEveryHours: Int = 4,
    val accurateBySteps: Boolean = true,
    val accurateEverySteps: Int = 2000,
    val cellByTime: Boolean = true,
    val cellEveryHours: Int = 1,
    val motionBySteps: Boolean = true,
    val motionEverySteps: Int = 100,
) {
    /** The new source pair, or null when it would leave no source on (the caller keeps the old one). */
    fun withSources(gps: Boolean, cell: Boolean): EngineConfig? =
        if (!gps && !cell) null else copy(useGps = gps, useCell = cell)

    companion object {
        val HOURS_ACCURATE = listOf(1, 2, 4, 6, 8, 12, 24)
        val STEPS_ACCURATE = listOf(500, 1000, 2000, 3000, 5000, 10000)
        val HOURS_CELL = listOf(1, 2, 3, 6, 12)
        val STEPS_MOTION = listOf(25, 50, 100, 200, 500)

        /** The next preset above (dir>0) or below (dir<0) [cur]; [cur] itself at the end of the list. */
        fun step(presets: List<Int>, cur: Int, dir: Int): Int {
            val i = if (dir > 0) presets.indexOfFirst { it > cur } else presets.indexOfLast { it < cur }
            return if (i < 0) cur else presets[i]
        }
    }
}

object SourceRules {
    /**
     * The accurate fix: GPS when GPS is on (also when both are on), the cell/network provider when
     * GPS is off. Never null: [EngineConfig.withSources] guarantees one source.
     */
    fun accurateProvider(c: EngineConfig): Provider = if (c.useGps) Provider.GPS else Provider.NETWORK

    /** The low-power hourly fix: cell towers, and only when the cell source is on. */
    fun lowPowerProvider(c: EngineConfig): Provider? = if (c.useCell) Provider.NETWORK else null
}

/** What the planner needs to know about the engine's progress. */
data class EngineState(
    val lastAccurateMs: Long = 0L,
    val lastCellMs: Long = 0L,
    val stepsSinceAccurate: Long = 0L,
    val stepsSinceMotion: Long = 0L,
)

object TriggerPlanner {
    private const val HOUR_MS = 3_600_000L

    fun accurateDue(c: EngineConfig, s: EngineState, nowMs: Long): Boolean =
        s.lastAccurateMs == 0L ||
            (c.accurateByTime && nowMs - s.lastAccurateMs >= c.accurateEveryHours * HOUR_MS) ||
            (c.accurateBySteps && s.stepsSinceAccurate >= c.accurateEverySteps)

    /**
     * What is due now, strongest first. An accurate fix supersedes the two low-power actions (it
     * resets them), so they are listed only when no accurate fix is due.
     */
    fun due(c: EngineConfig, s: EngineState, nowMs: Long): List<Action> {
        if (!c.enabled) return emptyList()
        if (accurateDue(c, s, nowMs)) return listOf(Action.ACCURATE_FIX)
        val out = mutableListOf<Action>()
        if (SourceRules.lowPowerProvider(c) != null && c.cellByTime &&
            nowMs - s.lastCellMs >= c.cellEveryHours * HOUR_MS) out += Action.CELL_UPDATE
        if (c.motionBySteps && s.stepsSinceMotion >= c.motionEverySteps) out += Action.MOTION_ESTIMATE
        return out
    }
}

/**
 * Battery policy: no high-accuracy (GPS) request while the phone is still. A tick counts as moving when the step counter
 * advanced by at least [MOVING_STEPS] since the previous one; while still, only the network / cell provider may be asked.
 */
object BatteryPolicy {
    const val MOVING_STEPS = 30L

    fun isMoving(stepsSinceLastTick: Long): Boolean = stepsSinceLastTick >= MOVING_STEPS

    /** The provider an accurate fix may use now, or null when the policy forbids a request (still, and no cell source). */
    fun accurateProvider(c: EngineConfig, moving: Boolean): Provider? = when {
        moving -> SourceRules.accurateProvider(c)
        c.useCell -> Provider.NETWORK
        else -> null
    }

    /** May an active GPS request be made at all? */
    fun allowGps(moving: Boolean): Boolean = moving
}
