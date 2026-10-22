package com.diegonmarcos.superapp.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * The My-Health board's reads (SuperApp Boards > My-PM): per local day steps, distance, resting heart rate and sleep
 * stages over a window ending today, as plain values (no Health Connect types leave this file). A day with no
 * record is simply absent from its map: "no data" is not zero, and the board's aggregation treats it that way.
 */
object HealthBoardReader {

    val STEPS = HealthPermission.getReadPermission(StepsRecord::class)
    val DISTANCE = HealthPermission.getReadPermission(DistanceRecord::class)
    val RESTING_HR = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    val SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
    /** Optional: only sharpens the stride length behind estimated km. */
    val HEIGHT = HealthPermission.getReadPermission(HeightRecord::class)

    /** What the board asks for in one dialog. */
    val BOARD_PERMISSIONS: Set<String> = setOf(STEPS, DISTANCE, RESTING_HR, SLEEP, HEIGHT)

    /** One night, in minutes, by stage. [staged] false = the source wrote only a session span (all counted as asleep). */
    data class Night(val deepMin: Long, val remMin: Long, val lightMin: Long, val awakeMin: Long, val staged: Boolean) {
        val asleepMin: Long get() = deepMin + remMin + lightMin
    }

    enum class Status { READY, NEEDS_PERMISSION, UPDATE_REQUIRED, UNAVAILABLE, ERROR }

    data class Raw(
        val status: Status,
        val granted: Set<String>,
        val steps: Map<LocalDate, Long> = emptyMap(),
        val distanceM: Map<LocalDate, Double> = emptyMap(),
        val restingHr: Map<LocalDate, Long> = emptyMap(),
        /** Keyed by the day the sleep ENDED (waking up), the way "last night" is read. */
        val sleep: Map<LocalDate, Night> = emptyMap(),
        val heightM: Double? = null,
        val error: String? = null,
    )

    suspend fun read(context: Context, days: Int = 30, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Raw =
        withContext(Dispatchers.IO) {
            val sdk = runCatching { HealthConnectClient.getSdkStatus(context, HealthConnectGateway.PROVIDER_PACKAGE) }
                .getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)
            if (sdk == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) return@withContext Raw(Status.UPDATE_REQUIRED, emptySet())
            val c = if (sdk == HealthConnectClient.SDK_AVAILABLE) HealthConnectGateway.client(context) else null
            if (c == null) return@withContext Raw(Status.UNAVAILABLE, emptySet())
            val granted = runCatching { c.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
            val first = today.minusDays((days - 1).toLong())
            val localRange = TimeRangeFilter.between(first.atStartOfDay(), today.plusDays(1).atStartOfDay())
            var err: String? = null
            suspend fun <T> guarded(perm: String, empty: T, f: suspend () -> T): T =
                if (perm !in granted) empty else try { f() } catch (t: Throwable) { err = t.javaClass.simpleName; empty }

            val steps = guarded(STEPS, emptyMap()) {
                c.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), localRange, Period.ofDays(1)))
                    .mapNotNull { g -> g.result[StepsRecord.COUNT_TOTAL]?.let { g.startTime.toLocalDate() to it } }.toMap()
            }
            val dist = guarded(DISTANCE, emptyMap()) {
                c.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(DistanceRecord.DISTANCE_TOTAL), localRange, Period.ofDays(1)))
                    .mapNotNull { g -> g.result[DistanceRecord.DISTANCE_TOTAL]?.let { g.startTime.toLocalDate() to it.inMeters } }.toMap()
            }
            val rhr = guarded(RESTING_HR, emptyMap()) {
                c.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(RestingHeartRateRecord.BPM_AVG), localRange, Period.ofDays(1)))
                    .mapNotNull { g -> g.result[RestingHeartRateRecord.BPM_AVG]?.let { g.startTime.toLocalDate() to it } }.toMap()
            }
            val sleep = guarded(SLEEP, emptyMap()) {
                // From a day earlier: last night's session starts before midnight.
                val range = TimeRangeFilter.between(first.minusDays(1).atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant())
                val recs = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range, pageSize = 500)).records
                nights(recs.map { r -> SessionIn(r.endTime.atZone(zone).toLocalDate(), r.startTime, r.endTime, r.stages.map { StageIn(it.startTime, it.endTime, it.stage) }) })
                    .filterKeys { it >= first && it <= today }
            }
            val height = guarded(HEIGHT, null as Double?) {
                c.readRecords(ReadRecordsRequest(HeightRecord::class, TimeRangeFilter.before(Instant.now()), ascendingOrder = false, pageSize = 1))
                    .records.firstOrNull()?.height?.inMeters
            }
            Raw(if (STEPS in granted || DISTANCE in granted || RESTING_HR in granted || SLEEP in granted) Status.READY else Status.NEEDS_PERMISSION,
                granted, steps, dist, rhr, sleep, height, err)
        }

    data class StageIn(val start: Instant, val end: Instant, val type: Int)
    data class SessionIn(val wakeDate: LocalDate, val start: Instant, val end: Instant, val stages: List<StageIn>)

    /** Sessions -> nights by wake-up day. Pure (tested in the SuperApp). Several sessions on a day add up. */
    fun nights(sessions: List<SessionIn>): Map<LocalDate, Night> =
        sessions.groupBy { it.wakeDate }.mapValues { (_, list) ->
            var deep = 0L; var rem = 0L; var light = 0L; var awake = 0L; var staged = true
            for (s in list) {
                if (s.stages.isEmpty()) { light += minutes(s.start, s.end); staged = false; continue }
                for (st in s.stages) {
                    val m = minutes(st.start, st.end)
                    when (st.type) {
                        SleepSessionRecord.STAGE_TYPE_DEEP -> deep += m
                        SleepSessionRecord.STAGE_TYPE_REM -> rem += m
                        SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> light += m
                        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> awake += m
                        else -> {} // OUT_OF_BED / UNKNOWN: neither asleep nor awake in bed
                    }
                }
            }
            Night(deep, rem, light, awake, staged)
        }

    private fun minutes(a: Instant, b: Instant): Long = ((b.epochSecond - a.epochSecond) / 60).coerceAtLeast(0)
}
