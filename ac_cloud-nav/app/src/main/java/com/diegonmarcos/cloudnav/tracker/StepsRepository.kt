package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import android.location.Geocoder
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.diegonmarcos.cloudnav.maps.MapsDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period as JPeriod
import java.time.ZoneId
import java.util.Locale

/**
 * Loads the Steps page. Health Connect (StepsRecord) is the source of truth when it is installed and
 * the read permission is granted; otherwise the page says so and shows what the tracker engine
 * itself counted on this device (labelled as such).
 */
class StepsRepository(private val ctx: Context) {
    companion object {
        val READ_STEPS: Set<String> = setOf(HealthPermission.getReadPermission(StepsRecord::class))
        fun permissionContract() = PermissionController.createRequestPermissionResultContract()
        private const val HISTORY_DAYS = 400L
    }

    private val names = HashMap<String, String>()

    suspend fun load(zone: ZoneId = ZoneId.systemDefault()): StepsUi = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dayStart = LocalDate.now(zone).minusDays(StepsModel.CARD_DAYS - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
        val fixes = fixes(dayStart, now)
        val stepM = EnginePrefs(ctx).stepLengthM
        val label: (PlaceFix) -> String = { name(it) }

        val hcStatus = HealthConnectClient.getSdkStatus(ctx)
        if (hcStatus == HealthConnectClient.SDK_AVAILABLE) {
            val client = HealthConnectClient.getOrCreate(ctx)
            if (client.permissionController.getGrantedPermissions().containsAll(READ_STEPS)) {
                runCatching {
                    val daily = hcDaily(client, zone)
                    val slices = hcSlices(client, dayStart, now)
                    return@withContext StepsModel.build(StepsSource.HEALTH_CONNECT, SourceStatus.READY, daily, slices, fixes, zone, now, stepM, label)
                }
            }
            return@withContext device(SourceStatus.NEEDS_PERMISSION, zone, now, dayStart, fixes, stepM, label)
        }
        device(SourceStatus.HEALTH_CONNECT_MISSING, zone, now, dayStart, fixes, stepM, label)
    }

    private fun device(st: SourceStatus, zone: ZoneId, now: Long, dayStart: Long, fixes: List<PlaceFix>, stepM: Double, label: (PlaceFix) -> String): StepsUi {
        val all = EngineDb.get(ctx).slices(now - HISTORY_DAYS * 86_400_000L, now)
        return StepsModel.build(StepsSource.DEVICE, st, StepStats.perDay(all, zone), all.filter { it.startMs >= dayStart }, fixes, zone, now, stepM, label)
    }

    private suspend fun hcDaily(c: HealthConnectClient, zone: ZoneId): Map<LocalDate, Long> {
        val end = LocalDateTime.now(zone).plusDays(1).toLocalDate().atStartOfDay()
        val res = c.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(
            setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(end.minusDays(HISTORY_DAYS), end), JPeriod.ofDays(1)))
        return res.associate { it.startTime.toLocalDate() to (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) }
    }

    private suspend fun hcSlices(c: HealthConnectClient, fromMs: Long, toMs: Long): List<StepSlice> {
        val out = mutableListOf<StepSlice>()
        var token: String? = null
        do {
            val r = c.readRecords(ReadRecordsRequest(StepsRecord::class,
                TimeRangeFilter.between(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs)), pageSize = 5000, pageToken = token))
            r.records.mapTo(out) { StepSlice(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.count) }
            token = r.pageToken
        } while (token != null && out.size < 100_000)
        return out
    }

    /** The engine's fixes plus the foreground tracker's GPS points (read-only), oldest first. */
    private fun fixes(from: Long, to: Long): List<PlaceFix> {
        val out = EngineDb.get(ctx).fixes(from, to).toMutableList()
        runCatching {
            MapsDb.get(ctx).readableDatabase.rawQuery(
                "SELECT ts,lat,lon,accuracy FROM points WHERE ts BETWEEN ? AND ? ORDER BY ts", arrayOf(from.toString(), to.toString())).use { c ->
                while (c.moveToNext()) out += PlaceFix(c.getLong(0), c.getDouble(1), c.getDouble(2), false, if (c.isNull(3)) 0.0 else c.getDouble(3))
            }
        }
        return out.sortedBy { it.ts }
    }

    /** Geocoder place name when the platform has one, else the coordinates. Cached on a ~100 m grid. */
    @Suppress("DEPRECATION")
    private fun name(f: PlaceFix): String {
        val key = "%.3f,%.3f".format(Locale.US, f.lat, f.lon)
        return names.getOrPut(key) {
            val coords = "%.4f, %.4f".format(Locale.US, f.lat, f.lon)
            if (!Geocoder.isPresent()) return@getOrPut coords
            runCatching {
                Geocoder(ctx, Locale.getDefault()).getFromLocation(f.lat, f.lon, 1)?.firstOrNull()?.let { a ->
                    listOfNotNull(a.thoroughfare ?: a.featureName, a.locality ?: a.subAdminArea).distinct().joinToString(", ").ifBlank { null }
                }
            }.getOrNull() ?: coords
        }
    }
}
