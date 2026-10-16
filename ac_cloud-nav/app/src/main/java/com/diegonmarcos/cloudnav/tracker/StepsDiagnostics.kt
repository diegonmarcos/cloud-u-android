package com.diegonmarcos.cloudnav.tracker

import androidx.health.connect.client.HealthConnectClient
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Health Connect permission state for steps, as the Steps page and the status line report it. */
enum class PermState { GRANTED, DENIED, UPDATE_REQUIRED, UNAVAILABLE }

/**
 * Pure rules behind the Steps page status line (unit tested; no Android, no Health Connect calls).
 * Why steps read ZERO is almost always one of: permission never asked, Health Connect needs an update,
 * or nothing writes to Health Connect (Samsung Health's own HC sync is off).
 */
object StepsDiagnostics {
    /** Map [HealthConnectClient.getSdkStatus] + the granted set to one state. Android 14+ reports AVAILABLE (built in). */
    fun permState(sdkStatus: Int, granted: Set<String>, required: Set<String>): PermState = when (sdkStatus) {
        HealthConnectClient.SDK_AVAILABLE -> if (granted.containsAll(required)) PermState.GRANTED else PermState.DENIED
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> PermState.UPDATE_REQUIRED
        else -> PermState.UNAVAILABLE
    }

    /** "Today" for aggregation: the local calendar day of [now] in [zone], start inclusive .. next local midnight exclusive. */
    fun todayRange(now: Instant, zone: ZoneId): Pair<LocalDateTime, LocalDateTime> {
        val start = now.atZone(zone).toLocalDate().atStartOfDay()
        return start to start.plusDays(1)
    }

    fun hint(perm: PermState, steps: Long, origins: Set<String>): String? = when {
        perm == PermState.UPDATE_REQUIRED -> "Update Health Connect, then reopen this page."
        perm == PermState.UNAVAILABLE -> "Health Connect is not available on this phone."
        perm == PermState.DENIED -> "Tap Grant access to let Cloud Nav read steps from Health Connect."
        steps == 0L && origins.isEmpty() ->
            "Health Connect has no step data today. If you use Samsung Health, turn on its Health Connect sync " +
                "(Samsung Health > Settings > Connected services > Health Connect > Steps)."
        else -> null
    }

    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    /** "Source: Health Connect | Permission: granted | Last data: 14:32 | Origins: com.sec.android.app.shealth". */
    fun statusLine(source: String, perm: PermState, lastDataMs: Long?, origins: Set<String>, zone: ZoneId): String {
        val p = when (perm) {
            PermState.GRANTED -> "granted"; PermState.DENIED -> "not granted"
            PermState.UPDATE_REQUIRED -> "update Health Connect"; PermState.UNAVAILABLE -> "Health Connect unavailable"
        }
        val last = lastDataMs?.let { hm.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "none"
        val o = if (origins.isEmpty()) "" else " | Origins: " + origins.sorted().joinToString(", ")
        return "Source: $source | Permission: $p | Last data: $last$o"
    }
}

/** Cloud Nav deep link: `page:<section>/<page>` (the SuperApp sends `extapp:cloud-nav#page:timeline/steps`). */
object NavDeepLink {
    const val STEPS = "page:timeline/steps"
    data class Target(val section: String, val page: String?)

    fun parse(raw: String?): Target? {
        val t = raw?.trim() ?: return null
        if (!t.startsWith("page:")) return null
        val body = t.removePrefix("page:").takeIf { it.isNotBlank() } ?: return null
        val parts = body.split("/", limit = 2)
        return Target(parts[0], parts.getOrNull(1)?.takeIf { it.isNotBlank() })
    }
}
