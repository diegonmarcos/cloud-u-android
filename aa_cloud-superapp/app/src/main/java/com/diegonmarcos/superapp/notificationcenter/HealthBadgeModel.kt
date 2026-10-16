package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.health.StepsToday
import java.util.Locale

/** The Health badge's steps half, as text. Pure so it is unit tested; a missing grant never reads as 0. */
object HealthBadgeModel {
    fun stepsText(r: StepsToday.Result?): String = when {
        r == null -> "steps reading…"
        r.steps != null -> String.format(Locale.US, "%,d steps", r.steps)
        r.perm == StepsToday.Perm.DENIED -> "steps not allowed"
        else -> "steps not available"
    }
}
