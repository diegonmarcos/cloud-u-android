package com.diegonmarcos.superapp.browser

import org.json.JSONObject

/** One field of a SUBMITTED form as the engine reported it: its signals, its typed value, whether Android autofill filled it. */
data class AcSubmitted(val id: Int, val sig: AcFieldSig, val type: String, val value: String, val cls: AcClass, val fw: Boolean) {
    override fun toString() = "AcSubmitted($id, ${cls.token}, $REDACTED)"
}

/** What a submitted form could add to the user's stores. Every secret-bearing case prints nothing secret. */
sealed class AcProposal {
    abstract val title: String

    /** A sign-in worth saving ([existingId] != null = the saved password changed). */
    data class Password(val site: String, val username: String, val password: String, val existingId: String?, val fromVault: Boolean) : AcProposal() {
        override val title get() = (if (existingId != null) "Update password for " else "Save password for ") + username.ifBlank { site }
        override fun toString() = "Password($site, $REDACTED)"
    }

    data class CardSave(val card: AcCard) : AcProposal() {
        override val title get() = "Save ${card.title()}"
        override fun toString() = "CardSave($REDACTED)"
    }

    /** One new record for a profile: exactly one of the record fields is set. */
    data class ProfileRecord(
        val group: String,
        val name: AcName? = null, val address: AcAddress? = null, val phone: AcPhone? = null, val email: AcEmail? = null,
        val date: AcDate? = null, val document: AcDocument? = null, val organisation: String? = null,
    ) : AcProposal() {
        override val title get() = "Add ${AcFill.groupTitle(group).lowercase()}: " + preview()
        fun preview(): String = name?.title() ?: address?.let { listOf(it.street, it.number, it.city).filter { s -> s.isNotBlank() }.joinToString(" ") }
            ?: phone?.value ?: email?.value ?: date?.value ?: document?.title() ?: organisation.orEmpty()
        override fun toString() = "ProfileRecord($group, $REDACTED)"
    }

    /** A value typed on this site only (a customer number, a member ID). */
    data class SiteValue(val entry: AcMemoryEntry) : AcProposal() {
        override val title get() = "Remember on ${entry.origin}: ${entry.label.ifBlank { entry.fieldKey }}"
        override fun toString() = "SiteValue(${entry.origin}, $REDACTED)"
    }
}

/** What the submit builder may offer, from the settings. */
data class AcOffer(val password: Boolean, val card: Boolean, val profileData: Boolean, val siteMemory: Boolean)

object AcProposals {

    /** Classify a reported field list (JSON from the engine) into [AcSubmitted], reclassifying a login form's identity box. */
    fun parse(fields: org.json.JSONArray?): List<AcSubmitted> {
        if (fields == null) return emptyList()
        val raw = (0 until fields.length()).mapNotNull { fields.optJSONObject(it) }.map { o ->
            val sig = signals(o)
            Triple(o, sig, AcFieldClassifier.classify(sig))
        }
        val classes = loginReclass(raw.map { it.third }, raw.map { it.second })
        return raw.mapIndexed { i, (o, sig, _) ->
            AcSubmitted(o.optInt("id", -1), AcFieldSig(sig.name, sig.id, sig.autocomplete, sig.label), sig.type, o.optString("v"), classes[i], o.optBoolean("fw"))
        }.filter { it.id >= 0 }
    }

    fun signals(o: JSONObject) = AcFieldSignals(
        autocomplete = o.optString("autocomplete"), name = o.optString("name"), id = o.optString("eid"), label = o.optString("label"),
        placeholder = o.optString("placeholder"), aria = o.optString("aria"), type = o.optString("type", "text"),
        inputMode = o.optString("inputmode"), maxLength = o.optInt("maxlength"),
    )

    /**
     * In a SIGN-IN form (a current-password box, no new-password box, at most three text boxes) the one identity box
     * before the password is the user name, whatever it is called (an email box labelled "Email" included).
     */
    fun loginReclass(classes: List<AcClass>, sigs: List<AcFieldSignals>): List<AcClass> {
        val pw = classes.indexOfFirst { it.kind == AcKind.PASSWORD }
        if (pw < 0 || classes.any { it.kind == AcKind.NEW_PASSWORD }) return classes
        val textish = classes.indices.filter { sigs[it].type.lowercase() !in setOf("password", "hidden", "checkbox", "radio", "submit", "button") }
        if (textish.size > 3) return classes
        val userAt = (pw - 1 downTo 0).firstOrNull { it in textish && classes[it].kind in setOf(AcKind.PROFILE, AcKind.LOGIN_USER, AcKind.SITE) } ?: return classes
        return classes.mapIndexed { i, c -> if (i == userAt) AcClass(AcTokens.USERNAME, AcKind.LOGIN_USER) else c }
    }

    /** Luhn check of a card number (spaces and dashes allowed). */
    fun luhn(number: String): Boolean {
        val d = number.filter { it.isDigit() }
        if (d.length !in 13..19 || number.any { !it.isDigit() && it != ' ' && it != '-' }) return false
        var sum = 0
        d.reversed().forEachIndexed { i, c -> var n = c - '0'; if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9 }; sum += n }
        return sum % 10 == 0
    }

    /** "05/27", "05 / 2027", "0527", "2027-05" to (mm, yyyy) or null. */
    fun parseExpiry(s: String): Pair<String, String>? {
        val t = s.trim()
        Regex("^(\\d{4})-(\\d{1,2})$").find(t)?.let { return it.groupValues[2].padStart(2, '0') to it.groupValues[1] }
        val m = Regex("^(\\d{1,2})\\s*[/\\-. ]?\\s*(\\d{2}|\\d{4})$").find(t) ?: return null
        val mm = m.groupValues[1].padStart(2, '0')
        val yy = m.groupValues[2]
        if (mm.toInt() !in 1..12) return null
        return mm to (if (yy.length == 2) "20$yy" else yy)
    }

    /**
     * What to offer after [fields] were submitted on [site] (host) in form [formSig]. Nothing in a private tab (the caller),
     * nothing a store already holds, nothing for a field Android autofill filled from Cloud Vault except a password the
     * vault now owns ([fromVault]: the merge already happened; no save prompt).
     */
    fun fromSubmission(
        fields: List<AcSubmitted>, site: String, formSig: String,
        profiles: List<AcProfile>, logins: List<AcLogin>, cards: List<AcCard>, memory: List<AcMemoryEntry>,
        offer: AcOffer, neverPassword: Set<String> = emptySet(),
    ): List<AcProposal> {
        val out = ArrayList<AcProposal>()
        val reg = AcSite.registrable(site)
        val origin = AcSiteMemory.originKey(site)
        fun v(token: String) = fields.firstOrNull { it.cls.token == token }?.value?.trim().orEmpty()

        // sign-in
        val pwField = fields.firstOrNull { it.cls.kind == AcKind.NEW_PASSWORD && it.value.isNotBlank() } ?: fields.firstOrNull { it.cls.kind == AcKind.PASSWORD && it.value.isNotBlank() }
        if (pwField != null && offer.password && reg !in neverPassword && !pwField.fw) {
            val user = fields.firstOrNull { it.cls.kind == AcKind.LOGIN_USER }?.value?.trim().orEmpty()
                .ifBlank { fields.firstOrNull { it.cls.token == AcTokens.EMAIL }?.value?.trim().orEmpty() }
            val same = logins.firstOrNull { AcSite.same(it.site, reg) && it.username.equals(user, ignoreCase = true) }
            when {
                same == null -> out += AcProposal.Password(reg, user, pwField.value, null, false)
                same.password != pwField.value -> out += AcProposal.Password(reg, user, pwField.value, same.id, false)
            }
        }
        // card
        val num = v(AcTokens.CC_NUMBER)
        if (offer.card && num.isNotBlank() && luhn(num) && cards.none { it.digits == num.filter { c -> c.isDigit() } }) {
            val exp = parseExpiry(v(AcTokens.CC_EXP)) ?: run {
                val m = v(AcTokens.CC_EXP_MONTH).padStart(2, '0'); val y = v(AcTokens.CC_EXP_YEAR).let { if (it.length == 2) "20$it" else it }
                if (m.length == 2 && y.length == 4) m to y else null
            }
            out += AcProposal.CardSave(AcCard(holder = v(AcTokens.CC_NAME), number = num, expMonth = exp?.first.orEmpty(), expYear = exp?.second.orEmpty(), cvc = ""))
        }
        // profile data: only from a form that is not a sign-in
        val signIn = fields.any { it.cls.kind == AcKind.PASSWORD || it.cls.kind == AcKind.NEW_PASSWORD && fields.none { f -> f.cls.token == AcTokens.GIVEN || f.cls.token == AcTokens.NAME } }
        if (offer.profileData && !signIn) out += profileRecords(fields, profiles)
        // per-site values
        if (offer.siteMemory) fields.filter { it.cls.kind == AcKind.SITE && AcSiteMemory.shouldRemember(it.sig, it.cls, it.value, it.type) }.forEach { f ->
            val key = f.sig.key()
            if (memory.none { it.origin == origin && it.fieldKey == key && it.value == f.value.trim() })
                out += AcProposal.SiteValue(AcMemoryEntry(origin = origin, formSig = formSig, fieldKey = key, label = f.sig.label.ifBlank { f.sig.name }, value = f.value.trim()))
        }
        return out
    }

    /** New names / addresses / phones / emails / dates / documents / organisation that NO profile holds yet. */
    fun profileRecords(fields: List<AcSubmitted>, profiles: List<AcProfile>): List<AcProposal.ProfileRecord> {
        fun v(token: String) = fields.firstOrNull { it.cls.token == token }?.value?.trim().orEmpty()
        val out = ArrayList<AcProposal.ProfileRecord>()
        fun norm(s: String) = AcFieldClassifier.norm(s).replace(" ", "")

        val given = v(AcTokens.GIVEN); val family = v(AcTokens.FAMILY); val full = v(AcTokens.NAME)
        if (given.isNotBlank() || family.isNotBlank() || full.isNotBlank()) {
            val n = AcName(given = given, middle = v(AcTokens.ADDITIONAL), family = family, full = if (given.isBlank() && family.isBlank()) full else "")
            val key = norm(n.fullName())
            if (key.isNotEmpty() && profiles.none { p -> p.names.any { norm(it.fullName()) == key } }) out += AcProposal.ProfileRecord(AcTokens.G_NAME, name = n)
        }
        val street = v(AcTokens.X_STREET).ifBlank { v(AcTokens.LINE1) }.ifBlank { v(AcTokens.STREET_ADDRESS).lines().firstOrNull().orEmpty() }
        val postal = v(AcTokens.POSTAL)
        if (street.isNotBlank() || postal.isNotBlank()) {
            val a = AcAddress(label = "home", street = street, number = v(AcTokens.X_NUMBER), line2 = v(AcTokens.LINE2), city = v(AcTokens.LEVEL2), postal = postal,
                region = v(AcTokens.LEVEL1), country = v(AcTokens.COUNTRY))
            if (profiles.none { p -> p.addresses.any { norm(it.street + it.number) == norm(a.street + a.number) && norm(it.postal) == norm(a.postal) } }) out += AcProposal.ProfileRecord(AcTokens.G_ADDRESS, address = a)
        }
        v(AcTokens.TEL).takeIf { it.isNotBlank() && profiles.none { p -> p.phones.any { x -> norm(x.value) == norm(it) } } }?.let { out += AcProposal.ProfileRecord(AcTokens.G_PHONE, phone = AcPhone(value = it)) }
        v(AcTokens.EMAIL).takeIf { it.isNotBlank() && profiles.none { p -> p.emails.any { x -> x.value.equals(it, true) } } }?.let { out += AcProposal.ProfileRecord(AcTokens.G_EMAIL, email = AcEmail(value = it)) }
        v(AcTokens.BDAY).takeIf { it.isNotBlank() && profiles.none { p -> p.dates.any { x -> x.value == it } } }?.let { out += AcProposal.ProfileRecord(AcTokens.G_DATE, date = AcDate(value = it)) }
        v(AcTokens.ORGANIZATION).takeIf { it.isNotBlank() && profiles.none { p -> p.organisations.any { x -> x.equals(it, true) } } }?.let { out += AcProposal.ProfileRecord(AcTokens.G_ORG, organisation = it) }
        for ((token, type) in listOf(AcTokens.X_PASSPORT to AcDocType.PASSPORT, AcTokens.X_NATIONAL_ID to AcDocType.NATIONAL_ID, AcTokens.X_TAX_ID to AcDocType.TAX_ID,
            AcTokens.X_RESIDENCE to AcDocType.RESIDENCE_PERMIT, AcTokens.X_LICENCE to AcDocType.DRIVER_LICENCE, AcTokens.X_DOCUMENT to AcDocType.OTHER)) {
            val num = v(token)
            if (num.isNotBlank() && profiles.none { p -> p.documents.any { norm(it.number) == norm(num) } })
                out += AcProposal.ProfileRecord(AcTokens.docGroup(type), document = AcDocument(type = type, number = num, issuingCountry = v(AcTokens.X_ISSUING),
                    citizenship = v(AcTokens.X_CITIZENSHIP), expires = v(AcTokens.X_DOC_EXPIRY)))
        }
        return out
    }

    /** [profile] with the record of [p] added (default stays where it was). */
    fun addTo(profile: AcProfile, p: AcProposal.ProfileRecord): AcProfile = profile.copy(
        names = profile.names + listOfNotNull(p.name), addresses = profile.addresses + listOfNotNull(p.address),
        phones = profile.phones + listOfNotNull(p.phone), emails = profile.emails + listOfNotNull(p.email),
        dates = profile.dates + listOfNotNull(p.date), documents = profile.documents + listOfNotNull(p.document),
        organisations = profile.organisations + listOfNotNull(p.organisation),
    ).normalized()

    /** A strong random password: 20 characters from letters, digits and a few symbols, at least one of each class. */
    fun generatePassword(random: java.util.Random = java.security.SecureRandom(), length: Int = 20): String {
        val sets = listOf("abcdefghijkmnopqrstuvwxyz", "ABCDEFGHJKLMNPQRSTUVWXYZ", "23456789", "-_.!@#%^*+=")
        val all = sets.joinToString("")
        val chars = ArrayList<Char>(sets.map { it[random.nextInt(it.length)] })
        while (chars.size < length) chars += all[random.nextInt(all.length)]
        chars.shuffle(random)
        return chars.joinToString("")
    }
}
