package com.x8bit.bitwarden.data.autofill.cloud

/**
 * A form field Cloud Vault can fill from a Bitwarden Identity item.
 *
 * [isDocument] fields are ID-document fields: focusing one is what makes the vault offer its
 * Identity items, and they carry the suggestion's presentation. The others (names, address, an
 * expiry date the page does not tie to a document) are fill-only: they are written only when the
 * user picks an Identity item on a document field of the same screen, and never show a
 * suggestion of their own, so the browser and keyboard tiers keep owning plain name and address
 * forms (docs/autofill-tiers-contract.md, rule 8).
 */
enum class IdentityField(val isDocument: Boolean) {
    /** Spanish national ID (DNI). */
    DNI(isDocument = true),

    /** Spanish foreigner ID number (NIE). */
    NIE(isDocument = true),

    /** Spanish tax ID (NIF): the DNI or NIE number for a person. */
    NIF(isDocument = true),

    /** German ID card (Personalausweis / Ausweisnummer). */
    PERSONALAUSWEIS(isDocument = true),

    /** Brazilian individual taxpayer number (CPF). */
    CPF(isDocument = true),

    /** Brazilian identity card (RG / identidade). */
    RG(isDocument = true),

    /** A residence permit (TIE, Aufenthaltstitel). */
    RESIDENCE_PERMIT(isDocument = true),

    /** "National ID", "ID number", "identity card": a national ID of any country. */
    NATIONAL_ID(isDocument = true),

    /** "Document number": whichever ID document the picked item holds. */
    DOCUMENT_NUMBER(isDocument = true),

    /** Passport number. */
    PASSPORT_NUMBER(isDocument = true),

    /** Driving licence number. */
    LICENSE_NUMBER(isDocument = true),

    /** Social security number. */
    SSN(isDocument = true),

    /** The document's support / serial number (ES "número de soporte", DE CAN). */
    SUPPORT_NUMBER(isDocument = true),

    /** The document's expiry date, on a field that names the document. */
    VALID_UNTIL(isDocument = true),

    /** The country that issued the document. */
    ISSUING_COUNTRY(isDocument = true),

    /** An expiry date that does not name a document: filled only next to a document field. */
    VALID_UNTIL_ON_PAGE(isDocument = false),
    FIRST_NAME(isDocument = false),
    MIDDLE_NAME(isDocument = false),
    LAST_NAME(isDocument = false),
    FULL_NAME(isDocument = false),
    ADDRESS_1(isDocument = false),
    ADDRESS_2(isDocument = false),
    ADDRESS_3(isDocument = false),
    CITY(isDocument = false),
    STATE(isDocument = false),
    POSTAL_CODE(isDocument = false),
    COUNTRY(isDocument = false),
}

/**
 * The plain values of one Identity item, with no SDK type, so the field mapping is pinned by JVM
 * tests. [customFields] are the item's text and hidden custom fields as (name, value).
 *
 * A secret holder: [toString] never prints a value.
 */
@Suppress("LongParameterList")
class IdentityValues(
    val firstName: String = "",
    val middleName: String = "",
    val lastName: String = "",
    val address1: String = "",
    val address2: String = "",
    val address3: String = "",
    val city: String = "",
    val state: String = "",
    val postalCode: String = "",
    val country: String = "",
    val ssn: String = "",
    val passportNumber: String = "",
    val licenseNumber: String = "",
    val customFields: List<Pair<String, String>> = emptyList(),
) {
    override fun toString(): String = "IdentityValues(redacted)"
}

/**
 * Which value of an Identity item fills which [IdentityField]. Bitwarden's Identity type has
 * standard fields for the passport, driving licence and social security numbers, names and
 * address; every other document detail lives in a custom field, matched by name (case, accents
 * and punctuation ignored). The names are the ones Cloud Vault's add-identity screen writes
 * ([IdentityDocumentKind.customFieldName], [CUSTOM_SUPPORT_NUMBER], ...), plus the obvious
 * spellings a user may have typed by hand. The table is in docs/autofill-tiers-contract.md.
 */
object IdentityFieldMapping {

    const val CUSTOM_DOCUMENT_TYPE: String = "Document type"
    const val CUSTOM_SUPPORT_NUMBER: String = "Support number"
    const val CUSTOM_VALID_UNTIL: String = "Valid until"
    const val CUSTOM_ISSUING_COUNTRY: String = "Issuing country"
    const val CUSTOM_ISSUED: String = "Issued"

    private val DNI_NAMES = listOf("DNI", "DNI number", "Número de DNI", "Documento nacional de identidad")
    private val NIE_NAMES = listOf("NIE", "NIE number", "Número de NIE")
    private val NIF_NAMES = listOf("NIF", "NIF number")
    private val PERSONALAUSWEIS_NAMES = listOf(
        "Personalausweis", "Personalausweisnummer", "Ausweisnummer", "Ausweis", "ID card number",
    )
    private val CPF_NAMES = listOf("CPF")
    private val RG_NAMES = listOf("RG", "Identidade", "Registro Geral", "Carteira de identidade")
    private val RESIDENCE_NAMES = listOf(
        "Residence permit", "TIE", "Aufenthaltstitel", "Tarjeta de residencia", "Residence card",
    )
    private val NATIONAL_ID_NAMES = listOf(
        "National ID", "National ID number", "ID number", "Identity number", "ID card number",
    )
    private val DOCUMENT_NUMBER_NAMES = listOf(
        "Document number", "Número de documento", "Documento", "ID number",
    )
    private val PASSPORT_NAMES = listOf(
        "Passport", "Passport number", "Pasaporte", "Passaporte", "Reisepass", "Reisepassnummer",
        "Passnummer",
    )
    private val LICENSE_NAMES = listOf(
        "Driver license", "Driving licence", "Driving license", "Permiso de conducir",
        "Führerschein", "CNH",
    )
    private val SSN_NAMES = listOf(
        "SSN", "Social security number", "Número de la Seguridad Social",
        "Sozialversicherungsnummer",
    )
    private val SUPPORT_NAMES = listOf(
        CUSTOM_SUPPORT_NUMBER, "Número de soporte", "Soporte", "IDESP", "CAN",
        "Card access number", "Zugangsnummer",
    )
    private val VALID_UNTIL_NAMES = listOf(
        CUSTOM_VALID_UNTIL, "Expiry date", "Expiration date", "Expires", "Fecha de caducidad",
        "Caducidad", "Válido hasta", "Gültig bis", "Ablaufdatum", "Validade", "Data de validade",
    )
    private val ISSUING_COUNTRY_NAMES = listOf(
        CUSTOM_ISSUING_COUNTRY, "Country of issue", "País de expedición", "País emisor",
        "Ausstellungsland", "País emissor",
    )

    /**
     * The value [field] is filled with from [values], or null when the item has none (the field
     * is then left alone).
     */
    @Suppress("CyclomaticComplexMethod")
    fun valueFor(field: IdentityField, values: IdentityValues): String? {
        fun custom(names: List<String>): String? = values.customValue(names)
        fun standard(value: String): String? = value.trim().takeIf { it.isNotEmpty() }
        fun primaryNational(): String? = custom(DNI_NAMES)
            ?: custom(NIE_NAMES)
            ?: custom(PERSONALAUSWEIS_NAMES)
            ?: custom(CPF_NAMES)
            ?: custom(RG_NAMES)
            ?: custom(RESIDENCE_NAMES)

        return when (field) {
            IdentityField.DNI -> custom(DNI_NAMES) ?: custom(NIE_NAMES) ?: custom(NIF_NAMES)
            IdentityField.NIE -> custom(NIE_NAMES)
            IdentityField.NIF -> custom(NIF_NAMES) ?: custom(DNI_NAMES) ?: custom(NIE_NAMES)
            IdentityField.PERSONALAUSWEIS -> custom(PERSONALAUSWEIS_NAMES)
            IdentityField.CPF -> custom(CPF_NAMES)
            IdentityField.RG -> custom(RG_NAMES)
            IdentityField.RESIDENCE_PERMIT -> custom(RESIDENCE_NAMES)
            IdentityField.NATIONAL_ID -> custom(NATIONAL_ID_NAMES) ?: primaryNational()
            IdentityField.DOCUMENT_NUMBER -> custom(DOCUMENT_NUMBER_NAMES)
                ?: primaryNational()
                ?: standard(values.passportNumber)
                ?: standard(values.licenseNumber)

            IdentityField.PASSPORT_NUMBER -> standard(values.passportNumber)
                ?: custom(PASSPORT_NAMES)

            IdentityField.LICENSE_NUMBER -> standard(values.licenseNumber)
                ?: custom(LICENSE_NAMES)

            IdentityField.SSN -> standard(values.ssn) ?: custom(SSN_NAMES)
            IdentityField.SUPPORT_NUMBER -> custom(SUPPORT_NAMES)
            IdentityField.VALID_UNTIL,
            IdentityField.VALID_UNTIL_ON_PAGE,
                -> custom(VALID_UNTIL_NAMES)

            IdentityField.ISSUING_COUNTRY -> custom(ISSUING_COUNTRY_NAMES)
            IdentityField.FIRST_NAME -> standard(values.firstName)
            IdentityField.MIDDLE_NAME -> standard(values.middleName)
            IdentityField.LAST_NAME -> standard(values.lastName)
            IdentityField.FULL_NAME -> listOf(values.firstName, values.middleName, values.lastName)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(separator = " ")
                .takeIf { it.isNotEmpty() }

            IdentityField.ADDRESS_1 -> standard(values.address1)
            IdentityField.ADDRESS_2 -> standard(values.address2)
            IdentityField.ADDRESS_3 -> standard(values.address3)
            IdentityField.CITY -> standard(values.city)
            IdentityField.STATE -> standard(values.state)
            IdentityField.POSTAL_CODE -> standard(values.postalCode)
            IdentityField.COUNTRY -> standard(values.country)
        }
    }

    private fun IdentityValues.customValue(names: List<String>): String? {
        val wanted = names.map { it.fieldNameKey() }
        return wanted.firstNotNullOfOrNull { key ->
            customFields
                .firstOrNull { (name, value) -> name.fieldNameKey() == key && value.isNotBlank() }
                ?.second
                ?.trim()
        }
    }

    /** "Número de soporte" and "numero_de_soporte" are the same custom field. */
    internal fun String.fieldNameKey(): String =
        IdentityFieldClassifier.normalize(this).replace(" ", "")
}

/**
 * The kind of ID document an add-identity request carries, read from its free-text type label
 * ("DNI", "Passport", "Reisepass", "Personalausweis", ...), and where its number is stored in the
 * Identity item: the standard passport / licence / SSN field, or a custom field named
 * [customFieldName].
 */
enum class IdentityDocumentKind(val customFieldName: String?) {
    PASSPORT(customFieldName = null),
    DRIVING_LICENCE(customFieldName = null),
    SSN(customFieldName = null),
    DNI(customFieldName = "DNI"),
    NIE(customFieldName = "NIE"),
    NIF(customFieldName = "NIF"),
    TIE(customFieldName = "TIE"),
    PERSONALAUSWEIS(customFieldName = "Personalausweis"),
    RESIDENCE_PERMIT(customFieldName = "Residence permit"),
    CPF(customFieldName = "CPF"),
    RG(customFieldName = "RG"),
    OTHER(customFieldName = "ID number"),
    ;

    companion object {
        private val RULES: List<Pair<Regex, IdentityDocumentKind>> = listOf(
            Regex("\\b(passport|pasaporte|passaporte|reisepass|pass)\\b") to PASSPORT,
            Regex(
                "\\b(driver|drivers|driving|licen[cs]e|fuhrerschein|fuehrerschein|cnh|conducir)\\b",
            ) to DRIVING_LICENCE,
            Regex("\\b(ssn|social security)\\b") to SSN,
            Regex("\\bdni\\b") to DNI,
            Regex("\\bnie\\b") to NIE,
            Regex("\\bnif\\b") to NIF,
            Regex("\\btie\\b") to TIE,
            Regex("\\b(personalausweis|ausweis)") to PERSONALAUSWEIS,
            Regex("\\b(residence|aufenthalt|residencia)") to RESIDENCE_PERMIT,
            Regex("\\bcpf\\b") to CPF,
            Regex("\\b(rg|identidade|registro geral)\\b") to RG,
        )

        /** The kind a type label names; [OTHER] when it names none Cloud Vault knows. */
        fun fromLabel(label: String?): IdentityDocumentKind {
            val normalized = IdentityFieldClassifier.normalize(label.orEmpty())
            return RULES.firstOrNull { (regex, _) -> regex.containsMatchIn(normalized) }?.second
                ?: OTHER
        }
    }
}
