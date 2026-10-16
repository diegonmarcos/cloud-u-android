package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A trailing generated suffix ("field 8812", "input 3f9a2c"): hex-ish and holding a digit, so "feed" or "bed" stay. */
private val GENERATED4 = Regex("""\s*(?=[0-9a-f]*[0-9])[0-9a-f]{4,}$""")
private val GENERATED6 = Regex("""\s*(?=[0-9a-f]*[0-9])[0-9a-f]{6,}$""")

/** A field's identity on a page, as far as the page names it. Never a value. */
data class AcFieldSig(val name: String = "", val id: String = "", val autocomplete: String = "", val label: String = "") {
    /** Stable key of the field: the first of name, id, autocomplete, label that is set (normalised); blank = unidentifiable. */
    fun key(): String {
        val pick = listOf(name, id, autocomplete.takeIf { it != "on" && it != "off" }.orEmpty(), label).firstOrNull { it.isNotBlank() }.orEmpty()
        // a generated suffix ("field_8812", "input-3f9a") must not make today's field a stranger tomorrow
        return AcFieldClassifier.norm(pick).replace(GENERATED4, "").replace(Regex("\\s+"), " ").trim()
    }
}

/** One remembered value: typed on [origin] into field [fieldKey] of the form [formSig]. */
data class AcMemoryEntry(
    val id: String = AcIds.next(),
    /** The site (see [AcSiteMemory.originKey]). */
    val origin: String,
    val formSig: String,
    val fieldKey: String,
    /** What the field was called on the page, for the manage list. */
    val label: String = "",
    val value: String,
    val lastUsed: Long = 0,
    val uses: Int = 1,
) {
    override fun toString() = "AcMemoryEntry($origin, $fieldKey, $REDACTED)"
}

/**
 * #900 PER-SITE MEMORY: values the user typed on one site that are NOT generic personal data (a customer number,
 * a member ID), kept by ORIGIN + FORM signature + FIELD key, offered back on that site only. Pure: JVM-tested.
 */
object AcSiteMemory {
    const val MAX_PER_FIELD = 5
    const val MAX_VALUE = 80

    /** The site key of a url or host: lower-case host, no `www.`, no port. Blank when it has no host. */
    fun originKey(urlOrHost: String): String {
        val raw = urlOrHost.trim().lowercase()
        // "about:blank", "mailto:x": a scheme and no host. A port ("localhost:8000") is not a scheme.
        if ("://" !in raw && Regex("^[a-z][a-z0-9+.\\-]*:(?!\\d+(/|$))").containsMatchIn(raw)) return ""
        val host = (if ("://" in raw) runCatching { java.net.URI(raw).host }.getOrNull() else raw.substringBefore('/').substringBefore(':')).orEmpty()
        return host.removePrefix("www.").trimEnd('.')
    }

    /** A form's signature: its id/name and the keys of its identifiable fields (not their order), hashed. */
    fun formSignature(formId: String, fieldKeys: Collection<String>): String {
        val base = AcFieldClassifier.norm(formId).replace(GENERATED6, "") + "|" + fieldKeys.filter { it.isNotBlank() }.sorted().joinToString(",")
        return MessageDigest.getInstance("SHA-256").digest(base.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
    }

    /**
     * May this typed [value] be offered for remembering? Not a secret, not personal data a profile already
     * models, not a search / captcha / long text, and the field must be identifiable.
     */
    fun shouldRemember(sig: AcFieldSig, cls: AcClass, value: String, type: String = "text"): Boolean {
        if (cls.kind != AcKind.SITE) return false
        if (type.lowercase() in setOf("password", "hidden", "search", "file", "checkbox", "radio", "textarea", "email", "tel")) return false
        val v = value.trim()
        if (v.isEmpty() || v.length > MAX_VALUE || v.contains('\n')) return false
        if (sig.key().isEmpty()) return false
        // a long digit run is a card or an IBAN, whatever its box is called
        if (Regex("^[0-9 \\-]{13,19}$").matches(v) && v.count { it.isDigit() } >= 13) return false
        return true
    }

    /** [list] with [e] added or its use counted; at most [MAX_PER_FIELD] values per origin+form+field (the least used goes first). */
    fun upsert(list: List<AcMemoryEntry>, e: AcMemoryEntry, now: Long): List<AcMemoryEntry> {
        val same = { x: AcMemoryEntry -> x.origin == e.origin && x.formSig == e.formSig && x.fieldKey == e.fieldKey }
        val hit = list.firstOrNull { same(it) && it.value == e.value }
        val updated = if (hit != null) list.map { if (it.id == hit.id) it.copy(uses = it.uses + 1, lastUsed = now, label = e.label.ifBlank { it.label }) else it }
            else list + e.copy(lastUsed = now)
        val ofField = updated.filter(same)
        if (ofField.size <= MAX_PER_FIELD) return updated
        val drop = ofField.sortedWith(compareBy({ it.uses }, { it.lastUsed })).take(ofField.size - MAX_PER_FIELD).map { it.id }.toSet()
        return updated.filterNot { it.id in drop }
    }

    /**
     * What to offer on [origin] for a field: the entries of this very form first, then the same field key of another
     * form of the SAME site; most used and most recent first. Another site's entries are never returned.
     */
    fun suggestions(list: List<AcMemoryEntry>, origin: String, formSig: String, fieldKey: String): List<AcMemoryEntry> {
        if (origin.isBlank() || fieldKey.isBlank()) return emptyList()
        val site = list.filter { it.origin == origin && it.fieldKey == fieldKey }
        val byRank = compareByDescending<AcMemoryEntry> { it.uses }.thenByDescending { it.lastUsed }
        return (site.filter { it.formSig == formSig }.sortedWith(byRank) + site.filter { it.formSig != formSig }.sortedWith(byRank))
            .distinctBy { it.value }
    }

    /** Everything remembered for one site, for the manage list. */
    fun forSite(list: List<AcMemoryEntry>, origin: String): List<AcMemoryEntry> =
        list.filter { it.origin == origin }.sortedWith(compareBy({ it.fieldKey }, { -it.uses }))

    fun sites(list: List<AcMemoryEntry>): Map<String, Int> = list.groupingBy { it.origin }.eachCount().toSortedMap()

    fun toJson(list: List<AcMemoryEntry>): JSONArray = JSONArray().also { a -> list.forEach {
        a.put(JSONObject().put("id", it.id).put("origin", it.origin).put("form", it.formSig).put("field", it.fieldKey).put("label", it.label)
            .put("value", it.value).put("last", it.lastUsed).put("uses", it.uses)) } }

    fun fromJson(a: JSONArray?): List<AcMemoryEntry> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull {
        val origin = it.optString("origin"); val value = it.optString("value")
        if (origin.isBlank() || value.isBlank()) null else AcMemoryEntry(it.optString("id").ifBlank { AcIds.next() }, origin, it.optString("form"),
            it.optString("field"), it.optString("label"), value, it.optLong("last"), it.optInt("uses", 1).coerceAtLeast(1))
    }
}
