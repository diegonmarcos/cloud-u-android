package com.x8bit.bitwarden.data.autofill.cloud

import android.content.Intent
import android.os.Bundle
import com.x8bit.bitwarden.data.autofill.model.AutofillSaveItem

/**
 * Cloud Vault's "add identity" entry point: the contract another fleet app (Cloud Account's
 * Import) uses to open the vault's new-Identity screen prefilled with one ID document.
 *
 * The caller starts [ACTION] in this package with the [EXTRAS] below. The activity is exported
 * only behind [PERMISSION], a signature permission every app signed with the constellation key
 * holds and no other app can get. The vault reads the extras once, removes them from the intent,
 * hands the document to the main screen in memory ([PendingAddIdentity], never through another
 * intent), unlocks first when it is locked, and saves nothing until the user taps Save. Nothing
 * here is logged.
 */
object AddIdentityRequest {

    /** The action of the add-identity activity. */
    const val ACTION: String = "com.diegonmarcos.cloudvault.action.ADD_IDENTITY"

    /** The signature permission a caller must hold. */
    const val PERMISSION: String = "com.diegonmarcos.cloud.permission.VAULT_ADD_IDENTITY"

    /** The contract version this vault speaks (a caller may send [EXTRA_VERSION]). */
    const val VERSION: Int = 1

    const val EXTRA_VERSION: String = "com.diegonmarcos.cloudvault.extra.VERSION"
    const val EXTRA_TYPE: String = "com.diegonmarcos.cloudvault.extra.ID_TYPE"
    const val EXTRA_NUMBER: String = "com.diegonmarcos.cloudvault.extra.ID_NUMBER"
    const val EXTRA_SUPPORT: String = "com.diegonmarcos.cloudvault.extra.ID_SUPPORT"
    const val EXTRA_ISSUING_COUNTRY: String = "com.diegonmarcos.cloudvault.extra.ID_ISSUING_COUNTRY"
    const val EXTRA_VALID_UNTIL: String = "com.diegonmarcos.cloudvault.extra.ID_VALID_UNTIL"
    const val EXTRA_ISSUED: String = "com.diegonmarcos.cloudvault.extra.ID_ISSUED"
    const val EXTRA_FIRST_NAME: String = "com.diegonmarcos.cloudvault.extra.FIRST_NAME"
    const val EXTRA_MIDDLE_NAME: String = "com.diegonmarcos.cloudvault.extra.MIDDLE_NAME"
    const val EXTRA_LAST_NAME: String = "com.diegonmarcos.cloudvault.extra.LAST_NAME"

    /** Every extra of the contract; all of them are removed after reading. */
    val EXTRAS: List<String> = listOf(
        EXTRA_VERSION,
        EXTRA_TYPE,
        EXTRA_NUMBER,
        EXTRA_SUPPORT,
        EXTRA_ISSUING_COUNTRY,
        EXTRA_VALID_UNTIL,
        EXTRA_ISSUED,
        EXTRA_FIRST_NAME,
        EXTRA_MIDDLE_NAME,
        EXTRA_LAST_NAME,
    )

    /** No field of an ID document is longer; anything longer is cut, not trusted. */
    private const val MAX_VALUE_LENGTH: Int = 128

    /**
     * Reads the document from [intent] and removes every extra from it, whatever the outcome, so
     * the number does not stay in the activity's intent. Null when the request carries neither a
     * number nor a name (nothing to prefill) or its extras cannot be read.
     */
    fun readAndClear(intent: Intent): AutofillSaveItem.Identity? {
        val item = runCatching { read { key -> intent.getStringExtra(key) } }.getOrNull()
        clear(intent)
        return item
    }

    /** Removes every extra from [intent] (the contract's and any other). */
    fun clear(intent: Intent) {
        runCatching {
            EXTRAS.forEach { intent.removeExtra(it) }
            intent.replaceExtras(null as Bundle?)
        }
    }

    /** The document [extra] describes; see [readAndClear]. */
    internal fun read(extra: (String) -> String?): AutofillSaveItem.Identity? {
        fun value(key: String): String? = extra(key)
            ?.trim()
            ?.take(MAX_VALUE_LENGTH)
            ?.takeIf { it.isNotEmpty() }

        val item = AutofillSaveItem.Identity(
            documentType = value(EXTRA_TYPE),
            documentNumber = value(EXTRA_NUMBER),
            supportNumber = value(EXTRA_SUPPORT),
            issuingCountry = value(EXTRA_ISSUING_COUNTRY),
            validUntil = value(EXTRA_VALID_UNTIL),
            issueDate = value(EXTRA_ISSUED),
            firstName = value(EXTRA_FIRST_NAME),
            middleName = value(EXTRA_MIDDLE_NAME),
            lastName = value(EXTRA_LAST_NAME),
        )
        val hasContent = item.documentNumber != null ||
            item.firstName != null ||
            item.lastName != null
        return item.takeIf { hasContent }
    }
}

/**
 * Who may use [AddIdentityRequest.ACTION]. Android itself refuses a caller without
 * [AddIdentityRequest.PERMISSION] (the activity's `android:permission`); this is the second
 * check, in code: on Android 14+ a caller that shares its identity (Cloud Account does) is
 * checked again, and is refused when its uid holds no such permission. A caller that does not
 * share it is known only to the framework, whose check already passed.
 */
object AddIdentityCallerPolicy {

    /**
     * @param launchedFromUid `Activity.getLaunchedFromUid()` (Android 14+), or [unknownUid].
     * @param unknownUid `Process.INVALID_UID`, what Android reports for an unshared identity.
     * @param uidHoldsPermission Whether a package of that uid holds the permission.
     */
    fun isAllowed(
        launchedFromUid: Int,
        unknownUid: Int,
        uidHoldsPermission: (Int) -> Boolean,
    ): Boolean = launchedFromUid == unknownUid || uidHoldsPermission(launchedFromUid)
}

/**
 * The one add-identity request waiting for the main screen, held in memory only: the
 * add-identity activity offers it, `MainViewModel` takes it once when the relay intent arrives.
 */
object PendingAddIdentity {

    /** The extra that marks the intent the add-identity activity starts the main screen with. */
    const val EXTRA_RELAY: String = "com.diegonmarcos.cloudvault.internal.ADD_IDENTITY_RELAY"

    @Volatile
    private var pending: AutofillSaveItem.Identity? = null

    /** Holds [item] until [take]; a newer request replaces an older one. */
    fun offer(item: AutofillSaveItem.Identity) {
        pending = item
    }

    /** The waiting request, once; null afterwards. */
    @Synchronized
    fun take(): AutofillSaveItem.Identity? = pending.also { pending = null }
}

/**
 * The ID document the add-identity activity left for this intent, once: the intent only marks
 * the hand-over ([PendingAddIdentity.EXTRA_RELAY]); the document itself never travels in it.
 */
fun Intent.takeRelayedAddIdentityOrNull(): AutofillSaveItem.Identity? =
    if (getBooleanExtra(PendingAddIdentity.EXTRA_RELAY, false)) {
        removeExtra(PendingAddIdentity.EXTRA_RELAY)
        PendingAddIdentity.take()
    } else {
        null
    }
