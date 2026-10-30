package com.diegonmarcos.cloudme

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1350 Achieves became one tab per kind of experience. The old Experiences page stored the owner's records in the
 * collections `done` and `todo` of experiences_user; build.json::ui.sections[achieves].migration says where each goes: by the
 * value of [field] to a tab, unknown values to [default], into `<tab><suffix>`, with optional field renames per tab
 * (a renamed field never overwrites a value the record already has). Every record lands somewhere and keeps every
 * key; the legacy collections are then dropped, so a second run changes nothing.
 */
data class AchievesMigrationSpec(
    val legacy: Map<String, String>, val field: String, val byValue: Map<String, String>, val default: String,
    val rename: Map<String, Map<String, String>>,
) {
    companion object {
        private fun map(o: JSONObject?): Map<String, String> = if (o == null) emptyMap() else o.keys().asSequence().associateWith { o.getString(it) }
        fun parse(o: JSONObject) = AchievesMigrationSpec(
            map(o.optJSONObject("legacy")), o.optString("field", "category"), map(o.optJSONObject("by_value")),
            o.getString("default"),
            o.optJSONObject("rename")?.let { r -> r.keys().asSequence().associateWith { map(r.optJSONObject(it)) } }.orEmpty(),
        )
    }
}

object AchievesMigration {
    /** The declared spec for [store], from the baked ui.sections (build.json); null when no section declares one. */
    fun declared(sections: JSONArray, store: String): AchievesMigrationSpec? =
        (0 until sections.length()).mapNotNull { sections.optJSONObject(it)?.optJSONObject("migration") }
            .firstOrNull { it.optString("store") == store }?.let { AchievesMigrationSpec.parse(it) }

    fun migrate(d: LedgerData, s: AchievesMigrationSpec): LedgerData {
        var out = d
        for ((old, suffix) in s.legacy) {
            for (r in d.rows(old)) {
                val tab = s.byValue[r[s.field].orEmpty().trim()] ?: s.default
                var rec = r - "id"
                for ((from, to) in s.rename[tab].orEmpty()) {
                    val v = rec[from]
                    if (!v.isNullOrBlank() && rec[to].isNullOrBlank()) rec = rec - from + (to to v)
                }
                out = out.added(tab + suffix, rec)
            }
            out = out.without(old)
        }
        return out
    }
}
