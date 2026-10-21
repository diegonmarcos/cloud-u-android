package com.diegonmarcos.superapp.autofill

/**
 * #1077: every autocomplete field the fleet deploys, as ONE declaration the keyboard's
 * Configs > Autocomplete page and Autocomplete list read.
 *
 *  - Cloud Account's autofill store is NOT copied here: its tables and columns come from [AutofillSot]
 *    (the provider's own declaration), so a new column shows up in the keyboard with no edit.
 *  - Cloud Account's identity hand-off and Cloud Vault's autofill fields live in the vault app, which
 *    this library cannot compile against. [Vault] and [IDENTITY_HANDOFF] therefore name them here, and
 *    ac_cloud-keyboard/test/test-keyboard-autocomplete-schemas.sh fails the build when either list differs
 *    from the vault's IdentityField / AddIdentityRequest / AutofillSaveItem or the account's VaultIdentityHandoff.
 */
data class SchemaField(val key: String, val label: String, val sensitive: Boolean)

data class SchemaTable(
    val id: String,
    /** The app that owns the data. */
    val owner: String,
    /** Where it lives: a content URI, an intent contract or a vault item type. */
    val store: String,
    val title: String,
    val fields: List<SchemaField>,
    /** True when the keyboard can read VALUES from it (Cloud Account's provider); the vault's are never read here. */
    val readable: Boolean,
)

object AutofillSchemas {
    const val ACCOUNT = "Cloud Account"
    const val VAULT = "Cloud Vault"

    /** Columns of the account store that hold personal data to mask until unlocked. Everything else is plain contact data. */
    val SENSITIVE_ACCOUNT_COLUMNS = setOf("bday", AutofillSot.S_TEXT)

    private val TITLES = mapOf(
        AutofillSot.PATH_PROFILES to "Profiles", AutofillSot.PATH_ADDRESSES to "Addresses", AutofillSot.PATH_CONTACTS to "Contacts",
        AutofillSot.PATH_RULES to "Site rules", AutofillSot.PATH_SNIPPETS to "Snippets",
    )

    fun label(column: String): String = column.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }

    /** The account's autofill store, one table per provider path, columns straight from [AutofillSot]. */
    fun account(): List<SchemaTable> = AutofillSot.PATHS.map { path ->
        SchemaTable(
            id = "account.$path", owner = ACCOUNT, store = "content://${AutofillSot.AUTHORITY}/$path", title = TITLES[path] ?: label(path),
            fields = AutofillSot.columns(path).filterNot { it == AutofillSot.ID || it == AutofillSot.UPDATED_AT }
                .map { SchemaField(it, label(it), it in SENSITIVE_ACCOUNT_COLUMNS) },
            readable = true,
        )
    }

    /** The extras Cloud Account's Import hands to Cloud Vault's add-identity entry point (suffix of com.diegonmarcos.cloudvault.extra.*). Never stored in the account. */
    val IDENTITY_HANDOFF = listOf("ID_TYPE", "ID_NUMBER", "ID_SUPPORT", "ID_ISSUING_COUNTRY", "ID_VALID_UNTIL", "ID_ISSUED",
        "FIRST_NAME", "MIDDLE_NAME", "LAST_NAME")

    /** Cloud Vault's autofill: the Bitwarden item types it fills and saves, and the Identity form fields it recognises. */
    object Vault {
        val LOGIN = listOf("username", "password", "uri")
        val CARD = listOf("cardholderName", "number", "expirationMonth", "expirationYear", "securityCode", "brand")
        val IDENTITY_SAVE = listOf("documentType", "documentNumber", "supportNumber", "issuingCountry", "validUntil", "issueDate",
            "firstName", "middleName", "lastName")
        /** IdentityField constants, in declaration order. */
        val IDENTITY_FIELDS = listOf("DNI", "NIE", "NIF", "PERSONALAUSWEIS", "CPF", "RG", "RESIDENCE_PERMIT", "NATIONAL_ID",
            "DOCUMENT_NUMBER", "PASSPORT_NUMBER", "LICENSE_NUMBER", "SSN", "SUPPORT_NUMBER", "VALID_UNTIL", "ISSUING_COUNTRY",
            "VALID_UNTIL_ON_PAGE", "FIRST_NAME", "MIDDLE_NAME", "LAST_NAME", "FULL_NAME", "ADDRESS_1", "ADDRESS_2", "ADDRESS_3",
            "CITY", "STATE", "POSTAL_CODE", "COUNTRY")
    }

    /** All the vault's fields are secrets: they are listed so the user sees what exists, and are never read by the keyboard. */
    private fun secret(keys: List<String>) = keys.map { SchemaField(it, label(it.lowercase()), sensitive = true) }

    fun identityHandoff() = SchemaTable("account.identity-handoff", ACCOUNT, "intent com.diegonmarcos.cloudvault.action.ADD_IDENTITY",
        "Identity hand-off", secret(IDENTITY_HANDOFF), readable = false)

    fun vault(): List<SchemaTable> = listOf(
        SchemaTable("vault.login", VAULT, "Android Autofill, item type Login", "Logins", secret(Vault.LOGIN), false),
        SchemaTable("vault.card", VAULT, "Android Autofill, item type Card", "Cards", secret(Vault.CARD), false),
        SchemaTable("vault.identity", VAULT, "Android Autofill, item type Identity", "Identity items", secret(Vault.IDENTITY_SAVE), false),
        SchemaTable("vault.identity-fields", VAULT, "IdentityField form fields", "Identity form fields", secret(Vault.IDENTITY_FIELDS), false),
    )

    fun all(): List<SchemaTable> = account() + identityHandoff() + vault()
}
