package com.diegonmarcos.cloudme

import org.json.JSONArray
import org.json.JSONObject

/**
 * The pure half of Profile > Health and Profile > DD (special-category records).
 *
 * Two inputs, kept apart on purpose:
 *  - the SCHEMA (assets `profiles/<id>.schema.json`): sections and field labels, never a value; it is public code.
 *  - the VALUES (`{"schema":"health","values":{"<field id>":{"value","status","expires"}}}`): the owner's data, imported
 *    from the private store into the on-device store `profile_<id>_user`; it is never in this repo.
 *
 * A field with no value is "Pending — sample, to be filled/verified". Nothing is invented: no value, no claim.
 */
object ProfileStatus {
    const val PENDING = "pending"
    /** Owner-stated, not independently checked. */
    const val DECLARED = "declared"
    const val VERIFIED = "verified"
    const val EXPIRED = "expired"
    val ALL = listOf(PENDING, DECLARED, VERIFIED, EXPIRED)
}

const val PENDING_TEXT = "Pending — sample, to be filled/verified"

data class PField(val id: String, val label: String, val value: String, val status: String, val expires: String) {
    val filled: Boolean get() = value.isNotBlank()
}

data class PSection(val id: String, val title: String, val fields: List<PField>)

data class ProfileDoc(val id: String, val title: String, val sections: List<PSection>) {
    private val all: List<PField> get() = sections.flatMap { it.fields }
    val total: Int get() = all.size
    val filled: Int get() = all.count { it.filled }
    fun count(status: String): Int = all.count { it.status == status }
    /** Whole-number percentage of fields that carry a value. */
    val percent: Int get() = if (total == 0) 0 else filled * 100 / total
}

object ProfileRecord {

    /** Schema merged with [values] (null or empty when nothing was imported). [today] is `YYYY-MM-DD`. */
    fun merge(schema: JSONObject, values: JSONObject?, today: String): ProfileDoc {
        val v = values?.optJSONObject("values") ?: JSONObject()
        val secs = schema.optJSONArray("sections") ?: JSONArray()
        return ProfileDoc(
            schema.optString("id"), schema.optString("title"),
            (0 until secs.length()).mapNotNull { secs.optJSONObject(it) }.map { s ->
                val fs = s.optJSONArray("fields") ?: JSONArray()
                PSection(s.optString("id"), s.optString("title"), (0 until fs.length()).mapNotNull { fs.optJSONObject(it) }.map { f ->
                    val id = f.optString("id")
                    val e = v.optJSONObject(id)
                    PField(id, f.optString("label", id), e?.optString("value").orEmpty(), statusOf(e, today), e?.optString("expires").orEmpty())
                })
            },
        )
    }

    /**
     * pending: no value, or a value with no/unknown status. verified: stated as verified and not past `expires`.
     * expired: stated as verified (or expired) and `expires` is before [today]. declared: stated as declared.
     */
    fun statusOf(e: JSONObject?, today: String): String {
        if (e == null || e.optString("value").isBlank()) return ProfileStatus.PENDING
        val expires = e.optString("expires")
        return when (val s = e.optString("status")) {
            ProfileStatus.VERIFIED, ProfileStatus.EXPIRED ->
                if (s == ProfileStatus.EXPIRED || (expires.isNotBlank() && expires < today)) ProfileStatus.EXPIRED else ProfileStatus.VERIFIED
            ProfileStatus.DECLARED -> ProfileStatus.DECLARED
            else -> ProfileStatus.PENDING
        }
    }

    /** Problems that make an import unsafe; empty means the file may be stored. */
    fun validate(schema: JSONObject, data: JSONObject): List<String> {
        val bad = mutableListOf<String>()
        if (data.optString("schema") != schema.optString("id")) bad += "schema is '${data.optString("schema")}', expected '${schema.optString("id")}'"
        val ids = mutableSetOf<String>()
        val secs = schema.optJSONArray("sections") ?: JSONArray()
        for (i in 0 until secs.length()) secs.optJSONObject(i)?.optJSONArray("fields")?.let { fs ->
            for (j in 0 until fs.length()) fs.optJSONObject(j)?.let { ids += it.optString("id") }
        }
        val v = data.optJSONObject("values")
        if (v == null) bad += "no values object" else v.keys().forEach { k ->
            val e = v.optJSONObject(k)
            if (k !in ids) bad += "unknown field $k"
            else if (e == null) bad += "$k is not an object"
            else if (e.has("status") && e.optString("status") !in ProfileStatus.ALL) bad += "$k has unknown status '${e.optString("status")}'"
        }
        return bad
    }

    /** The report text, section by section: what a PDF page list is built from. */
    fun exportSections(doc: ProfileDoc): List<Pair<String, List<String>>> {
        val head = "${doc.filled} of ${doc.total} fields filled (${doc.percent}%); verified ${doc.count(ProfileStatus.VERIFIED)}, " +
            "declared ${doc.count(ProfileStatus.DECLARED)}, expired ${doc.count(ProfileStatus.EXPIRED)}, pending ${doc.count(ProfileStatus.PENDING)}"
        return listOf("Summary" to listOf(head)) + doc.sections.map { s ->
            s.title to s.fields.map { f ->
                "[${f.status.uppercase()}] ${f.label}: ${f.value.ifBlank { PENDING_TEXT }}" + if (f.expires.isNotBlank()) " (expires ${f.expires})" else ""
            }
        }
    }

    fun pdfName(doc: ProfileDoc) = "profile-${doc.id}.pdf"
}
