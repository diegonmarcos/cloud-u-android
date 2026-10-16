package com.diegonmarcos.superapp.health

import androidx.health.connect.client.HealthConnectClient
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure rules for the Health badge's steps (no Android, no Health Connect calls; unit tested in the
 * SuperApp). Steps read ZERO for exactly three reasons: the read permission was never granted, Health
 * Connect needs an update, or nothing writes steps into Health Connect (Samsung Health's own sync is off).
 * The badge says which, instead of showing a bare 0.
 */
object StepsToday {
    /** What the badge needs: steps for the number, the other two for the existing kcal / km halves. */
    const val READ_STEPS = "android.permission.health.READ_STEPS"

    enum class Perm { GRANTED, DENIED, UPDATE_REQUIRED, UNAVAILABLE }

    /** [HealthConnectClient.getSdkStatus] + the granted set -> one state. Android 14+ (built-in HC) reports AVAILABLE. */
    fun permState(sdkStatus: Int, granted: Set<String>): Perm = when (sdkStatus) {
        HealthConnectClient.SDK_AVAILABLE -> if (READ_STEPS in granted) Perm.GRANTED else Perm.DENIED
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Perm.UPDATE_REQUIRED
        else -> Perm.UNAVAILABLE
    }

    /** "Today" for the aggregate: the local calendar day of [now] in [zone], midnight inclusive .. next midnight exclusive. */
    fun todayRange(now: Instant, zone: ZoneId): Pair<LocalDateTime, LocalDateTime> {
        val start = now.atZone(zone).toLocalDate().atStartOfDay()
        return start to start.plusDays(1)
    }

    data class Result(val steps: Long?, val perm: Perm, val origins: Set<String>, val lastDataMs: Long?, val error: String? = null)

    fun hint(r: Result): String? = when {
        r.perm == Perm.UPDATE_REQUIRED -> "Update Health Connect."
        r.perm == Perm.UNAVAILABLE -> "Health Connect is not available on this phone."
        r.perm == Perm.DENIED -> "Steps not allowed yet: tap Grant access."
        r.error != null -> "Health Connect read failed (${r.error})."
        r.steps == 0L && r.origins.isEmpty() ->
            "No step data in Health Connect today. If you use Samsung Health, turn on its Health Connect sync."
        else -> null
    }

    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    /** "Source: Health Connect | Permission: granted | Last data: 14:32". */
    fun statusLine(r: Result, zone: ZoneId): String {
        val p = when (r.perm) {
            Perm.GRANTED -> "granted"; Perm.DENIED -> "not granted"
            Perm.UPDATE_REQUIRED -> "update Health Connect"; Perm.UNAVAILABLE -> "unavailable"
        }
        val last = r.lastDataMs?.let { hm.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "none"
        val o = if (r.origins.isEmpty()) "" else " | From: " + r.origins.sorted().joinToString(", ")
        return "Source: Health Connect | Permission: $p | Last data: $last$o"
    }
}
