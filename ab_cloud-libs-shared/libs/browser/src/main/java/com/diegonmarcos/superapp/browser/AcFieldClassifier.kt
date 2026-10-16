package com.diegonmarcos.superapp.browser

import java.text.Normalizer

/** What a page's field reports about itself. Never its value. */
data class AcFieldSignals(
    /** The autocomplete attribute as written ("section-ship shipping given-name"). */
    val autocomplete: String = "",
    val name: String = "",
    val id: String = "",
    /** The associated label's text (label[for], wrapping label, aria-labelledby). */
    val label: String = "",
    val placeholder: String = "",
    val aria: String = "",
    /** input type (text, email, tel, password, ...), `select-one`, `textarea`. */
    val type: String = "text",
    val inputMode: String = "",
    val maxLength: Int = 0,
)

/** What kind of field it is to the autocomplete system. */
enum class AcKind {
    /** personal data a profile holds (name, address, phone, email, birth date, document). */
    PROFILE,
    /** a user name / email on a sign-in form. */
    LOGIN_USER,
    PASSWORD,
    NEW_PASSWORD,
    OTP,
    CARD,
    /** a text field that is none of the above: a candidate for the per-site memory. */
    SITE,
    /** a search box, a captcha, a button: never remembered or filled. */
    IGNORE,
}

data class AcClass(val token: String?, val kind: AcKind) {
    val isSecret get() = kind == AcKind.PASSWORD || kind == AcKind.NEW_PASSWORD || kind == AcKind.OTP || kind == AcKind.CARD
}

/**
 * #900 THE FIELD CLASSIFIER: one field's signals (autocomplete attribute, name, id, label, placeholder, aria, type)
 * to its HTML autocomplete token and kind. Order: (1) a recognised WHATWG token of the autocomplete attribute
 * (the last one wins: "shipping given-name"); (2) the input type (password, email, tel); (3) the words of
 * name / id / label / placeholder / aria in EN, ES, PT, FR and DE, most specific first. A secret is recognised
 * BEFORE any profile word, so a password box labelled "Name of your first pet" cannot be mistaken for a name.
 * Pure: JVM-tested.
 */
object AcFieldClassifier {

    private val SKIP_TYPES = setOf("hidden", "submit", "button", "checkbox", "radio", "file", "image", "reset", "range", "color")

    /** Tokens of the autocomplete attribute this classifier maps (others are ignored, not guessed). */
    private val TOKEN_ALIASES = mapOf(
        "tel-national" to AcTokens.TEL, "tel-local" to AcTokens.TEL, "address-line3" to AcTokens.LINE2, "country-name" to AcTokens.COUNTRY,
        "street-address" to AcTokens.STREET_ADDRESS, "bday-day" to AcTokens.BDAY_DAY, "bday-month" to AcTokens.BDAY_MONTH,
        "bday-year" to AcTokens.BDAY_YEAR, "nickname" to AcTokens.NICKNAME, "honorific-prefix" to AcTokens.HONORIFIC,
    )
    private val ATTR_PROFILE = setOf(AcTokens.NAME, AcTokens.GIVEN, AcTokens.ADDITIONAL, AcTokens.FAMILY, AcTokens.EMAIL, AcTokens.TEL,
        AcTokens.ORGANIZATION, AcTokens.LINE1, AcTokens.LINE2, AcTokens.LEVEL1, AcTokens.LEVEL2, AcTokens.POSTAL, AcTokens.COUNTRY, AcTokens.BDAY)

    // words -> token, in priority order (most specific first). Text is lower-cased, accents stripped.
    private val CARD_WORDS = listOf(
        AcTokens.CC_CSC to Regex("""\b(cvv2?|cvc2?|csc|cid)\b|security.?code|card.?verification|sicherheitscode|codigo de seguridad|codigo de verificacao|cryptogramme|pruefnummer"""),
        AcTokens.CC_EXP to Regex("""mm ?/ ?(yy|yyyy)|mm ?- ?(yy|yyyy)"""),
        AcTokens.CC_EXP_MONTH to Regex("""(exp|expir|venc|caduc|ablauf|valid).{0,16}(month|mes|mois|monat)|\bmm\b"""),
        AcTokens.CC_EXP_YEAR to Regex("""(exp|expir|venc|caduc|ablauf|valid).{0,16}(year|ano|anio|annee|jahr)|\byy(yy)?\b"""),
        AcTokens.CC_EXP to Regex("""expir|expiry|expiration|caduc|vencimento|validade|ablaufdatum|gueltig bis|valid thru|valid until|mm ?/ ?yy"""),
        AcTokens.CC_NUMBER to Regex("""card.?(number|no\b|num)|credit.?card|debit.?card|cc.?num|kartennummer|numero de (la )?tarjeta|numero do cartao|numero de carte|\bpan\b"""),
        AcTokens.CC_NAME to Regex("""(name|nombre|nome|nom|inhaber).{0,12}(on|en|no|sur|der)?.{0,6}(card|tarjeta|cartao|carte|karte)|card.?holder|titular|karteninhaber"""),
    )
    private val OTP_WORDS = Regex("""\botp\b|one.?time|2fa|totp|verification.?code|auth(entication)?.?code|codigo de verific|bestaetigungscode|code de verif|sms.?code|\btan\b""")
    private val PASSWORD_WORDS = Regex("""pass(word|wort|e)?\b|passwd|\bpwd\b|contrasena|senha|mot de passe|kennwort|secret.?phrase|\bpin\b""")
    private val IGNORE_WORDS = Regex("""\b(search|suche|buscar|recherche|pesquisa|query|captcha|recaptcha|coupon|promo|voucher|gutschein|cupon|cupom)\b""")
    private val IGNORE_NAMES = setOf("q", "s", "query", "search", "keyword", "keywords", "term", "terms")
    /** "code" next to one of these is not a one-time code. */
    private val NOT_OTP = Regex("""postal|zip|\bpost|plz|promo|coupon|voucher|discount|area|country|sort|swift|\bbic\b|\bcep\b|\bcp\b|gutschein|dial|language|currency""")
    private val LOGIN_USER_WORDS = Regex("""user.?name|\buser\b|\blogin\b|usuario|utilisateur|benutzer|nutzername|identifiant|account.?(id|name)|\bsignin\b|sign.?in.?(id|name)""")

    private val WORDS = listOf(
        AcTokens.EMAIL to Regex("""e.?mail|correo|courriel|e-post"""),
        AcTokens.POSTAL to Regex("""\bzip\b|zip.?code|postal.?code|post.?code|\bplz\b|postleitzahl|codigo postal|\bcep\b|code postal|\bcp\b"""),
        AcTokens.TEL to Regex("""phone|\btel\b|telephone|mobile|\bcell|telefon|telefono|celular|\bmovil|handy|portable|whatsapp"""),
        AcTokens.X_PASSPORT to Regex("""passport|pasaporte|passaporte|reisepass|\bpass.?nr|numero de passeport"""),
        AcTokens.X_TAX_ID to Regex("""tax.?id|steuer.?id|steuernummer|\bnif\b|\bcif\b|\bcpf\b|\btin\b|\bvat\b|\bnie\b|identificacion fiscal|codice fiscale"""),
        AcTokens.X_RESIDENCE to Regex("""residence.?permit|aufenthaltstitel|tarjeta de residencia|titre de sejour|permiso de residencia"""),
        AcTokens.X_LICENCE to Regex("""driv(er|ing).?licen[cs]e|fuehrerschein|permiso de conducir|carnet de conducir|\bcnh\b|permis de conduire"""),
        AcTokens.X_NATIONAL_ID to Regex("""national.?id|\bdni\b|personalausweis|ausweis|identity.?(card|number)|id.?card|carte d.?identite|\brg\b|cedula|numero de identidad"""),
        AcTokens.X_DOCUMENT to Regex("""document.?(number|no\b|num)|numero de documento|dokumentnummer|id.?number"""),
        AcTokens.X_ISSUING to Regex("""issu(ing|ed).?(country|by)|country.?of.?issue|pais de expedicion|ausstellungsland|pais emissor"""),
        AcTokens.X_CITIZENSHIP to Regex("""citizen|nationality|nacionalidad|nacionalidade|staatsangeh|nationalite"""),
        AcTokens.X_DOC_EXPIRY to Regex("""(document|passport|id).{0,12}(expir|valid)|fecha de caducidad del documento"""),
        AcTokens.BDAY to Regex("""birth|\bdob\b|geburt|nacimiento|nascimento|naissance|date of birth"""),
        AcTokens.X_NUMBER to Regex("""hausnummer|haus.?nr|house.?(number|no)\b|street.?(number|no)\b|building.?(number|no)\b|numero de (la )?(calle|casa|portal)|numero da (casa|rua)|\bhnr\b"""),
        AcTokens.LINE2 to Regex("""address.?(line)?.?2|line.?2|apartment|\bapt\b|\bapto\b|suite|\bunit\b|apartamento|complemento|wohnung|appartement|bloco|adresszusatz|piso|puerta"""),
        AcTokens.GIVEN to Regex("""first.?name|\bfname|given.?name|forename|vorname|prenom|primeiro nome|nombre de pila|^nombre$"""),
        AcTokens.FAMILY to Regex("""last.?name|\blname|surname|family.?name|nachname|apellido|sobrenome|nom de famille"""),
        AcTokens.ORGANIZATION to Regex("""company|organi[sz]ation|\bfirma|empresa|societe|entreprise|unternehmen|employer"""),
        AcTokens.LINE1 to Regex("""street|address|\baddr|strasse|calle|direccion|endereco|logradouro|\brua\b|adresse|\brue\b"""),
        AcTokens.LEVEL2 to Regex("""\bcity\b|\btown\b|locality|\bort\b|stadt|ciudad|localidad|cidade|\bville\b|municipio|commune"""),
        AcTokens.LEVEL1 to Regex("""\bstate\b|province|region|county|bundesland|provincia|\bestado\b|departement"""),
        AcTokens.COUNTRY to Regex("""country|\bland\b|\bpais\b|\bpays\b"""),
        AcTokens.NAME to Regex("""full.?name|your.?name|^name$|\bname\b|nombre completo|nome completo|nom complet|vollstaendiger name|\bnome\b|\bnombre\b"""),
    )

    fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        t = t.replace("ß", "ss")
        // camelCase and snake/kebab to words
        return t.replace(Regex("[_\\-\\[\\]\\.]+"), " ").trim()
    }

    private fun words(s: AcFieldSignals): String =
        norm(listOf(splitCamel(s.name), splitCamel(s.id), s.label, s.placeholder, s.aria).joinToString(" "))

    private fun splitCamel(s: String) = s.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")

    /** The classification of one field. */
    fun classify(s: AcFieldSignals): AcClass {
        val type = s.type.lowercase().ifBlank { "text" }
        if (type in SKIP_TYPES) return AcClass(null, AcKind.IGNORE)
        val ws = words(s)
        val ac = s.autocomplete.lowercase().trim()

        // 1. the autocomplete attribute: the last token we know wins ("section-a shipping given-name")
        if (ac.isNotEmpty() && ac != "on" && ac != "off") {
            for (tok in ac.split(Regex("\\s+")).reversed()) {
                when (tok) {
                    AcTokens.CURRENT_PASSWORD -> return AcClass(tok, AcKind.PASSWORD)
                    AcTokens.NEW_PASSWORD -> return AcClass(tok, AcKind.NEW_PASSWORD)
                    AcTokens.OTP -> return AcClass(tok, AcKind.OTP)
                    AcTokens.USERNAME -> return AcClass(tok, AcKind.LOGIN_USER)
                    "webauthn" -> return AcClass(null, AcKind.IGNORE)
                }
                if (tok in AcTokens.CARD) return AcClass(tok, AcKind.CARD)
                val mapped = TOKEN_ALIASES[tok] ?: tok.takeIf { it in ATTR_PROFILE }
                if (mapped != null) return AcClass(mapped, AcKind.PROFILE)
            }
        }
        // 2. the type: a password box is a secret whatever it is called
        if (type == "password") return AcClass(if (looksNew(ws, ac)) AcTokens.NEW_PASSWORD else AcTokens.CURRENT_PASSWORD, if (looksNew(ws, ac)) AcKind.NEW_PASSWORD else AcKind.PASSWORD)
        // 3. secrets by words, BEFORE profile words
        val oneTimeShape = (s.inputMode == "numeric" || type == "number" || type == "tel" || type == "text") && s.maxLength in 4..8 &&
            Regex("\\bcode\\b|codigo").containsMatchIn(ws) && !NOT_OTP.containsMatchIn(ws)
        if (OTP_WORDS.containsMatchIn(ws) || oneTimeShape) return AcClass(AcTokens.OTP, AcKind.OTP)
        for ((tok, rx) in CARD_WORDS) if (rx.containsMatchIn(ws)) return AcClass(tok, AcKind.CARD)
        if (PASSWORD_WORDS.containsMatchIn(ws)) return AcClass(AcTokens.CURRENT_PASSWORD, AcKind.PASSWORD)
        if (IGNORE_WORDS.containsMatchIn(ws) || type == "search" || s.name.lowercase() in IGNORE_NAMES) return AcClass(null, AcKind.IGNORE)
        // 4. type hints
        if (type == "email") return AcClass(AcTokens.EMAIL, AcKind.PROFILE)
        if (type == "tel") return AcClass(AcTokens.TEL, AcKind.PROFILE)
        if (type == "textarea") return AcClass(null, AcKind.IGNORE)
        // "Street and house number" in ONE box is the whole first line, not the number.
        if (Regex("strasse|street|calle|\\brua\\b|\\brue\\b").containsMatchIn(ws) && Regex("hausnummer|house.?(number|no)|\\bnumero\\b|\\bnr\\b").containsMatchIn(ws))
            return AcClass(AcTokens.LINE1, AcKind.PROFILE)
        // a sign-in box by its words (a plain "user name" must not become the person's NAME)
        if (LOGIN_USER_WORDS.containsMatchIn(ws)) return AcClass(AcTokens.USERNAME, AcKind.LOGIN_USER)
        // 5. profile words
        for ((tok, rx) in WORDS) if (rx.containsMatchIn(ws)) return AcClass(tok, AcKind.PROFILE)
        if (type == "number" || type == "date" || type == "datetime-local" || type == "time" || type == "month" || type == "week" || type == "url") return AcClass(null, AcKind.IGNORE)
        return AcClass(null, AcKind.SITE)
    }

    private fun looksNew(ws: String, ac: String) =
        ac.contains("new-password") || Regex("\\bnew\\b|confirm|repeat|retype|nueva|nuevo|neues|novo|nova|nouveau|bestaetig|wiederhol").containsMatchIn(ws)
}
