package com.x8bit.bitwarden.data.autofill.cloud

import java.text.Normalizer

/**
 * Cloud Vault's ID-document field rules: which fields an Identity item fills (see
 * [IdentityField]). Pure Kotlin, like [CloudFieldClassifier], so the rules are pinned by JVM tests
 * with fake [FieldSignals].
 *
 * Signals, in this order:
 *
 * 1. Never ours: a password / OTP / username / email / phone / card autofill hint, a password or
 *    email input type, an HTML `type` of password, email or tel, an `autocomplete` token for
 *    those, or password / PIN / one-time-code / card wording.
 * 2. The HTML `autocomplete` token or the Android / androidx autofill hint for a name or address
 *    part (`given-name`, `postal-code`, `personFamilyName`, ...): a fill-only field.
 * 3. ID-document wording in the id, name, label, placeholder, aria-label, title or a non-standard
 *    autofill hint, EN / ES / DE / PT (and the FR "carte d'identité"): issuing country, expiry,
 *    support number, passport, driving licence, social security, residence permit, DNI, NIE,
 *    NIF, Personalausweis / Ausweisnummer, CPF, RG / identidade, then the generic "national ID",
 *    "ID number", "document number". The generic ones never match a field that also talks about a
 *    user, account, customer, order, vehicle, ... ("User ID", "Customer ID number").
 * 4. Name and address wording, as fill-only fields.
 *
 * A bare "ID" never makes a document field.
 */
object IdentityFieldClassifier {

    private val MEANINGFUL_HTML_ATTRIBUTES = listOf(
        "id", "name", "label", "placeholder", "aria-label", "hint", "title",
    )

    /** Autofill hints (lower-cased) that name the field as something that is not ours. */
    private val FOREIGN_HINT_PREFIXES = listOf(
        "password", "newpassword", "currentpassword", "username", "newusername", "email",
        "phone", "creditcard", "cc-", "smsotp", "2faapp", "otp", "totp", "onetimecode",
        "one-time-code", "birthdate", "gender", "pin",
    )

    /** Android and androidx autofill hints (lower-cased) for a name or address part. */
    private val HINT_FIELDS: Map<String, IdentityField> = mapOf(
        "persongivenname" to IdentityField.FIRST_NAME,
        "personmiddlename" to IdentityField.MIDDLE_NAME,
        "personfamilyname" to IdentityField.LAST_NAME,
        "personname" to IdentityField.FULL_NAME,
        "name" to IdentityField.FULL_NAME,
        "streetaddress" to IdentityField.ADDRESS_1,
        "postaladdressstreetaddress" to IdentityField.ADDRESS_1,
        "extendedaddress" to IdentityField.ADDRESS_2,
        "postaladdressextendedaddress" to IdentityField.ADDRESS_2,
        "addresslocality" to IdentityField.CITY,
        "postaladdresslocality" to IdentityField.CITY,
        "addressregion" to IdentityField.STATE,
        "postaladdressregion" to IdentityField.STATE,
        "postalcode" to IdentityField.POSTAL_CODE,
        "addresscountry" to IdentityField.COUNTRY,
        "postaladdresscountry" to IdentityField.COUNTRY,
    )

    /** HTML `autocomplete` tokens for a name or address part. */
    private val AUTOCOMPLETE_FIELDS: Map<String, IdentityField> = mapOf(
        "given-name" to IdentityField.FIRST_NAME,
        "additional-name" to IdentityField.MIDDLE_NAME,
        "family-name" to IdentityField.LAST_NAME,
        "name" to IdentityField.FULL_NAME,
        "street-address" to IdentityField.ADDRESS_1,
        "address-line1" to IdentityField.ADDRESS_1,
        "address-line2" to IdentityField.ADDRESS_2,
        "address-line3" to IdentityField.ADDRESS_3,
        "address-level2" to IdentityField.CITY,
        "address-level1" to IdentityField.STATE,
        "postal-code" to IdentityField.POSTAL_CODE,
        "country" to IdentityField.COUNTRY,
        "country-name" to IdentityField.COUNTRY,
    )

    /** HTML `autocomplete` tokens (or prefixes) that say the field is something else. */
    private val FOREIGN_AUTOCOMPLETE_PREFIXES = listOf(
        "username", "email", "current-password", "new-password", "one-time-code", "webauthn",
        "tel", "cc-", "organization", "bday", "sex", "url", "photo", "impp", "transaction-",
        "honorific-", "nickname", "language",
    )

    private val PASSWORD_OR_CODE = Regex(
        "\\b(password|passwort|passwd|pwd|pswd|contrasena|clave|senha|kennwort|pin|passcode|" +
            "otp|totp|2fa|mfa|one time|verification code|verify code|security code|" +
            "codigo de verificacion|bestatigungscode|cvv|cvc|csc)\\b",
    )

    /** Card wording; "ID card" / "identity card" were collapsed by [normalize] and do not count. */
    private val CARD_CONTEXT = Regex(
        "\\b(card|cards|cc|credit|debit|tarjeta|karte|kreditkarte|cartao|iban|payment|pago|" +
            "zahlung|pagamento)\\b",
    )

    /** Wording that makes a generic "ID number" / "document number" something else. */
    private val FOREIGN_ID_CONTEXT = Regex(
        "\\b(user|users|username|usuario|benutzer|login|account|cuenta|konto|customer|cliente|" +
            "kunde|kunden|client|member|socio|mitglied|apple|google|microsoft|order|pedido|" +
            "bestellung|transaction|tracking|session|device|employee|empleado|student|vat|iva|" +
            "company|empresa|firma|invoice|factura|rechnung|reference|referencia|booking|reserva|" +
            "ticket|application|solicitud|case|expediente|vehicle|vin|chassis|serial number|" +
            "product|producto|app|wallet|email|mail)\\b",
    )

    /** Words that tie an expiry date to an ID document. */
    private val DOCUMENT_CONTEXT = Regex(
        "\\b(dni|nie|nif|tie|passport|pasaporte|passaporte|reisepass|pass|ausweis|" +
            "personalausweis|idcard|identitycard|documento|document|dokument|identidad|" +
            "identidade|identity|id|cpf|rg|residence|aufenthaltstitel|licen[cs]e|" +
            "fuhrerschein|fuehrerschein|conducir)\\b",
    )

    private val ISSUING_COUNTRY = Regex(
        "\\b(issuing country|country of issue|issuing state|issued in|pais de expedicion|" +
            "pais emisor|pais de emision|pais emissor|ausstellungsland|ausstellender staat|" +
            "ausstellungsstaat)\\b",
    )

    private val EXPIRY = Regex(
        "\\b(valid until|valid thru|valid through|expiry|expiry date|expiration|" +
            "expiration date|date of expiry|expires|fecha de caducidad|caducidad|" +
            "fecha de vencimiento|vencimiento|valido hasta|fecha de validez|gultig bis|" +
            "gueltig bis|gultigkeit|ablaufdatum|ablauf|validade|data de validade|valido ate)\\b",
    )

    private val SUPPORT_NUMBER = Regex(
        "\\b(numero de soporte|num soporte|n soporte|nro soporte|soporte|support number|" +
            "support no|idesp|card access number|zugangsnummer|can nummer|can number|can nr)\\b",
    )

    /** A field that only says "CAN" is the German ID card's access number. */
    private const val CAN_ALONE = "can"

    private val DOCUMENT_RULES: List<Pair<Regex, IdentityField>> = listOf(
        Regex(
            "\\b(passport|passportnumber|passportno|pasaporte|passaporte|reisepass|" +
                "reisepassnummer|passnummer|pass nr|pass nummer|pass no|pass number)\\b",
        ) to IdentityField.PASSPORT_NUMBER,
        Regex(
            "\\b(driver s licen[cs]e|drivers licen[cs]e|driver licen[cs]e|driving licen[cs]e|" +
                "licen[cs]e de conducir|permiso de conducir|carne de conducir|" +
                "carnet de conducir|fuhrerschein|fuehrerschein|fuhrerscheinnummer|" +
                "carteira de motorista|carteira de habilitacao|cnh)\\b",
        ) to IdentityField.LICENSE_NUMBER,
        Regex(
            "\\b(ssn|social security|seguridad social|numero de afiliacion|" +
                "sozialversicherungsnummer)\\b",
        ) to IdentityField.SSN,
        Regex(
            "\\b(residence permit|residence card|aufenthaltstitel|aufenthaltserlaubnis|" +
                "tarjeta de residencia|permiso de residencia|autorizacion de residencia)\\b",
        ) to IdentityField.RESIDENCE_PERMIT,
        Regex("\\b(dni|documento nacional de identidad)\\b") to IdentityField.DNI,
        Regex("\\bnie\\b") to IdentityField.NIE,
        Regex("\\bnif\\b") to IdentityField.NIF,
        Regex(
            "\\b(personalausweis|personalausweisnummer|ausweisnummer|ausweis|ausweis nr|" +
                "ausweis nummer)\\b",
        ) to IdentityField.PERSONALAUSWEIS,
        Regex("\\bcpf\\b") to IdentityField.CPF,
        Regex("\\b(rg|registro geral|carteira de identidade|identidade)\\b") to IdentityField.RG,
    )

    /** Generic national-ID wording: needs a field that talks about nothing else. */
    private val NATIONAL_ID = Regex(
        "\\b(national id|national identity|nationalid|nationalidnumber|id number|idnumber|" +
            "id no|id nr|id nummer|identity number|idcard|idcard number|identitycard|" +
            "identitycard number|identity document|identification number|" +
            "numero de identificacion|numero de identidad|documento de identidad|" +
            "carte d identite|cedula|cedula de identidad)\\b",
    )

    /** Generic document-number wording: needs a field that talks about nothing else. */
    private val DOCUMENT_NUMBER = Regex(
        "\\b(document number|document no|document nr|documentnumber|doc number|doc no|" +
            "numero de documento|numero documento|num documento|nro documento|n documento|" +
            "numero do documento|dokumentennummer|dokumentnummer|documento)\\b",
    )

    /** Spanish forms split the two surnames; Bitwarden has one last name, so neither is ours. */
    private val SPLIT_SURNAME = Regex(
        "\\b(primer apellido|segundo apellido|second surname|second family name|" +
            "2nd surname|first surname)\\b",
    )

    private val NOT_A_NAME_OR_ADDRESS = Regex(
        "\\b(email|e mail|correo|mail|phone|telefono|telefon|tel|mobile|movil|handy|celular|" +
            "user|username|usuario|benutzer|login|company|empresa|firma|organization|" +
            "organisation|business|card|cc|search|buscar|suche|coupon|promo|ip|url|web|website|" +
            "wallet|tarjeta|titular|birth|nacimiento|geburt|nascimento|nationality|nacionalidad|nacionalidade|" +
            "staatsangehorigkeit|civil|marital)\\b",
    )

    /** A phone's country code / dial prefix is no country field. */
    private val NOT_A_COUNTRY = Regex(
        "\\b(code|dial|dialing|calling|prefix|prefijo|vorwahl|ddi)\\b",
    )

    private val NAME_AND_ADDRESS_RULES: List<Pair<Regex, IdentityField>> = listOf(
        Regex(
            "\\b(full name|fullname|your name|nombre completo|nombre y apellidos|nome completo|" +
                "vollstandiger name|vor und nachname)\\b",
        ) to IdentityField.FULL_NAME,
        Regex(
            "\\b(middle name|middlename|segundo nombre|zweitname|zweiter vorname|nome do meio|" +
                "additional name)\\b",
        ) to IdentityField.MIDDLE_NAME,
        Regex(
            "\\b(first name|firstname|given name|givenname|forename|fname|vorname|nombre|" +
                "primeiro nome|nome|prenom)\\b",
        ) to IdentityField.FIRST_NAME,
        Regex(
            "\\b(last name|lastname|surname|family name|familyname|lname|apellidos|apellido|" +
                "nachname|familienname|sobrenome|apelido)\\b",
        ) to IdentityField.LAST_NAME,
        Regex("\\b(address line 3|address3|addressline3)\\b") to IdentityField.ADDRESS_3,
        Regex(
            "\\b(address line 2|address2|addressline2|apartment|apt|suite|piso|puerta|planta|" +
                "escalera|complemento|adresszusatz|etage|stockwerk)\\b",
        ) to IdentityField.ADDRESS_2,
        Regex(
            "\\b(address line 1|address1|addressline1|street address|streetaddress|street|" +
                "address|direccion|domicilio|calle|strasse|anschrift|adresse|endereco|" +
                "logradouro|rua)\\b",
        ) to IdentityField.ADDRESS_1,
        Regex(
            "\\b(zip|zipcode|zip code|postal code|postalcode|postcode|post code|codigo postal|" +
                "cp|plz|postleitzahl|cep)\\b",
        ) to IdentityField.POSTAL_CODE,
        Regex(
            "\\b(city|town|ciudad|localidad|poblacion|municipio|stadt|ort|wohnort|cidade|" +
                "ville)\\b",
        ) to IdentityField.CITY,
        Regex(
            "\\b(state|province|provincia|region|bundesland|estado|county|" +
                "comunidad autonoma)\\b",
        ) to IdentityField.STATE,
        Regex("\\b(country|pais|land|pays)\\b") to IdentityField.COUNTRY,
    )

    /**
     * Classifies one node: the [IdentityField] it is, or null when an Identity item has nothing
     * to do with it.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun classify(signals: FieldSignals): IdentityField? {
        if (!signals.isInputLike) return null
        val hints = signals.autofillHints.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (hints.any { hint -> FOREIGN_HINT_PREFIXES.any { hint.startsWith(it) } }) return null
        if (CloudFieldClassifier.isPasswordInputType(signals.inputType)) return null
        if (signals.isEmailInputType()) return null
        val htmlType = signals.htmlAttributes["type"]?.trim()?.lowercase()
        if (htmlType in setOf("password", "email", "tel", "hidden", "checkbox", "radio")) {
            return null
        }

        val autocomplete = signals.htmlAttributes["autocomplete"]
            ?.trim()
            ?.lowercase()
            ?.split(Regex("\\s+"))
            ?.lastOrNull()
            .orEmpty()
        if (FOREIGN_AUTOCOMPLETE_PREFIXES.any { autocomplete.startsWith(it) }) return null

        // Hints that are not Android / androidx constants (e.g. "passportNumber") are wording.
        val sources = signals.meaningSources() +
            signals.autofillHints.filter { it.trim().lowercase() !in HINT_FIELDS }
        val texts = sources.map(::normalize).filter { it.isNotEmpty() }
        if (texts.any { PASSWORD_OR_CODE.containsMatchIn(it) }) return null

        AUTOCOMPLETE_FIELDS[autocomplete]?.let { return it }
        hints.firstNotNullOfOrNull { HINT_FIELDS[it] }?.let { return it }

        when (val verdict = documentVerdict(texts)) {
            is DocumentVerdict.Field -> return verdict.field
            DocumentVerdict.NotOurs -> return null
            DocumentVerdict.Silent -> Unit
        }
        return nameOrAddressFieldOrNull(texts)
    }

    /** True when [classify] would make this a field that offers Identity items. */
    fun isDocumentField(signals: FieldSignals): Boolean = classify(signals)?.isDocument == true

    /** What the document wording says about a field. */
    private sealed class DocumentVerdict {
        /** It is this ID-document (or document-expiry) field. */
        data class Field(val field: IdentityField) : DocumentVerdict()

        /** It is something else (a card's expiry date). */
        data object NotOurs : DocumentVerdict()

        /** It does not talk about documents at all. */
        data object Silent : DocumentVerdict()
    }

    @Suppress("ReturnCount")
    private fun documentVerdict(texts: List<String>): DocumentVerdict {
        fun any(regex: Regex): Boolean = texts.any { regex.containsMatchIn(it) }
        val hasCardContext = any(CARD_CONTEXT)

        if (any(ISSUING_COUNTRY)) return DocumentVerdict.Field(IdentityField.ISSUING_COUNTRY)
        if (any(EXPIRY)) {
            return when {
                hasCardContext -> DocumentVerdict.NotOurs
                any(DOCUMENT_CONTEXT) -> DocumentVerdict.Field(IdentityField.VALID_UNTIL)
                else -> DocumentVerdict.Field(IdentityField.VALID_UNTIL_ON_PAGE)
            }
        }
        if (any(SUPPORT_NUMBER) || texts.any { it == CAN_ALONE }) {
            return DocumentVerdict.Field(IdentityField.SUPPORT_NUMBER)
        }
        DOCUMENT_RULES
            .firstOrNull { (regex, _) -> any(regex) }
            ?.let { return DocumentVerdict.Field(it.second) }

        // The generic wordings: never on a card form, never with user / account / ... wording.
        if (hasCardContext || any(FOREIGN_ID_CONTEXT)) return DocumentVerdict.Silent
        if (any(NATIONAL_ID)) return DocumentVerdict.Field(IdentityField.NATIONAL_ID)
        if (any(DOCUMENT_NUMBER)) return DocumentVerdict.Field(IdentityField.DOCUMENT_NUMBER)
        return DocumentVerdict.Silent
    }

    @Suppress("ReturnCount")
    private fun nameOrAddressFieldOrNull(texts: List<String>): IdentityField? {
        if (texts.any { NOT_A_NAME_OR_ADDRESS.containsMatchIn(it) }) return null
        if (texts.any { SPLIT_SURNAME.containsMatchIn(it) }) return null
        if (texts.any { it == "name" }) return IdentityField.FULL_NAME
        return NAME_AND_ADDRESS_RULES
            .firstOrNull { (regex, _) -> texts.any { regex.containsMatchIn(it) } }
            ?.second
            ?.takeUnless { field ->
                field == IdentityField.COUNTRY && texts.any { NOT_A_COUNTRY.containsMatchIn(it) }
            }
    }

    /**
     * Lower case, accents and "ß" folded, camel case split, every run of other characters one
     * space; "ID card" / "identity card" become one word so they never read as a payment card.
     * "Número de soporte" → "numero de soporte", "passportNumber" → "passport number".
     */
    fun normalize(raw: String): String {
        val split = raw.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
        val folded = Normalizer
            .normalize(split.replace("ß", "ss").replace("ẞ", "SS"), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return folded
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\bidentity card\\b"), "identitycard")
            .replace(Regex("\\bid card\\b"), "idcard")
    }

    private val FieldSignals.isInputLike: Boolean
        get() = htmlTag?.lowercase() in setOf("input", "select") ||
            (htmlTag == null && inputType != 0)

    private fun FieldSignals.isEmailInputType(): Boolean {
        if (inputType and InputTypeBits.MASK_CLASS != InputTypeBits.CLASS_TEXT) return false
        val variation = inputType and InputTypeBits.MASK_VARIATION
        return variation == InputTypeBits.TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputTypeBits.TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    private fun FieldSignals.meaningSources(): List<String> = buildList {
        idEntry?.let(::add)
        hint?.let(::add)
        MEANINGFUL_HTML_ATTRIBUTES.forEach { key -> htmlAttributes[key]?.let(::add) }
    }
}
