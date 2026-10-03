package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject

/** Who fills a form. Every field may be empty. */
data class ProfileIdentity(
    val first: String = "", val middle: String = "", val last: String = "", val full: String = "",
    val email: String = "", val phone: String = "", val company: String = "", val website: String = "",
) {
    val present get() = listOf(first, last, full, email, phone).any { it.isNotBlank() }
    val displayName get() = full.ifBlank { listOf(first, middle, last).filter { it.isNotBlank() }.joinToString(" ") }
}

data class ProfileAddress(
    val label: String = "", val first: String = "", val last: String = "", val full: String = "",
    val email: String = "", val phone: String = "", val street: String = "", val number: String = "",
    val apartment: String = "", val city: String = "", val state: String = "", val zip: String = "", val country: String = "",
) {
    /** The street line as a form wants it: "Main St 5". */
    val line1 get() = listOf(street, number).filter { it.isNotBlank() }.joinToString(" ")
    internal val dedupeKey get() = listOf(street, number, zip, country).joinToString("|") { it.trim().lowercase() }
}

/** A card as the browser may know it: never the number, never the code. */
data class CardMeta(val label: String = "", val holder: String = "", val last4: String = "",
                    val expMonth: String = "", val expYear: String = "", val type: String = "")

/**
 * #802 THE BROWSER'S AUTOFILL PROFILE, pure. It arrives two ways: from the fleet Account
 * (the vault bundle's `about.profile` + `about.addresses`, imported by FleetConfig into the
 * browser_autofill store as raw JSON — [fromVaultBundle] maps it), and from a file the user
 * imports ([parse]: CSV with a header, Firefox autofill-profiles.json, a Bitwarden export,
 * or this class's own JSON).
 *
 * CARD NUMBERS AND SECURITY CODES NEVER ENTER: every importer keeps the last four digits and
 * drops the rest AT PARSE TIME. Filling a card or a password is Cloud Vault's job, through
 * Android's Autofill Framework. Nothing here is ever logged or answered unmasked: the debug
 * API serves [masked] only.
 */
data class BrowserProfile(
    val identity: ProfileIdentity = ProfileIdentity(),
    val addresses: List<ProfileAddress> = emptyList(),
    val cards: List<CardMeta> = emptyList(),
) {
    val isEmpty get() = !identity.present && addresses.isEmpty() && cards.isEmpty()

    /** [other] on top: its identity wins when present; addresses and cards are unions. */
    fun merge(other: BrowserProfile) = BrowserProfile(
        identity = if (other.identity.present) other.identity else identity,
        addresses = (addresses + other.addresses).distinctBy { it.dedupeKey },
        cards = (cards + other.cards).distinctBy { listOf(it.last4, it.expMonth, it.expYear) },
    )

    /** Presence and counts only: initials, never a name; last4, never a number. */
    fun masked(): JSONObject = JSONObject()
        .put("identity", JSONObject().put("present", identity.present)
            .put("initials", identity.displayName.split(' ').filter { it.isNotBlank() }.joinToString("") { it.take(1).uppercase() }))
        .put("addresses", addresses.size)
        .put("address_labels", JSONArray(addresses.map { it.label.ifBlank { it.city.take(1) + "…" } }))
        .put("cards_meta", cards.size)
        .put("card_last4", JSONArray(cards.map { it.last4 }))

    fun toJson(): JSONObject = JSONObject()
        .put("identity", identityJson(identity))
        .put("addresses", JSONArray(addresses.map { addressJson(it) }))
        .put("cards_meta", JSONArray(cards.map { cardJson(it) }))

    companion object {
        private fun JSONObject.s(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> optString(k).trim().takeIf { has(k) && !isNull(k) && it.isNotEmpty() } }.orEmpty()

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        fun identityJson(i: ProfileIdentity) = JSONObject().put("first", i.first).put("middle", i.middle).put("last", i.last)
            .put("full", i.full).put("email", i.email).put("phone", i.phone).put("company", i.company).put("website", i.website)

        fun addressJson(a: ProfileAddress) = JSONObject().put("label", a.label).put("first", a.first).put("last", a.last)
            .put("full", a.full).put("email", a.email).put("phone", a.phone).put("street", a.street).put("number", a.number)
            .put("apartment", a.apartment).put("city", a.city).put("state", a.state).put("zip", a.zip).put("country", a.country)

        fun cardJson(c: CardMeta) = JSONObject().put("label", c.label).put("holder", c.holder).put("last4", c.last4)
            .put("exp_month", c.expMonth).put("exp_year", c.expYear).put("type", c.type)

        /** The last four digits of [number]; everything else is dropped here. */
        fun last4(number: String) = number.filter { it.isDigit() }.takeLast(4)

        /** This class's own JSON ([toJson]). */
        fun fromNative(o: JSONObject): BrowserProfile = BrowserProfile(
            identity = o.optJSONObject("identity")?.let {
                ProfileIdentity(it.s("first"), it.s("middle"), it.s("last"), it.s("full"), it.s("email"), it.s("phone"), it.s("company"), it.s("website"))
            } ?: ProfileIdentity(),
            addresses = o.optJSONArray("addresses").objects().map {
                ProfileAddress(it.s("label"), it.s("first"), it.s("last"), it.s("full"), it.s("email"), it.s("phone"),
                    it.s("street"), it.s("number"), it.s("apartment"), it.s("city"), it.s("state"), it.s("zip"), it.s("country"))
            },
            cards = o.optJSONArray("cards_meta").objects().map {
                CardMeta(it.s("label"), it.s("holder"), last4(it.s("last4", "card_number", "number")), it.s("exp_month"), it.s("exp_year"), it.s("type"))
            },
        )

        /**
         * The vault bundle's `about.profile` {name, email, location, website, titles} and
         * `about.addresses[]` {first_name, middle_name, last_name, full_name, email, phone,
         * street_name, street_number, apartment, city, state, zip, country, label}.
         * The identity's first/last come from the first address when the profile has only `name`.
         */
        fun fromVaultBundle(profile: JSONObject?, addresses: JSONArray?): BrowserProfile {
            val addr = addresses.objects().map {
                ProfileAddress(it.s("label"), it.s("first_name"), it.s("last_name"), it.s("full_name"), it.s("email"), it.s("phone"),
                    it.s("street_name"), it.s("street_number"), it.s("apartment"), it.s("city"), it.s("state"), it.s("zip"), it.s("country"))
            }
            val a0 = addresses.objects().firstOrNull()
            val p = profile ?: JSONObject()
            val id = ProfileIdentity(
                first = a0?.s("first_name").orEmpty(), middle = a0?.s("middle_name").orEmpty(), last = a0?.s("last_name").orEmpty(),
                full = p.s("name").ifBlank { a0?.s("full_name").orEmpty() },
                email = p.s("email").ifBlank { a0?.s("email").orEmpty() }, phone = a0?.s("phone").orEmpty(),
                company = p.s("company"), website = p.s("website"),
            )
            return BrowserProfile(id, addr)
        }

        /** A CSV with a header row; columns are matched by name (Chrome/Google/generic exports). */
        fun fromCsv(csv: String): BrowserProfile {
            val rows = csvRows(csv)
            if (rows.size < 2) return BrowserProfile()
            val h = rows[0].map { it.trim().lowercase().replace(' ', '_').replace('-', '_') }
            fun col(r: List<String>, vararg names: String) =
                names.firstNotNullOfOrNull { n -> h.indexOf(n).takeIf { it >= 0 }?.let { r.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() } }.orEmpty()
            val out = rows.drop(1).filter { r -> r.any { it.isNotBlank() } }.map { r ->
                ProfileAddress(
                    label = col(r, "label", "company", "organization"),
                    first = col(r, "first_name", "given_name", "first"), last = col(r, "last_name", "family_name", "last"),
                    full = col(r, "full_name", "name"), email = col(r, "email", "email_address"),
                    phone = col(r, "phone", "phone_number", "tel"),
                    street = col(r, "street_name", "street_address", "address_line_1", "address", "street"),
                    number = col(r, "street_number", "house_number"), apartment = col(r, "apartment", "address_line_2"),
                    city = col(r, "city", "locality", "address_level2"), state = col(r, "state", "region", "address_level1"),
                    zip = col(r, "zip", "postal_code", "zip_code"), country = col(r, "country", "country_code"),
                )
            }
            return BrowserProfile(addresses = out)
        }

        /** Firefox's autofill-profiles.json: `addresses[]` and `creditCards[]` (metadata only). */
        fun fromFirefoxJson(o: JSONObject) = BrowserProfile(
            addresses = o.optJSONArray("addresses").objects().map {
                ProfileAddress(label = it.s("organization"), first = it.s("given-name"), last = it.s("family-name"), full = it.s("name"),
                    email = it.s("email"), phone = it.s("tel"), street = it.s("street-address"), city = it.s("address-level2"),
                    state = it.s("address-level1"), zip = it.s("postal-code"), country = it.s("country"))
            },
            cards = o.optJSONArray("creditCards").objects().map {
                CardMeta(label = it.s("cc-name"), holder = it.s("cc-name"), last4 = last4(it.s("cc-number")),
                    expMonth = it.s("cc-exp-month"), expYear = it.s("cc-exp-year"), type = it.s("cc-type"))
            },
        )

        /** A Bitwarden JSON export: identities (type 4) → identity + address; cards (type 3) → metadata. */
        fun fromBitwardenJson(o: JSONObject): BrowserProfile {
            val items = o.optJSONArray("items").objects()
            val ids = items.filter { it.optInt("type") == 4 }.mapNotNull { it.optJSONObject("identity")?.let { i -> it.optString("name") to i } }
            val first = ids.firstOrNull()?.second
            return BrowserProfile(
                identity = first?.let {
                    ProfileIdentity(it.s("firstName"), it.s("middleName"), it.s("lastName"), "", it.s("email"), it.s("phone"), it.s("company"))
                } ?: ProfileIdentity(),
                addresses = ids.map { (name, i) ->
                    ProfileAddress(label = name, first = i.s("firstName"), last = i.s("lastName"), email = i.s("email"), phone = i.s("phone"),
                        street = i.s("address1"), apartment = i.s("address2"), city = i.s("city"), state = i.s("state"),
                        zip = i.s("postalCode"), country = i.s("country"))
                },
                cards = items.filter { it.optInt("type") == 3 }.mapNotNull { it.optJSONObject("card")?.let { c -> it.optString("name") to c } }.map { (name, c) ->
                    CardMeta(name, c.s("cardholderName"), last4(c.s("number")), c.s("expMonth"), c.s("expYear"), c.s("brand"))
                },
            )
        }

        /** [body] in [format] (csv | firefox | bitwarden | native | vault), or sniffed when blank. */
        fun parse(format: String?, body: String): BrowserProfile {
            val t = body.trim()
            val f = format?.lowercase()?.ifBlank { null } ?: when {
                !t.startsWith("{") -> "csv"
                t.contains("\"creditCards\"") || t.contains("\"given-name\"") -> "firefox"
                t.contains("\"items\"") -> "bitwarden"
                t.contains("\"about\"") -> "vault"
                else -> "native"
            }
            return when (f) {
                "csv", "chrome" -> fromCsv(t)
                "firefox" -> fromFirefoxJson(JSONObject(t))
                "bitwarden" -> fromBitwardenJson(JSONObject(t))
                "vault" -> JSONObject(t).optJSONObject("about").let { fromVaultBundle(it?.optJSONObject("profile"), it?.optJSONArray("addresses")) }
                "native" -> fromNative(JSONObject(t))
                else -> throw IllegalArgumentException("format must be csv, firefox, bitwarden, vault or native")
            }
        }

        /** RFC 4180-ish: quoted fields, doubled quotes, newlines inside quotes. */
        fun csvRows(s: String): List<List<String>> {
            val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val f = StringBuilder()
            var q = false; var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    q && c == '"' && s.getOrNull(i + 1) == '"' -> { f.append('"'); i++ }
                    c == '"' -> q = !q
                    !q && c == ',' -> { row.add(f.toString()); f.setLength(0) }
                    !q && (c == '\n' || c == '\r') -> {
                        if (c == '\r' && s.getOrNull(i + 1) == '\n') i++
                        row.add(f.toString()); f.setLength(0); rows.add(row); row = ArrayList()
                    }
                    else -> f.append(c)
                }
                i++
            }
            if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
            return rows
        }
    }
}

/**
 * #802 which profile value a form field gets, pure. A field is described by what the page
 * says about it (autocomplete, type, name, id, label, placeholder); passwords, card numbers
 * and security codes are REFUSED here, whatever else the field claims to be.
 */
object BrowserAutofillMatch {
    private val refuse = Regex("pass|pwd|cc-|card|cvv|cvc|csc|iban|ssn|secur|otp|one-time|pin\\b", RegexOption.IGNORE_CASE)

    /** autocomplete token → kind; name/label words → kind, in priority order. */
    private val byAutocomplete = mapOf(
        "given-name" to "first", "additional-name" to "middle", "family-name" to "last", "name" to "full",
        "email" to "email", "tel" to "phone", "tel-national" to "phone", "organization" to "company", "url" to "website",
        "street-address" to "line1", "address-line1" to "line1", "address-line2" to "apartment",
        "address-level2" to "city", "address-level1" to "state", "postal-code" to "zip", "country" to "country", "country-name" to "country",
    )
    private val byWords = listOf(
        "first" to Regex("first.?name|given|vorname|prenom|nombre", RegexOption.IGNORE_CASE),
        "last" to Regex("last.?name|surname|family|nachname|apellido", RegexOption.IGNORE_CASE),
        "email" to Regex("e.?mail", RegexOption.IGNORE_CASE),
        "phone" to Regex("phone|tel|mobile|telefon", RegexOption.IGNORE_CASE),
        "zip" to Regex("zip|postal|postcode|plz", RegexOption.IGNORE_CASE),
        "city" to Regex("city|town|ort\\b|stadt|ciudad", RegexOption.IGNORE_CASE),
        "state" to Regex("state|province|region", RegexOption.IGNORE_CASE),
        "country" to Regex("country|land\\b|pais", RegexOption.IGNORE_CASE),
        "apartment" to Regex("apartment|apt|suite|address.?2|line.?2", RegexOption.IGNORE_CASE),
        "line1" to Regex("street|address|strasse|calle", RegexOption.IGNORE_CASE),
        "company" to Regex("company|organi[sz]ation|firma", RegexOption.IGNORE_CASE),
        "full" to Regex("full.?name|^name$|your.?name|\\bname\\b", RegexOption.IGNORE_CASE),
    )

    /** The kind of field, or null when it must not be filled (or nothing matched). */
    fun kind(f: JSONObject): String? {
        val type = f.optString("type").lowercase()
        if (type in setOf("password", "hidden", "submit", "button", "checkbox", "radio", "file")) return null
        val ac = f.optString("autocomplete").lowercase().split(' ').lastOrNull { it.isNotBlank() }.orEmpty()
        val words = listOf("name", "id", "label", "placeholder").joinToString(" ") { f.optString(it) }
        if (refuse.containsMatchIn(ac) || refuse.containsMatchIn(words)) return null
        byAutocomplete[ac]?.let { return it }
        if (type == "email") return "email"
        if (type == "tel") return "phone"
        return byWords.firstOrNull { it.second.containsMatchIn(words) }?.first
    }

    /** The value a [kind] gets from [p], using address [ai] for postal fields. */
    fun value(kind: String, p: BrowserProfile, ai: Int = 0): String {
        val i = p.identity; val a = p.addresses.getOrNull(ai) ?: ProfileAddress()
        return when (kind) {
            "first" -> i.first.ifBlank { a.first }
            "middle" -> i.middle
            "last" -> i.last.ifBlank { a.last }
            "full" -> i.displayName.ifBlank { a.full }
            "email" -> i.email.ifBlank { a.email }
            "phone" -> i.phone.ifBlank { a.phone }
            "company" -> i.company
            "website" -> i.website
            "line1" -> a.line1
            "apartment" -> a.apartment
            "city" -> a.city
            "state" -> a.state
            "zip" -> a.zip
            "country" -> a.country
            else -> ""
        }
    }

    /** field index → kind for every fillable field of a scan ([autofill_scan.js]'s array). */
    fun plan(fields: JSONArray): Map<Int, String> = (0 until fields.length()).mapNotNull { n ->
        val f = fields.optJSONObject(n) ?: return@mapNotNull null
        kind(f)?.let { f.optInt("i", n) to it }
    }.toMap()
}
