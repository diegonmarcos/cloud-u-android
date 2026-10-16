package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * #900 AUTOCOMPLETE, the personal-profile model. A profile is one person-as-a-form-sees-them ("Diego - ES",
 * "Diego - BR") and every kind of detail is MANY-VALUED: two names, three labelled addresses, two citizenships'
 * documents, several phones and emails. At fill time the user picks the profile, then the one value per kind when
 * a profile holds several ([AcFill]). Pure: org.json only, JVM-tested. Nothing here is logged: every class that
 * holds personal data prints [REDACTED] from toString().
 */
internal const val REDACTED = "‹redacted›"

object AcIds {
    fun next(): String = UUID.randomUUID().toString().replace("-", "").take(12)
}

/** The document kinds a profile can hold, as the editor offers them. */
object AcDocType {
    const val NATIONAL_ID = "national_id"
    const val PASSPORT = "passport"
    const val TAX_ID = "tax_id"
    const val RESIDENCE_PERMIT = "residence_permit"
    const val DRIVER_LICENCE = "driver_licence"
    const val OTHER = "other"
    val ALL = listOf(NATIONAL_ID to "National ID", PASSPORT to "Passport", TAX_ID to "Tax ID",
        RESIDENCE_PERMIT to "Residence permit", DRIVER_LICENCE to "Driving licence", OTHER to "Other")
    fun label(t: String) = ALL.firstOrNull { it.first == t }?.second ?: t
}

data class AcName(
    val id: String = AcIds.next(),
    val given: String = "", val middle: String = "", val family: String = "",
    /** The name as one string; blank = given + middle + family. */
    val full: String = "",
    val alias: String = "",
    val isDefault: Boolean = false,
) {
    fun fullName(): String = full.ifBlank { listOf(given, middle, family).filter { it.isNotBlank() }.joinToString(" ") }
    val isEmpty get() = given.isBlank() && middle.isBlank() && family.isBlank() && full.isBlank() && alias.isBlank()
    fun title(): String = fullName().ifBlank { alias }
    override fun toString() = "AcName($REDACTED)"
}

data class AcAddress(
    val id: String = AcIds.next(),
    /** home | work | billing | ... free label. */
    val label: String = "home",
    val street: String = "", val number: String = "", val line2: String = "",
    val city: String = "", val postal: String = "", val region: String = "",
    /** An ISO 3166 alpha-2 code or a country name, as the user typed it. */
    val country: String = "",
    val isDefault: Boolean = false,
) {
    val isEmpty get() = street.isBlank() && number.isBlank() && city.isBlank() && postal.isBlank() && line2.isBlank() && region.isBlank()
    fun title(): String = label.ifBlank { "address" } + (city.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "")
    override fun toString() = "AcAddress($REDACTED)"
}

data class AcPhone(val id: String = AcIds.next(), val label: String = "mobile", val value: String = "", val isDefault: Boolean = false) {
    override fun toString() = "AcPhone($REDACTED)"
}

data class AcEmail(val id: String = AcIds.next(), val label: String = "personal", val value: String = "", val isDefault: Boolean = false) {
    override fun toString() = "AcEmail($REDACTED)"
}

/** An ID document: [type] (see [AcDocType]), its number, who issued it, and the citizenship it proves. */
data class AcDocument(
    val id: String = AcIds.next(),
    val type: String = AcDocType.NATIONAL_ID,
    val number: String = "",
    val issuingCountry: String = "",
    val citizenship: String = "",
    /** yyyy-MM-dd or blank. */
    val expires: String = "",
    val isDefault: Boolean = false,
) {
    fun title(): String = AcDocType.label(type) + (issuingCountry.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "")
    override fun toString() = "AcDocument($REDACTED)"
}

/** A date of the person: [kind] birth (the one the forms ask), value yyyy-MM-dd. */
data class AcDate(val id: String = AcIds.next(), val kind: String = "birth", val value: String = "", val isDefault: Boolean = false) {
    override fun toString() = "AcDate($REDACTED)"
    fun part(which: Char): String {
        val p = value.split('-')
        return when (which) { 'y' -> p.getOrNull(0); 'm' -> p.getOrNull(1); 'd' -> p.getOrNull(2); else -> null }.orEmpty()
    }
}

data class AcProfile(
    val id: String = AcIds.next(),
    val label: String = "",
    val isDefault: Boolean = false,
    val names: List<AcName> = emptyList(),
    val addresses: List<AcAddress> = emptyList(),
    val phones: List<AcPhone> = emptyList(),
    val emails: List<AcEmail> = emptyList(),
    val documents: List<AcDocument> = emptyList(),
    val dates: List<AcDate> = emptyList(),
    /** Organisations / employers; the first is the one forms get. */
    val organisations: List<String> = emptyList(),
    /** local | account (imported from Cloud Account) | legacy (imported from the old browser profile). */
    val source: String = "local",
) {
    val isEmpty get() = names.all { it.isEmpty } && addresses.all { it.isEmpty } && phones.all { it.value.isBlank() } &&
        emails.all { it.value.isBlank() } && documents.all { it.number.isBlank() } && dates.all { it.value.isBlank() } && organisations.all { it.isBlank() }

    fun title(): String = label.ifBlank { names.firstOrNull()?.title().orEmpty().ifBlank { "Profile" } }

    /** Exactly one record per list is default: the marked one, else the first. */
    fun normalized(): AcProfile = copy(
        names = names.pickDefault({ it.isDefault }, { n, d -> n.copy(isDefault = d) }),
        addresses = addresses.pickDefault({ it.isDefault }, { a, d -> a.copy(isDefault = d) }),
        phones = phones.pickDefault({ it.isDefault }, { a, d -> a.copy(isDefault = d) }),
        emails = emails.pickDefault({ it.isDefault }, { a, d -> a.copy(isDefault = d) }),
        documents = documents.pickDefault({ it.isDefault }, { a, d -> a.copy(isDefault = d) }),
        dates = dates.pickDefault({ it.isDefault }, { a, d -> a.copy(isDefault = d) }),
    )

    override fun toString() = "AcProfile(${id}, ${names.size}n ${addresses.size}a ${phones.size}p ${emails.size}e ${documents.size}d)"
}

/** Mark exactly one item default (the first marked one, else the first). */
internal fun <T> List<T>.pickDefault(isDef: (T) -> Boolean, set: (T, Boolean) -> T): List<T> {
    if (isEmpty()) return this
    val at = indexOfFirst(isDef).takeIf { it >= 0 } ?: 0
    return mapIndexed { i, t -> set(t, i == at) }
}

/** The whole set of profiles: exactly one default, and JSON in/out. */
object AcProfiles {
    const val SCHEMA = 1

    /** Exactly one profile is the default (the marked one, else the first); each profile normalised. */
    fun normalize(list: List<AcProfile>): List<AcProfile> =
        list.map { it.normalized() }.pickDefault({ it.isDefault }, { p, d -> p.copy(isDefault = d) })

    fun default(list: List<AcProfile>): AcProfile? = list.firstOrNull { it.isDefault } ?: list.firstOrNull()

    fun toJson(list: List<AcProfile>): JSONArray = JSONArray().also { a -> list.forEach { a.put(toJson(it)) } }

    fun toJson(p: AcProfile): JSONObject = JSONObject()
        .put("id", p.id).put("label", p.label).put("default", p.isDefault).put("source", p.source)
        .put("names", JSONArray(p.names.map { JSONObject().put("id", it.id).put("given", it.given).put("middle", it.middle).put("family", it.family)
            .put("full", it.full).put("alias", it.alias).put("default", it.isDefault) }))
        .put("addresses", JSONArray(p.addresses.map { JSONObject().put("id", it.id).put("label", it.label).put("street", it.street).put("number", it.number)
            .put("line2", it.line2).put("city", it.city).put("postal", it.postal).put("region", it.region).put("country", it.country).put("default", it.isDefault) }))
        .put("phones", JSONArray(p.phones.map { JSONObject().put("id", it.id).put("label", it.label).put("value", it.value).put("default", it.isDefault) }))
        .put("emails", JSONArray(p.emails.map { JSONObject().put("id", it.id).put("label", it.label).put("value", it.value).put("default", it.isDefault) }))
        .put("documents", JSONArray(p.documents.map { JSONObject().put("id", it.id).put("type", it.type).put("number", it.number)
            .put("issuing_country", it.issuingCountry).put("citizenship", it.citizenship).put("expires", it.expires).put("default", it.isDefault) }))
        .put("dates", JSONArray(p.dates.map { JSONObject().put("id", it.id).put("kind", it.kind).put("value", it.value).put("default", it.isDefault) }))
        .put("organisations", JSONArray(p.organisations))

    fun fromJson(a: JSONArray?): List<AcProfile> = if (a == null) emptyList() else
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { fromJson(it) }

    fun fromJson(o: JSONObject): AcProfile {
        fun <T> list(key: String, f: (JSONObject) -> T): List<T> =
            o.optJSONArray(key).let { a -> if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map(f) }
        fun JSONObject.id() = optString("id").ifBlank { AcIds.next() }
        return AcProfile(
            id = o.optString("id").ifBlank { AcIds.next() }, label = o.optString("label"), isDefault = o.optBoolean("default"),
            source = o.optString("source", "local").ifBlank { "local" },
            names = list("names") { AcName(it.id(), it.optString("given"), it.optString("middle"), it.optString("family"), it.optString("full"), it.optString("alias"), it.optBoolean("default")) },
            addresses = list("addresses") { AcAddress(it.id(), it.optString("label", "home"), it.optString("street"), it.optString("number"), it.optString("line2"),
                it.optString("city"), it.optString("postal"), it.optString("region"), it.optString("country"), it.optBoolean("default")) },
            phones = list("phones") { AcPhone(it.id(), it.optString("label", "mobile"), it.optString("value"), it.optBoolean("default")) },
            emails = list("emails") { AcEmail(it.id(), it.optString("label", "personal"), it.optString("value"), it.optBoolean("default")) },
            documents = list("documents") { AcDocument(it.id(), it.optString("type", AcDocType.OTHER), it.optString("number"), it.optString("issuing_country"),
                it.optString("citizenship"), it.optString("expires"), it.optBoolean("default")) },
            dates = list("dates") { AcDate(it.id(), it.optString("kind", "birth"), it.optString("value"), it.optBoolean("default")) },
            organisations = o.optJSONArray("organisations").let { a -> if (a == null) emptyList() else (0 until a.length()).map { i -> a.optString(i) } },
        )
    }
}
