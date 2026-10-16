package com.diegonmarcos.superapp.browser

import com.diegonmarcos.superapp.autofill.AddressFormat

/**
 * #900 the HTML autocomplete tokens (WHATWG) the browser fills and the groups of one profile they draw from.
 * A GROUP is a kind of detail a profile holds several of (names, addresses, phones, emails, documents, dates):
 * the fill picks ONE record per group, so a form's street, postal code and city always come from the SAME address.
 */
object AcTokens {
    // profile data
    const val NAME = "name"
    const val GIVEN = "given-name"
    const val ADDITIONAL = "additional-name"
    const val FAMILY = "family-name"
    const val NICKNAME = "nickname"
    const val HONORIFIC = "honorific-prefix"
    const val EMAIL = "email"
    const val TEL = "tel"
    const val TEL_NATIONAL = "tel-national"
    const val ORGANIZATION = "organization"
    const val STREET_ADDRESS = "street-address"
    const val LINE1 = "address-line1"
    const val LINE2 = "address-line2"
    const val LINE3 = "address-line3"
    const val LEVEL1 = "address-level1"   // region / state
    const val LEVEL2 = "address-level2"   // city
    const val POSTAL = "postal-code"
    const val COUNTRY = "country"
    const val COUNTRY_NAME = "country-name"
    const val BDAY = "bday"
    const val BDAY_DAY = "bday-day"
    const val BDAY_MONTH = "bday-month"
    const val BDAY_YEAR = "bday-year"
    // split-street forms (the house number in its own box)
    const val X_STREET = "x-street"
    const val X_NUMBER = "x-house-number"
    // documents (a profile's ID documents, never the vault's secrets)
    const val X_NATIONAL_ID = "x-national-id"
    const val X_PASSPORT = "x-passport"
    const val X_TAX_ID = "x-tax-id"
    const val X_RESIDENCE = "x-residence-permit"
    const val X_LICENCE = "x-driver-licence"
    const val X_DOCUMENT = "x-document-number"
    const val X_ISSUING = "x-issuing-country"
    const val X_CITIZENSHIP = "x-citizenship"
    const val X_DOC_EXPIRY = "x-document-expiry"
    // secrets (never profile data)
    const val USERNAME = "username"
    const val CURRENT_PASSWORD = "current-password"
    const val NEW_PASSWORD = "new-password"
    const val OTP = "one-time-code"
    const val CC_NAME = "cc-name"
    const val CC_NUMBER = "cc-number"
    const val CC_EXP = "cc-exp"
    const val CC_EXP_MONTH = "cc-exp-month"
    const val CC_EXP_YEAR = "cc-exp-year"
    const val CC_CSC = "cc-csc"
    const val CC_TYPE = "cc-type"

    const val G_NAME = "name"
    const val G_ADDRESS = "address"
    const val G_PHONE = "phone"
    const val G_EMAIL = "email"
    const val G_DATE = "date"
    const val G_ORG = "organisation"
    const val G_DOC_ANY = "doc:any"
    fun docGroup(type: String) = "doc:$type"

    /** Canonical order the chooser lists the groups in. */
    private val GROUP_ORDER = listOf(G_NAME, G_ADDRESS, G_PHONE, G_EMAIL, G_DATE, G_ORG)

    private val DOC_TOKEN_TYPE = mapOf(
        X_NATIONAL_ID to AcDocType.NATIONAL_ID, X_PASSPORT to AcDocType.PASSPORT, X_TAX_ID to AcDocType.TAX_ID,
        X_RESIDENCE to AcDocType.RESIDENCE_PERMIT, X_LICENCE to AcDocType.DRIVER_LICENCE,
    )
    private val DOC_DEPENDENT = setOf(X_ISSUING, X_CITIZENSHIP, X_DOC_EXPIRY)

    val PROFILE: Set<String> = setOf(NAME, GIVEN, ADDITIONAL, FAMILY, NICKNAME, HONORIFIC, EMAIL, TEL, TEL_NATIONAL, ORGANIZATION,
        STREET_ADDRESS, LINE1, LINE2, LINE3, LEVEL1, LEVEL2, POSTAL, COUNTRY, COUNTRY_NAME, BDAY, BDAY_DAY, BDAY_MONTH, BDAY_YEAR,
        X_STREET, X_NUMBER, X_DOCUMENT, X_ISSUING, X_CITIZENSHIP, X_DOC_EXPIRY) + DOC_TOKEN_TYPE.keys

    val CARD: Set<String> = setOf(CC_NAME, CC_NUMBER, CC_EXP, CC_EXP_MONTH, CC_EXP_YEAR, CC_CSC, CC_TYPE)
    val LOGIN: Set<String> = setOf(USERNAME, CURRENT_PASSWORD, NEW_PASSWORD)

    fun isProfile(token: String?) = token != null && token in PROFILE
    fun isCard(token: String?) = token != null && token in CARD
    fun isSecret(token: String?) = token != null && (token in CARD || token == CURRENT_PASSWORD || token == NEW_PASSWORD || token == OTP)

    /** The group of one profile a token draws from, or null for a token that is not profile data. */
    fun groupOf(token: String): String? = when (token) {
        NAME, GIVEN, ADDITIONAL, FAMILY, NICKNAME, HONORIFIC -> G_NAME
        STREET_ADDRESS, LINE1, LINE2, LINE3, LEVEL1, LEVEL2, POSTAL, COUNTRY, COUNTRY_NAME, X_STREET, X_NUMBER -> G_ADDRESS
        TEL, TEL_NATIONAL -> G_PHONE
        EMAIL -> G_EMAIL
        BDAY, BDAY_DAY, BDAY_MONTH, BDAY_YEAR -> G_DATE
        ORGANIZATION -> G_ORG
        X_DOCUMENT -> G_DOC_ANY
        in DOC_TOKEN_TYPE -> docGroup(DOC_TOKEN_TYPE.getValue(token))
        in DOC_DEPENDENT -> G_DOC_ANY
        else -> null
    }

    /**
     * The groups a form needs, in chooser order. A document group is only ever asked for once per TYPE; the
     * dependent tokens (issuing country, citizenship, expiry) ride on the first document group of the form.
     */
    fun groupsOf(tokens: Collection<String>): List<String> {
        val groups = tokens.mapNotNull { groupOf(it) }.distinct()
        val simple = GROUP_ORDER.filter { it in groups }
        val docs = tokens.mapNotNull { groupOf(it) }.filter { it.startsWith("doc:") }.distinct().let { all ->
            // doc:any exists only when no typed document is on the form
            val typed = all.filter { it != G_DOC_ANY }
            if (typed.isNotEmpty()) typed else all
        }
        return simple + docs
    }
}

/** One record the user can choose for a group: shown as [label] + [preview], preselected when [isDefault]. */
data class AcCandidate(val recordId: String, val label: String, val preview: String, val isDefault: Boolean)

/** A group the form needs and the profile has more than one record for: the chooser asks. */
data class AcChoiceGroup(val group: String, val title: String, val options: List<AcCandidate>)

/** The user's (or the default) choice: which profile, and per group which record. */
data class AcChoice(val profileId: String, val records: Map<String, String>) {
    override fun toString() = "AcChoice($profileId, ${records.size})"
}

/** What the chooser must ask: the profile when several, and each group with several records. */
data class AcChoicePlan(val profiles: List<AcProfile>, val groups: Map<String, List<AcChoiceGroup>>) {
    /** Nothing to ask: one profile, one record per group. */
    fun isTrivial(): Boolean = profiles.size <= 1 && groups.values.all { it.isEmpty() }
}

/** (id, token) of one fillable field of a form, as the page engine reported it. */
data class AcField(val id: Int, val token: String)

object AcFill {
    private val GROUP_TITLES = mapOf(AcTokens.G_NAME to "Name", AcTokens.G_ADDRESS to "Address", AcTokens.G_PHONE to "Phone",
        AcTokens.G_EMAIL to "Email", AcTokens.G_DATE to "Date", AcTokens.G_ORG to "Organisation", AcTokens.G_DOC_ANY to "Document")

    fun groupTitle(group: String): String =
        if (group.startsWith("doc:") && group != AcTokens.G_DOC_ANY) AcDocType.label(group.removePrefix("doc:")) else GROUP_TITLES[group] ?: group

    /** The records of [group] that [p] holds, default first. Empty records are not offered. */
    fun candidates(p: AcProfile, group: String): List<AcCandidate> {
        val list: List<AcCandidate> = when {
            group == AcTokens.G_NAME -> p.names.filterNot { it.isEmpty }.map { AcCandidate(it.id, it.title(), it.fullName().ifBlank { it.alias }, it.isDefault) }
            group == AcTokens.G_ADDRESS -> p.addresses.filterNot { it.isEmpty }.map {
                AcCandidate(it.id, it.label.ifBlank { "address" }, listOf(line1(it), it.postal, it.city).filter { s -> s.isNotBlank() }.joinToString(", "), it.isDefault) }
            group == AcTokens.G_PHONE -> p.phones.filter { it.value.isNotBlank() }.map { AcCandidate(it.id, it.label, it.value, it.isDefault) }
            group == AcTokens.G_EMAIL -> p.emails.filter { it.value.isNotBlank() }.map { AcCandidate(it.id, it.label, it.value, it.isDefault) }
            group == AcTokens.G_DATE -> p.dates.filter { it.value.isNotBlank() }.map { AcCandidate(it.id, it.kind, it.value, it.isDefault) }
            group == AcTokens.G_ORG -> p.organisations.filter { it.isNotBlank() }.mapIndexed { i, o -> AcCandidate("org$i", "organisation", o, i == 0) }
            group == AcTokens.G_DOC_ANY -> p.documents.filter { it.number.isNotBlank() }.map { AcCandidate(it.id, it.title(), masked(it.number), it.isDefault) }
            group.startsWith("doc:") -> p.documents.filter { it.type == group.removePrefix("doc:") && it.number.isNotBlank() }
                .map { AcCandidate(it.id, it.title(), masked(it.number), it.isDefault) }
            else -> emptyList()
        }
        return list.sortedByDescending { it.isDefault }
    }

    /** A document number in the chooser: the last 3 only (the chooser is on screen next to a page). */
    private fun masked(n: String) = if (n.length <= 4) "•••" else "•••" + n.takeLast(3)

    /** Which profiles can fill at least one field of the form (the others are not offered). */
    fun usable(profiles: List<AcProfile>, tokens: Collection<String>): List<AcProfile> {
        val groups = AcTokens.groupsOf(tokens)
        return profiles.filter { p -> groups.any { candidates(p, it).isNotEmpty() } }
    }

    /** What the chooser asks for [tokens]: the profile when more than one can fill, and each multi-record group, per profile. */
    fun plan(profiles: List<AcProfile>, tokens: Collection<String>): AcChoicePlan {
        val usable = usable(profiles, tokens)
        val groups = AcTokens.groupsOf(tokens)
        return AcChoicePlan(usable, usable.associate { p ->
            p.id to groups.mapNotNull { g ->
                val c = candidates(p, g)
                if (c.size > 1) AcChoiceGroup(g, groupTitle(g), c) else null
            }
        })
    }

    /** The preselected choice: [preferred] (a profile id, e.g. the one used last on this site) else the default profile; the default record per group. */
    fun defaultChoice(profiles: List<AcProfile>, tokens: Collection<String>, preferred: String? = null): AcChoice? {
        val usable = usable(profiles, tokens)
        val p = usable.firstOrNull { it.id == preferred } ?: usable.firstOrNull { it.isDefault } ?: usable.firstOrNull() ?: return null
        return choiceFor(p, tokens)
    }

    /** The default record of every group of [tokens] that [p] has. */
    fun choiceFor(p: AcProfile, tokens: Collection<String>): AcChoice =
        AcChoice(p.id, AcTokens.groupsOf(tokens).mapNotNull { g -> candidates(p, g).firstOrNull()?.let { g to it.recordId } }.toMap())

    /** [choice] with [group] set to [recordId] (a chooser tap). */
    fun with(choice: AcChoice, group: String, recordId: String) = choice.copy(records = choice.records + (group to recordId))

    /** field id → value for every field the choice can fill. A secret token is never given a value. */
    fun values(profiles: List<AcProfile>, fields: List<AcField>, choice: AcChoice): Map<Int, String> {
        val p = profiles.firstOrNull { it.id == choice.profileId } ?: return emptyMap()
        val tokens = fields.map { it.token }
        val docGroup = AcTokens.groupsOf(tokens).firstOrNull { it.startsWith("doc:") }
        return fields.mapNotNull { f ->
            if (AcTokens.isSecret(f.token) || f.token !in AcTokens.PROFILE) return@mapNotNull null
            valueFor(p, f.token, choice, docGroup)?.takeIf { it.isNotEmpty() }?.let { f.id to it }
        }.toMap()
    }

    /** One token's value from the chosen records of [p]; null when the profile has none. */
    fun valueFor(p: AcProfile, token: String, choice: AcChoice, docGroup: String? = null): String? {
        val group = AcTokens.groupOf(token) ?: return null
        val effective = if (token in setOf(AcTokens.X_ISSUING, AcTokens.X_CITIZENSHIP, AcTokens.X_DOC_EXPIRY)) (docGroup ?: AcTokens.G_DOC_ANY) else group
        val rid = choice.records[effective] ?: candidates(p, effective).firstOrNull()?.recordId ?: return null
        return when (group) {
            AcTokens.G_NAME -> p.names.firstOrNull { it.id == rid }?.let { n ->
                when (token) {
                    AcTokens.NAME -> n.fullName(); AcTokens.GIVEN -> n.given.ifBlank { n.fullName().substringBefore(' ') }
                    AcTokens.ADDITIONAL -> n.middle
                    AcTokens.FAMILY -> n.family.ifBlank { n.fullName().substringAfter(' ', "") }
                    AcTokens.NICKNAME -> n.alias; else -> null
                }
            }
            AcTokens.G_ADDRESS -> p.addresses.firstOrNull { it.id == rid }?.let { a ->
                when (token) {
                    AcTokens.STREET_ADDRESS -> listOf(line1(a), a.line2).filter { it.isNotBlank() }.joinToString("\n")
                    AcTokens.LINE1 -> line1(a)
                    AcTokens.LINE2 -> a.line2
                    AcTokens.LINE3 -> ""
                    AcTokens.LEVEL1 -> a.region; AcTokens.LEVEL2 -> a.city; AcTokens.POSTAL -> a.postal
                    AcTokens.COUNTRY -> a.country; AcTokens.COUNTRY_NAME -> AddressFormat.countryName(a.country)
                    AcTokens.X_STREET -> a.street; AcTokens.X_NUMBER -> a.number
                    else -> null
                }
            }
            AcTokens.G_PHONE -> p.phones.firstOrNull { it.id == rid }?.value
            AcTokens.G_EMAIL -> p.emails.firstOrNull { it.id == rid }?.value
            AcTokens.G_DATE -> p.dates.firstOrNull { it.id == rid }?.let { d ->
                when (token) { AcTokens.BDAY -> d.value; AcTokens.BDAY_DAY -> d.part('d'); AcTokens.BDAY_MONTH -> d.part('m'); AcTokens.BDAY_YEAR -> d.part('y'); else -> null }
            }
            AcTokens.G_ORG -> p.organisations.getOrNull(rid.removePrefix("org").toIntOrNull() ?: 0)
            else -> p.documents.firstOrNull { it.id == rid }?.let { d ->
                when (token) {
                    AcTokens.X_ISSUING -> d.issuingCountry; AcTokens.X_CITIZENSHIP -> d.citizenship; AcTokens.X_DOC_EXPIRY -> d.expires
                    else -> d.number
                }
            }
        }
    }

    private fun line1(a: AcAddress) = AddressFormat.line1(a.country, a.street, a.number)
}
