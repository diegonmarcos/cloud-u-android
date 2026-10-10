package com.diegonmarcos.superapp.autofill

import android.net.Uri

/**
 * The non-secret autofill Source of Truth, as every tier addresses it
 * (a0_docs/eng-specs/autofill-3-tier.md).
 *
 * OWNER    Cloud Account ([PKG]) holds the rows and serves them from [AUTHORITY].
 * READERS  Cloud Browser (DOM autofill of contact/address forms) and Cloud Keyboard
 *          (IME candidates) — they hold [PERMISSION_READ].
 * WRITERS  Cloud Account's own editor and import; Cloud Browser's user-confirmed "save this
 *          address" ([PERMISSION_WRITE], insert only). Nothing else writes.
 *
 * Both permissions are protectionLevel="signature": Android grants them at install time to an
 * APK signed with the constellation key (SIGNING.md: every fleet app shares it) and to nothing
 * else. The provider re-checks them on every call as well.
 *
 * THE MODEL. A profile is a person as one country's forms want them ("Spain-A", "Germany-A",
 * "Brazil"): name parts (two family names for the Spanish form), display name, birth date,
 * nationality. It owns typed addresses (home, postal, c/o, post-office branch) and contacts
 * (phones, emails, links). Snippets are labelled free-text blocks. Rules map site forms.
 *
 * NEVER in here: passwords, passkeys, one-time codes, cards, and IDENTITY DOCUMENTS (national
 * ID, passport, residence permit: DNI/NIE, Personalausweis, RG/CPF…). Those are Cloud Vault's
 * (Bitwarden Login/Card/Identity items, Tier 1, the Android Autofill framework); every reader
 * refuses to put SOT data into such a field whatever a rule says.
 */
object AutofillSot {
    const val PKG = "com.diegonmarcos.cloudaccount"
    const val AUTHORITY = "$PKG.autofill"
    const val PERMISSION_READ = "com.diegonmarcos.cloud.permission.AUTOFILL_PROFILE_READ"
    const val PERMISSION_WRITE = "com.diegonmarcos.cloud.permission.AUTOFILL_PROFILE_WRITE"

    /** Contract version a reader can check with [METHOD_VERSION]; bump on a breaking column change. */
    const val VERSION = 2
    const val METHOD_VERSION = "version"

    const val PATH_PROFILES = "profiles"
    const val PATH_ADDRESSES = "addresses"
    const val PATH_CONTACTS = "contacts"
    const val PATH_RULES = "rules"
    const val PATH_SNIPPETS = "snippets"
    val PATHS = listOf(PATH_PROFILES, PATH_ADDRESSES, PATH_CONTACTS, PATH_RULES, PATH_SNIPPETS)

    fun uri(path: String): Uri = Uri.parse("content://$AUTHORITY/$path")
    fun uri(path: String, id: Long): Uri = Uri.parse("content://$AUTHORITY/$path/$id")

    // ── columns ──────────────────────────────────────────────────────────
    const val ID = "_id"
    const val UPDATED_AT = "updated_at"
    const val LABEL = "label"
    const val IS_DEFAULT = "is_default"   // 0 | 1
    const val PROFILE_ID = "profile_id"   // addresses, contacts → profiles._id
    const val TYPE = "type"

    /** profiles: the person. */
    val PROFILE_FIELDS = listOf("honorific_prefix", "given_name", "additional_name", "family_name", "family_name2",
        "display_name", "bday", "nationality", "organization", "organization_title")
    /** addresses: one place of a profile; [TYPE] is one of [AddressType]. */
    val ADDRESS_FIELDS = listOf("co_line", "street", "house_number", "floor_door", "complement", "neighborhood",
        "postal_code", "city", "state", "country")
    /** contacts: one phone / email / link of a profile. */
    const val C_KIND = "kind"        // tel | email | url
    const val C_VALUE = "value"
    const val C_COUNTRY = "country"  // ISO 3166-1 alpha-2 of a phone, optional

    /** rules: domain → form selector → field selector → field key (or "ignore") / literal value. */
    const val R_DOMAIN = "domain"
    const val R_FORM = "form_selector"
    const val R_FIELD = "field_selector"
    const val R_KEY = "field_key"
    const val R_VALUE = "literal_value"
    const val R_ENABLED = "enabled"

    /** snippets: a labelled free-text block (multi-line, any Unicode). */
    const val S_TEXT = "text"

    val PROFILE_COLUMNS = listOf(ID, LABEL, IS_DEFAULT) + PROFILE_FIELDS + UPDATED_AT
    val ADDRESS_COLUMNS = listOf(ID, PROFILE_ID, TYPE, LABEL, IS_DEFAULT) + ADDRESS_FIELDS + UPDATED_AT
    val CONTACT_COLUMNS = listOf(ID, PROFILE_ID, C_KIND, C_VALUE, TYPE, C_COUNTRY, IS_DEFAULT, UPDATED_AT)
    val RULE_COLUMNS = listOf(ID, R_DOMAIN, R_FORM, R_FIELD, R_KEY, R_VALUE, R_ENABLED, UPDATED_AT)
    val SNIPPET_COLUMNS = listOf(ID, LABEL, S_TEXT, UPDATED_AT)

    fun columns(path: String): List<String> = when (path) {
        PATH_PROFILES -> PROFILE_COLUMNS
        PATH_ADDRESSES -> ADDRESS_COLUMNS
        PATH_CONTACTS -> CONTACT_COLUMNS
        PATH_RULES -> RULE_COLUMNS
        PATH_SNIPPETS -> SNIPPET_COLUMNS
        else -> emptyList()
    }

    /** Tables whose rows belong to a profile (deleted with it). */
    val CHILD_PATHS = listOf(PATH_ADDRESSES, PATH_CONTACTS)
}

object AddressType {
    const val HOME = "home"            // residence (ES "Domicilio")
    const val POSTAL = "postal"        // mailing (DE "Postanschrift")
    const val CO = "co"                // c/o address
    const val POST_OFFICE = "post_office" // post-office branch / Packstation (DE "Postfiliale")
    const val OTHER = "other"
    val ALL = listOf(HOME, POSTAL, CO, POST_OFFICE, OTHER)
    fun label(t: String) = when (t) { HOME -> "Home"; POSTAL -> "Postal"; CO -> "c/o"; POST_OFFICE -> "Post office"; else -> "Other" }
}

object ContactKind {
    const val TEL = "tel"; const val EMAIL = "email"; const val URL = "url"
    val ALL = listOf(TEL, EMAIL, URL)
    val TYPES = listOf("mobile", "landline", "work", "personal", "other")
}

/**
 * Field keys. The WHATWG autocomplete tokens map 1:1 (a page's `autocomplete=` is a key), plus
 * `x-` keys for what the token set has no word for but ES/DE/BR forms ask separately: the two
 * Spanish surnames, street and house number apart, floor/door, the c/o line, nationality.
 * `x-snippet` is a free-text field that takes a snippet (on explicit request only).
 */
object Fields {
    val PERSON = listOf("honorific-prefix", "given-name", "additional-name", "family-name", "x-family-name-1", "x-family-name-2",
        "name", "bday", "x-nationality", "organization", "organization-title")
    val CONTACT = listOf("email", "tel", "tel-national", "url")
    val ADDRESS = listOf("street-address", "address-line1", "address-line2", "x-street", "x-house-number", "x-floor-door",
        "x-complement", "x-co", "address-level3", "address-level2", "address-level1", "postal-code", "country", "country-name")
    val ALL = PERSON + CONTACT + ADDRESS
    const val IGNORE = "ignore"
    /** A rule with this key picks the DEFAULT PROFILE for a site/TLD: its literal_value is the profile's label. */
    const val PROFILE = "x-profile"
    const val SNIPPET = "x-snippet"

    /**
     * Tokens no SOT value may EVER be written into — Cloud Vault's territory. A field that
     * carries one of these (or type=password) is refused by every tier, whatever a rule says.
     */
    fun isSecretToken(token: String): Boolean {
        val t = token.lowercase().trim()
        return t == "current-password" || t == "new-password" || t == "one-time-code" ||
            t.startsWith("cc-") || t == "webauthn" || t == "username" || t.startsWith("x-id")
    }
}

data class AutofillAddress(
    val id: Long = 0,
    val profileId: Long = 0,
    val type: String = AddressType.HOME,
    val label: String = "",
    val isDefault: Boolean = false,
    /** [AutofillSot.ADDRESS_FIELDS] column → value. */
    val fields: Map<String, String> = emptyMap(),
    val updatedAt: Long = 0,
) {
    operator fun get(col: String): String = fields[col].orEmpty().trim()
    val isEmpty get() = fields.values.all { it.isBlank() }
    val title get() = label.ifBlank { AddressType.label(type) }

    /** Same place: street + number + postal code + country, case/space-insensitive. */
    fun samePlace(o: AutofillAddress): Boolean {
        fun k(a: AutofillAddress) = listOf("street", "house_number", "postal_code", "country").joinToString("|") {
            a[it].lowercase().replace(Regex("\\s+"), " ")
        }
        return !isEmpty && k(this) == k(o)
    }
}

data class AutofillContact(
    val id: Long = 0,
    val profileId: Long = 0,
    val kind: String = ContactKind.EMAIL,
    val value: String = "",
    val type: String = "",
    val country: String = "",
    val isDefault: Boolean = false,
    val updatedAt: Long = 0,
)

data class AutofillProfile(
    val id: Long = 0,
    val label: String = "",
    val isDefault: Boolean = false,
    /** [AutofillSot.PROFILE_FIELDS] column → value. */
    val fields: Map<String, String> = emptyMap(),
    val addresses: List<AutofillAddress> = emptyList(),
    val contacts: List<AutofillContact> = emptyList(),
    val updatedAt: Long = 0,
) {
    operator fun get(col: String): String = fields[col].orEmpty().trim()
    val isEmpty get() = fields.values.all { it.isBlank() } && addresses.all { it.isEmpty } && contacts.all { it.value.isBlank() }

    /** What a chip may show: the label, else the display name's first word, else "Profile". */
    val title get() = label.ifBlank { get("display_name").substringBefore(' ').ifBlank { get("given_name").ifBlank { "Profile" } } }

    fun contacts(kind: String): List<AutofillContact> =
        contacts.filter { it.kind == kind && it.value.isNotBlank() }.sortedWith(compareByDescending<AutofillContact> { it.isDefault }
            .thenBy { if (kind == ContactKind.TEL && it.type == "mobile") 0 else 1 })

    val familyName get() = listOf(get("family_name"), get("family_name2")).filter { it.isNotEmpty() }.joinToString(" ")
    val fullName get() = get("display_name").ifBlank {
        listOf(get("given_name"), get("additional_name"), familyName).filter { it.isNotEmpty() }.joinToString(" ")
    }
    val defaultAddress get() = addresses.filterNot { it.isEmpty }.sortedByDescending { it.isDefault }.firstOrNull()
}

/** What one fill uses: a profile and (for an address form) one of its addresses. */
data class FillTarget(val profile: AutofillProfile, val address: AutofillAddress?) {
    val title get() = profile.title + (address?.let { " · " + it.title } ?: "")

    /**
     * The value for any [Fields.ALL] key, blank when unknown. [coSeparate] = the form has its own c/o
     * field; otherwise a c/o line rides in front of address-line2.
     */
    fun value(key: String, coSeparate: Boolean = false): String {
        val p = profile; val a = address
        fun ad(c: String) = a?.get(c).orEmpty()
        return when (key) {
            "honorific-prefix" -> p["honorific_prefix"]
            "given-name" -> p["given_name"]
            "additional-name" -> p["additional_name"]
            "family-name" -> p.familyName
            "x-family-name-1" -> p["family_name"]
            "x-family-name-2" -> p["family_name2"]
            "name" -> p.fullName
            "bday" -> p["bday"]
            "x-nationality" -> p["nationality"]
            "organization" -> p["organization"]
            "organization-title" -> p["organization_title"]
            "email" -> p.contacts(ContactKind.EMAIL).firstOrNull()?.value.orEmpty()
            "tel", "tel-national" -> p.contacts(ContactKind.TEL).firstOrNull()?.value.orEmpty()
            "url" -> p.contacts(ContactKind.URL).firstOrNull()?.value.orEmpty()
            "x-street" -> ad("street")
            "x-house-number" -> ad("house_number")
            "x-floor-door" -> ad("floor_door")
            "x-complement" -> ad("complement")
            "x-co" -> ad("co_line").let { if (it.isEmpty()) "" else AddressFormat.coLine(it) }
            "address-line1" -> AddressFormat.line1(ad("country"), ad("street"), ad("house_number"))
            "address-line2" -> AddressFormat.line2(ad("floor_door"), ad("complement"), if (coSeparate) "" else ad("co_line"))
            "street-address" -> listOf(ad("co_line").let { if (it.isEmpty()) "" else AddressFormat.coLine(it) },
                AddressFormat.line1(ad("country"), ad("street"), ad("house_number")),
                AddressFormat.line2(ad("floor_door"), ad("complement"), "")).filter { it.isNotEmpty() }.joinToString("\n")
            "address-level3" -> ad("neighborhood")
            "address-level2" -> ad("city")
            "address-level1" -> ad("state")
            "postal-code" -> ad("postal_code")
            "country" -> ad("country")
            "country-name" -> AddressFormat.countryName(ad("country"))
            else -> ""
        }
    }
}

/** Address lines per country (ES/DE/BR and the usual number-first countries). Pure. */
object AddressFormat {
    private val NUMBER_FIRST = setOf("US", "GB", "UK", "FR", "CA", "AU", "IE", "NZ", "IN")
    private val COMMA = setOf("BR", "PT", "ES", "IT", "AR", "MX", "CL", "CO")
    private val NAMES = mapOf("ES" to "España", "DE" to "Deutschland", "BR" to "Brasil", "PT" to "Portugal", "FR" to "France",
        "IT" to "Italia", "AT" to "Österreich", "CH" to "Schweiz", "NL" to "Nederland", "GB" to "United Kingdom", "US" to "United States")

    fun line1(country: String, street: String, number: String): String {
        val c = country.trim().uppercase()
        if (number.isBlank()) return street.trim()
        if (street.isBlank()) return number.trim()
        return when (c) {
            in NUMBER_FIRST -> "${number.trim()} ${street.trim()}"
            in COMMA -> "${street.trim()}, ${number.trim()}"
            else -> "${street.trim()} ${number.trim()}"   // DE, AT, CH, NL, …: "Hauptstraße 5"
        }
    }

    fun line2(floorDoor: String, complement: String, co: String): String =
        listOf(if (co.isBlank()) "" else coLine(co), floorDoor.trim(), complement.trim()).filter { it.isNotEmpty() }.joinToString(", ")

    fun coLine(co: String): String = co.trim().let { if (Regex("^(c/o|c\\.o\\.|z\\.\\s?hd\\.?)\\s", RegexOption.IGNORE_CASE).containsMatchIn(it)) it else "c/o $it" }

    fun countryName(code: String): String = NAMES[code.trim().uppercase()] ?: code.trim()
}

data class SiteRule(
    val id: Long = 0,
    /** Host the rule applies to: an exact host, a parent domain covering its subdomains, or a TLD ("de"). */
    val domain: String = "",
    /** CSS selector of the form; blank = any form (or a field outside a form). */
    val formSelector: String = "",
    /** CSS selector of the field inside that form. */
    val fieldSelector: String = "",
    /** A [Fields.ALL] key, [Fields.IGNORE] to never fill that field, or [Fields.PROFILE] (default profile). */
    val fieldKey: String = "",
    /** A non-secret constant to fill instead of a profile value; for [Fields.PROFILE] the profile's label. */
    val literalValue: String = "",
    val enabled: Boolean = true,
    val updatedAt: Long = 0,
) {
    val isProfileRule get() = fieldKey == Fields.PROFILE
    val valid get() = domain.isNotBlank() && when {
        isProfileRule -> literalValue.isNotBlank()
        else -> fieldSelector.isNotBlank() && (fieldKey in Fields.ALL || fieldKey == Fields.IGNORE || literalValue.isNotBlank())
    }

    /** True for [host] == domain or any subdomain of it ("de" covers every *.de host). */
    fun appliesTo(host: String): Boolean {
        val h = host.lowercase().trimEnd('.'); val d = domain.lowercase().trim().trimStart('.').removePrefix("www.")
        if (d.isEmpty()) return false
        val hh = h.removePrefix("www.")
        return hh == d || hh.endsWith(".$d")
    }
}

data class Snippet(val id: Long = 0, val label: String = "", val text: String = "", val updatedAt: Long = 0)

/** Pure helpers every reader shares. */
object AutofillCandidates {
    /** The rules for [host], enabled and valid, most specific domain first. */
    fun rulesFor(host: String, rules: List<SiteRule>): List<SiteRule> =
        rules.filter { it.enabled && it.valid && it.appliesTo(host) }.sortedByDescending { it.domain.length }

    /**
     * The profile to offer first on [host]: an x-profile rule for the site or its TLD, else a profile with an
     * address in the TLD's country (.de → an address in DE), else the default profile, else the first.
     */
    fun defaultProfileFor(host: String, rules: List<SiteRule>, profiles: List<AutofillProfile>): AutofillProfile? {
        if (profiles.isEmpty()) return null
        rulesFor(host, rules).firstOrNull { it.isProfileRule }?.let { r ->
            profiles.firstOrNull { it.label.equals(r.literalValue.trim(), ignoreCase = true) }?.let { return it }
        }
        val tld = host.lowercase().substringAfterLast('.', "").uppercase()
        if (tld.length == 2) profiles.firstOrNull { p -> p.addresses.any { it["country"].equals(tld, true) } }?.let { return it }
        return profiles.firstOrNull { it.isDefault } ?: profiles.first()
    }

    /** Profiles in offer order for [host]: its default first, then the rest as stored. */
    fun ordered(host: String, rules: List<SiteRule>, profiles: List<AutofillProfile>): List<AutofillProfile> {
        val first = defaultProfileFor(host, rules, profiles) ?: return emptyList()
        return listOf(first) + profiles.filter { it != first }
    }

    /**
     * Fill targets for a form needing [keys], in the order to offer: each profile with something for
     * those keys; for an address form, one target per address of that profile (default address first).
     */
    fun targets(keys: Collection<String>, profiles: List<AutofillProfile>): List<FillTarget> {
        val addressForm = keys.any { it in Fields.ADDRESS }
        return profiles.flatMap { p ->
            val addrs = p.addresses.filterNot { it.isEmpty }.sortedByDescending { it.isDefault }
            val ts = if (addressForm && addrs.isNotEmpty()) addrs.map { FillTarget(p, it) } else listOf(FillTarget(p, p.defaultAddress))
            ts.filter { t -> keys.any { t.value(it).isNotEmpty() } }
        }
    }

    /**
     * Distinct values for [key] across [profiles] in their order: every email / phone / link of a profile
     * for a contact key, every address's value for an address key. The keyboard's row 2 uses it.
     */
    fun forKey(key: String, profiles: List<AutofillProfile>): List<String> = profiles.flatMap { p ->
        when (key) {
            "email" -> p.contacts(ContactKind.EMAIL).map { it.value }
            "tel", "tel-national" -> p.contacts(ContactKind.TEL).map { it.value }
            "url" -> p.contacts(ContactKind.URL).map { it.value }
            in Fields.ADDRESS -> p.addresses.filterNot { it.isEmpty }.sortedByDescending { it.isDefault }.map { FillTarget(p, it).value(key) }
            else -> listOf(FillTarget(p, null).value(key))
        }
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
