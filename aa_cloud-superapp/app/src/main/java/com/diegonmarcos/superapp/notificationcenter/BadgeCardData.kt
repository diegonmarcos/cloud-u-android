package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Locale

/**
 * #1068 — what one badge's One UI live card shows, read from the providers that already exist: the battery sticky
 * intent, [MarketsLastGood] (the Markets badge's persisted quotes), [AlertStore], and — for every other badge — the
 * Notification its own service has posted ([BadgeServices.live]: title, text, big-text lines, progress). Nothing here
 * invents a number: a badge that has posted nothing says why ([BadgeServices.status]) instead of a placeholder value.
 */
object BadgeCardData {

    data class Line(val label: String, val value: String)

    data class Data(
        val badge: BadgeDeclaration.Badge,
        /** The small header text: the badge's label. */
        val appName: String,
        /** The glanceable value, large: "78%", "18° · 💧20%", "€1 = R$5.14". */
        val headline: String,
        val sub: String,
        /** 0..1 when the badge has a level or a share to draw, else null. */
        val progress: Float?,
        val lines: List<Line>,
        val live: Boolean,
        /** The status sentence (why a badge is not in the shade); blank when live. */
        val note: String,
        val whenMs: Long,
        val open: PendingIntent?,
    )

    /** Test seam: a JVM/Robolectric render has no running services, so it supplies the data a phone would have. */
    @Volatile var override: ((Context, BadgeDeclaration.Badge) -> Data?)? = null

    fun read(ctx: Context, b: BadgeDeclaration.Badge): Data {
        override?.invoke(ctx, b)?.let { return it }
        val st = runCatching { BadgeServices.status(ctx, b) }.getOrNull()
        val n = BadgeServices.live(ctx, b)
        val ex = n?.extras
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = ex?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val max = ex?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0
        val posted = ex?.getInt(Notification.EXTRA_PROGRESS, 0) ?: 0
        val postedProgress = if (max > 0) (posted.toFloat() / max).coerceIn(0f, 1f) else null
        val live = n != null && (st == null || st.state == BadgeServices.State.LIVE)
        val note = if (live) "" else st?.reason.orEmpty()
        val extraLines = big.lines().map { it.trim() }.filter { it.isNotEmpty() && it != title && it != text }
            .take(MAX_LINES).map { l ->
                val i = l.indexOf(": ")
                if (i in 1..24) Line(l.substring(0, i), l.substring(i + 2)) else Line("", l)
            }
        fun data(headline: String, sub: String, progress: Float?, lines: List<Line>) = Data(
            b, b.label, headline, sub, progress, lines, live, note, n?.`when` ?: 0L, n?.contentIntent)
        return when (b.card.kind) {
            "level" -> {
                val lv = batteryLevel(ctx)
                data(lv?.let { "${(it.first * 100).toInt()}%" } ?: title.ifBlank { b.label },
                    title.ifBlank { text }.ifBlank { lv?.let { if (it.second) "Charging" else "On battery" }.orEmpty() },
                    lv?.first ?: postedProgress, extraLines)
            }
            "quotes" -> {
                val q = runCatching { MarketsLastGood(ctx).load() }.getOrDefault(emptyMap())
                val lines = b.instruments.map { i ->
                    val p = q[i.symbol]?.price
                    Line(i.label, if (p == null) "—" else String.format(Locale.US, "%.${i.decimals}f", p))
                }
                val newest = b.instruments.mapNotNull { q[it.symbol] }.maxOfOrNull { it.savedAtMillis.takeIf { s -> s > 0 } ?: it.asOfMillis } ?: 0L
                data(lines.firstOrNull()?.let { "${it.label} ${it.value}" } ?: b.label,
                    if (newest > 0) "updated " + ago(System.currentTimeMillis() - newest) else text.ifBlank { note }, null, lines)
            }
            "alerts" -> {
                val a = runCatching { AlertStore.visible(ctx) }.getOrDefault(emptyList())
                data(if (a.isEmpty()) "All clear" else "${a.size} alert${if (a.size == 1) "" else "s"}",
                    a.firstOrNull()?.let { it.title } ?: "Nothing raised by the fleet",
                    null, a.drop(1).take(MAX_LINES).map { Line(it.app, it.title) })
            }
            else -> data(title.ifBlank { b.label }, text, postedProgress, extraLines)
        }
    }

    private const val MAX_LINES = 4

    /** (level 0..1, charging) from the battery sticky intent, or null when the platform gives none. */
    private fun batteryLevel(ctx: Context): Pair<Float, Boolean>? = runCatching {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        (level.toFloat() / scale) to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }.getOrNull()

    private fun ago(ms: Long): String {
        val m = ms / 60_000L
        return when { m < 1 -> "now"; m < 60 -> "${m}m ago"; m < 1_440 -> "${m / 60}h ago"; else -> "${m / 1_440}d ago" }
    }
}
