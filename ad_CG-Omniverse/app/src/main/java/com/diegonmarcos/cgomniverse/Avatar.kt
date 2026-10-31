package com.diegonmarcos.cgomniverse

import org.json.JSONObject

/** One option of an avatar group: a shape the scene draws (choice) or a colour (color, "#rrggbb"). */
data class AvatarOption(val id: String, val label: String, val color: String?)

/** One personalisation group of data/avatar.json. */
data class AvatarGroup(val id: String, val label: String, val kind: String, val options: List<AvatarOption>)

/** The declared configurator: its groups and the default avatar (one option id per group). */
data class AvatarSpec(val groups: List<AvatarGroup>, val defaults: Map<String, String>)

/**
 * The avatar configuration: one option id per declared group. What is saved is that map; what the scene receives is the same map
 * with each colour resolved to its hex. A saved id the declaration no longer has (or a missing group) falls back to the default,
 * so an old save never draws a broken avatar. Pure, so the JVM suite covers it.
 */
object Avatar {
    const val KIND_COLOR = "color"

    fun spec(json: String): AvatarSpec {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return AvatarSpec(emptyList(), emptyMap())
        val arr = o.optJSONArray("groups")
        val groups = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { g ->
            val opts = g.optJSONArray("options")
            AvatarGroup(
                id = g.optString("id"), label = g.optString("label"), kind = g.optString("kind"),
                options = if (opts == null) emptyList() else (0 until opts.length()).mapNotNull { opts.optJSONObject(it) }.map {
                    AvatarOption(it.optString("id"), it.optString("label"), it.optString("color").ifEmpty { null })
                },
            )
        }
        val d = o.optJSONObject("default")
        val defaults = groups.associate { g -> g.id to (d?.optString(g.id)?.takeIf { id -> g.options.any { it.id == id } } ?: g.options.firstOrNull()?.id.orEmpty()) }
        return AvatarSpec(groups, defaults)
    }

    /** [saved] (a JSON object of group to id, or anything else) made whole against [spec]: every group, every id declared. */
    fun load(spec: AvatarSpec, saved: String?): Map<String, String> {
        val o = saved?.let { runCatching { JSONObject(it) }.getOrNull() }
        return spec.groups.associate { g ->
            val id = o?.optString(g.id)
            g.id to (if (id != null && g.options.any { it.id == id }) id else spec.defaults[g.id].orEmpty())
        }
    }

    fun save(cfg: Map<String, String>): String = JSONObject(cfg.toSortedMap() as Map<*, *>).toString()

    fun set(spec: AvatarSpec, cfg: Map<String, String>, group: String, id: String): Map<String, String> {
        val g = spec.groups.firstOrNull { it.id == group } ?: return cfg
        return if (g.options.any { it.id == id }) cfg + (group to id) else cfg
    }

    /** What avatar.js draws: the configuration with each colour id replaced by its hex. */
    fun sceneJson(spec: AvatarSpec, cfg: Map<String, String>): String {
        val out = JSONObject()
        spec.groups.forEach { g ->
            val id = cfg[g.id] ?: spec.defaults[g.id].orEmpty()
            val opt = g.options.firstOrNull { it.id == id }
            out.put(g.id, if (g.kind == KIND_COLOR) opt?.color ?: "" else id)
        }
        return out.toString()
    }
}
