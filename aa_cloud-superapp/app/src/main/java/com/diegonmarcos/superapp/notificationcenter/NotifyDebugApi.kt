package com.diegonmarcos.superapp.notificationcenter

import android.app.NotificationManager
import android.content.Context
import com.diegonmarcos.superapp.core.FleetAlerts
import com.diegonmarcos.superapp.devtools.AppDebugServer
import org.json.JSONArray
import org.json.JSONObject

/**
 * #777 — `/api/notify/…` on the fleet debug API (loopback + fleet token), so
 * the four groups and the alerts can be checked with the phone locked:
 *
 *   /api/notify/groups                         groups in the owner's order, on/off, members + live state
 *   /api/notify/alerts                         stored alerts (+ which are in the shade)
 *   /api/notify/alerts?action=raise&title=…    raise one through the REAL FleetAlerts path
 *   /api/notify/alerts?action=clear[&app=pkg]  Clear all (or one app's)
 *
 * "In the shade" is read from NotificationManager, not from the store, so
 * this answers what the user sees.
 */
object NotifyDebugApi {

    fun register(ctx: Context) {
        val app = ctx.applicationContext
        AppDebugServer.route(
            "notify",
            listOf(
                AppDebugServer.Op("groups", "", "the four shade groups: order, on/off, members and their live state"),
                AppDebugServer.Op("alerts", "action=list|raise|clear, title, text, severity, deep_link, dedupe_key, app",
                    "fleet alerts: list, raise a test alert through FleetAlerts, Clear all"),
            ),
        ) { op, q ->
            when (op) {
                "groups" -> groups(app).toString()
                "alerts" -> alerts(app, q).toString()
                else -> null
            }
        }
    }

    fun groups(ctx: Context): JSONObject {
        val posted = posted(ctx)
        val arr = JSONArray()
        for (g in NotifyGroups.ordered(ctx)) {
            val members = JSONArray()
            for (id in g.members) {
                val b = BadgeServices.declared.firstOrNull { it.id == id } ?: continue
                val s = BadgeServices.status(ctx, b)
                members.put(JSONObject().put("id", id).put("label", b.label)
                    .put("enabled", BadgeCustomization.isEnabled(ctx, b))
                    .put("state", s.state.name).put("reason", s.reason)
                    .put("in_shade", posted.any { it.first == NotifyGroups.key(g) && it.second == b.channel }))
            }
            arr.put(JSONObject().put("id", g.id).put("label", g.label).put("key", NotifyGroups.key(g))
                .put("enabled", NotifyGroups.isEnabled(ctx, g)).put("alerts", g.alerts)
                .put("children_in_shade", posted.count { it.first == NotifyGroups.key(g) && !it.third })
                .put("summary_in_shade", posted.any { it.first == NotifyGroups.key(g) && it.third })
                .put("members", members))
        }
        return JSONObject().put("groups", arr)
    }

    fun alerts(ctx: Context, q: Map<String, String>): JSONObject {
        val out = JSONObject()
        when (q["action"].orEmpty().ifBlank { "list" }) {
            "raise" -> out.put("delivery", FleetAlerts.raise(ctx, FleetAlerts.Alert(
                title = q["title"].orEmpty().ifBlank { "Test alert" },
                text = q["text"].orEmpty(),
                severity = q["severity"].orEmpty().ifBlank { FleetAlerts.INFO },
                deepLink = q["deep_link"].orEmpty(),
                dedupeKey = q["dedupe_key"].orEmpty(),
            )).name)
            "clear" -> { out.put("cleared", AlertStore.clear(ctx, q["app"])); AlertsNotifier.refresh(ctx) }
            "list" -> Unit
            else -> return out.put("error", "action must be list, raise or clear")
        }
        val inShade = posted(ctx).count { it.first == NotifyGroups.alertsGroup?.let(NotifyGroups::key) && !it.third }
        val visible = AlertStore.visible(ctx).map { it.id }.toSet()
        return out.put("count", AlertStore.all(ctx).size).put("in_shade", inShade)
            .put("alerts", JSONArray().apply {
                AlertStore.all(ctx).forEach { put(it.json().put("visible", it.id in visible)) }
            })
    }

    /** (group key, channel, isSummary) for every notification this app has posted. */
    private fun posted(ctx: Context): List<Triple<String?, String?, Boolean>> = runCatching {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications.map {
            Triple(it.notification.group, it.notification.channelId,
                it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0)
        }
    }.getOrDefault(emptyList())
}
