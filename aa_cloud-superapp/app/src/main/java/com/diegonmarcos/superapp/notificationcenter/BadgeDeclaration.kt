package com.diegonmarcos.superapp.notificationcenter

import org.json.JSONArray
import org.json.JSONObject

/**
 * #515 — `build.json::ui.notification_center`, resolved ONCE for everybody.
 *
 * Three things used to disagree about what a badge is: the Push pane drew all
 * eight producers because it had no way to tell a shade badge from a channel
 * inventory entry, `App.onCreate` hardcoded the one service it happened to
 * know about, and nothing at all knew which producers had to come back after
 * an update. Three readers, three answers, and the gap between them is exactly
 * where Quickmarks, Media and Alerts went missing: all three are owned by
 * FloatingNavService, which had no restart path, while KdeStatusService had
 * one because someone wrote its start call into App.onCreate by hand.
 *
 * So this is the only place that reads the declaration. [badges] is what the
 * pane renders, [restartServices] is what [BadgeRestartReceiver] re-ensures,
 * and both are DERIVED from the same list — adding a badge is a build.json
 * edit and nothing else.
 *
 * Deliberately free of `Context` and `BuildConfig`: callers hand it the JSON
 * text. That is what lets the unit test drive it with a declaration of its own
 * and assert on the RESOLVED set rather than grepping a file for a word.
 */
object BadgeDeclaration {

    /** One customization control, as declared. The pane maps `type` to a
     *  widget; it never knows which badge it is drawing. */
    data class Option(
        val key: String,
        val type: String,
        val label: String,
        val default: String,
        val options: List<String>,
    )

    data class Badge(
        val id: String,
        val label: String,
        val subtitle: String,
        val icon: String,
        val surface: String,
        val channel: String,
        val owner: String,
        val shows: String,
        val enabled: Boolean,
        /** Does this producer put a PERSISTENT entry in the notification
         *  centre? The one field that splits a badge from plumbing. */
        val isBadge: Boolean,
        /** Must it survive an app update and a reboot? */
        val persistent: Boolean,
        /** Service to (re-)ensure, fully qualified. Blank = nothing to start. */
        val service: String,
        /** Grants this badge cannot post without — so a dead badge can say WHY. */
        val requires: List<String>,
        val notBadgeReason: String,
        val customization: List<Option>,
    )

    /** Parse the whole declaration. A producer with no id or no label is
     *  dropped rather than drawn half-blank — that is a parse error, not a
     *  producer, and a half-blank row on a settings page is invisible. */
    fun parse(json: String): List<Badge> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr: JSONArray = root.optJSONArray("producers") ?: return emptyList()
        val out = mutableListOf<Badge>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val label = o.optString("label")
            if (id.isBlank() || label.isBlank()) continue
            out += Badge(
                id = id,
                label = label,
                subtitle = o.optString("subtitle", ""),
                icon = o.optString("icon", ""),
                surface = o.optString("surface", ""),
                channel = o.optString("channel", ""),
                owner = o.optString("owner", ""),
                shows = o.optString("shows", ""),
                enabled = o.optBoolean("enabled", false),
                isBadge = o.optBoolean("badge", false),
                persistent = o.optBoolean("persistent", false),
                service = o.optString("service", ""),
                requires = strings(o.optJSONArray("requires")),
                notBadgeReason = o.optString("not_badge_reason", ""),
                customization = options(o.optJSONArray("customization")),
            )
        }
        return out
    }

    /** What Configs ▸ Panel ▸ Push renders — one box in section 1 and one
     *  customization menu in section 2 per entry, in declaration order. */
    fun badges(all: List<Badge>): List<Badge> = all.filter { it.isBadge }

    /**
     * The services that must be running for every persistent badge, resolved
     * and de-duplicated — FloatingNavService owns three badges and must be
     * started once, not three times.
     *
     * A badge the owner has switched off is not in here: "declared off" is a
     * decision, and re-ensuring it would override the owner on every reboot.
     */
    fun restartServices(all: List<Badge>): List<String> =
        all.filter { it.isBadge && it.persistent && it.enabled && it.service.isNotBlank() }
            .map { it.service }
            .distinct()

    /** Badges a given service is responsible for — the pane uses this to
     *  attribute one dead service to every badge it took down with it. */
    fun badgesOf(all: List<Badge>, service: String): List<Badge> =
        all.filter { it.isBadge && it.service == service }

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }

    private fun options(a: JSONArray?): List<Option> {
        if (a == null) return emptyList()
        val out = mutableListOf<Option>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isBlank()) continue
            out += Option(
                key = key,
                type = o.optString("type", "toggle"),
                label = o.optString("label", key),
                // Declared defaults are booleans for toggles and strings for
                // choices; normalising to text here keeps one storage shape.
                default = o.opt("default")?.toString() ?: "",
                options = strings(o.optJSONArray("options")),
            )
        }
        return out
    }
}
