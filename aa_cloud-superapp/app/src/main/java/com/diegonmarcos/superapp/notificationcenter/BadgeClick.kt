package com.diegonmarcos.superapp.notificationcenter

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.diegonmarcos.superapp.MainActivity
import java.util.Locale

/**
 * The ONE click route for a Notify badge. `shortcut_action` is the extra ShellActivity's own router
 * consumes on a cold start (onCreate) and a warm one (onNewIntent): an http(s) target is rendered in the
 * embedded browser (launchUri), an `extapp:<app>#<target>` target opens the companion app on that page
 * (launchExternalApp). Markets (exchange), Weather and Health all tap through here.
 */
object BadgeClick {
    /** Health badge: Cloud Navigation > Timeline > Steps (ac_cloud-nav build.json::ui.deep_links "timeline-steps"). */
    const val HEALTH_STEPS = "extapp:cloud-nav#page:timeline/steps"

    /** Weather has no position yet: the provider's forecast docs page, unparameterised. */
    const val WEATHER_NO_FIX = "https://open-meteo.com/en/docs"

    /** The page of the weather SOURCE (Open-Meteo) for this position, prefilled with the same query the badge fetched. */
    fun weatherUrl(lat: Double?, lon: Double?): String =
        if (lat == null || lon == null) WEATHER_NO_FIX
        else String.format(Locale.US,
            "$WEATHER_NO_FIX#latitude=%.4f&longitude=%.4f&current=temperature_2m&hourly=temperature_2m" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1", lat, lon)

    fun pi(ctx: Context, target: String): PendingIntent = PendingIntent.getActivity(
        ctx, target.hashCode(),
        Intent(ctx, MainActivity::class.java)
            .putExtra("shortcut_action", target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
