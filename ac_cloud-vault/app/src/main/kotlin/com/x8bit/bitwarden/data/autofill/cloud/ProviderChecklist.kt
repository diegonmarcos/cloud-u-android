package com.x8bit.bitwarden.data.autofill.cloud

import com.diegonmarcos.superapp.fleetconfig.CredentialProviderStatus

/**
 * "Is Cloud Vault the phone's autofill and passkey provider?", split into the rows the user can
 * fix one at a time. Shown in the vault's onboarding step and in Settings > Autofill; SuperApp's
 * Permissions row reads the same settings through [CredentialProviderStatus], whose parsing
 * this reuses, so the three places cannot disagree.
 *
 * Pure: the platform reads happen in [ProviderProbe]'s producer (CloudVaultProviderStep.kt);
 * this maps them to green / red / unknown rows and the Android screen that fixes each one.
 * Nothing ever writes a secure setting: every action opens Android's own settings screen.
 */
object ProviderChecklist {

    /** Row colour: green, red, or grey when this device does not let an app read the answer. */
    enum class State { OK, MISSING, UNKNOWN }

    /** Which Android screen fixes a row. */
    enum class Action { NONE, SET_AUTOFILL_SERVICE, CREDENTIAL_PROVIDER_SETTINGS }

    /** One row. [id] is stable for tests and test tags. */
    data class Item(
        val id: String,
        val label: String,
        val state: State,
        val detail: String,
        val action: Action,
    )

    /**
     * What the platform said. Every field is nullable: null means "could not be read here",
     * which becomes [State.UNKNOWN] and is never guessed.
     *
     * @property sdkInt Build.VERSION.SDK_INT.
     * @property autofillSupported AutofillManager.isAutofillSupported().
     * @property ownAutofillSelected AutofillManager.hasEnabledAutofillServices(), which answers
     * only for the calling app: true means Cloud Vault is the selected autofill service.
     * @property autofillSetting Settings.Secure "autofill_service" ("" = none selected).
     * @property credentialServiceEnabled CredentialManager.isEnabledCredentialProviderService()
     * for Cloud Vault's provider (API 34+).
     * @property credentialPrimarySetting Settings.Secure "credential_service_primary".
     * @property credentialListSetting Settings.Secure "credential_service".
     */
    data class ProviderProbe(
        val sdkInt: Int,
        val autofillSupported: Boolean?,
        val ownAutofillSelected: Boolean?,
        val autofillSetting: String?,
        val credentialServiceEnabled: Boolean?,
        val credentialPrimarySetting: String?,
        val credentialListSetting: String?,
    )

    const val ID_AUTOFILL_SUPPORTED: String = "autofill_supported"
    const val ID_AUTOFILL_SERVICE: String = "autofill_service"
    const val ID_CREDENTIAL_ENABLED: String = "credential_provider_enabled"
    const val ID_CREDENTIAL_PREFERRED: String = "credential_provider_preferred"

    /** Credential Manager providers exist from Android 14. */
    const val CREDENTIAL_MANAGER_SDK: Int = 34

    /** The rows for [probe], for the vault installed as [packageName]. */
    @Suppress("LongMethod")
    fun items(
        probe: ProviderProbe,
        packageName: String = CredentialProviderStatus.VAULT_PACKAGE,
    ): List<Item> = buildList {
        val supported = probe.autofillSupported
        add(
            Item(
                id = ID_AUTOFILL_SUPPORTED,
                label = "Autofill available on this device",
                state = supported.toState(),
                detail = when (supported) {
                    true -> "Android's autofill framework is on"
                    false -> "Autofill is unavailable (unsupported or blocked by a device policy)"
                    null -> "Android did not say"
                },
                action = Action.NONE,
            ),
        )

        val selected = CredentialProviderStatus.parseComponents(probe.autofillSetting)
        val autofillState = when {
            probe.ownAutofillSelected == true -> State.OK
            CredentialProviderStatus.autofillState(probe.autofillSetting, packageName) ==
                CredentialProviderStatus.State.DEFAULT -> State.OK

            probe.ownAutofillSelected == false || probe.autofillSetting != null -> State.MISSING
            else -> State.UNKNOWN
        }
        val other = selected.firstOrNull { it.pkg != packageName }?.pkg
        add(
            Item(
                id = ID_AUTOFILL_SERVICE,
                label = "Cloud Vault is the autofill service",
                state = autofillState,
                detail = when {
                    autofillState == State.OK -> "Selected in Settings > Autofill service"
                    other != null -> "Another service is selected: $other"
                    autofillState == State.MISSING -> "No autofill service is selected"
                    else -> "Android did not say which service is selected"
                },
                action = if (supported == false) Action.NONE else Action.SET_AUTOFILL_SERVICE,
            ),
        )

        if (probe.sdkInt < CREDENTIAL_MANAGER_SDK) return@buildList

        val credential = CredentialProviderStatus.credentialState(
            primary = probe.credentialPrimarySetting,
            list = probe.credentialListSetting,
            pkg = packageName,
        )
        val enabledState = when (probe.credentialServiceEnabled) {
            true -> State.OK
            false -> State.MISSING
            null -> when (credential) {
                CredentialProviderStatus.State.DEFAULT,
                CredentialProviderStatus.State.ENABLED,
                    -> State.OK

                CredentialProviderStatus.State.OFF -> State.MISSING
                CredentialProviderStatus.State.UNKNOWN -> State.UNKNOWN
            }
        }
        add(
            Item(
                id = ID_CREDENTIAL_ENABLED,
                label = "Passkeys & passwords provider enabled",
                state = enabledState,
                detail = when (enabledState) {
                    State.OK -> "Cloud Vault is on in Passwords, passkeys & accounts"
                    State.MISSING -> "Turn Cloud Vault on in Passwords, passkeys & accounts"
                    State.UNKNOWN -> "Android did not say"
                },
                action = Action.CREDENTIAL_PROVIDER_SETTINGS,
            ),
        )

        val primary = probe.credentialPrimarySetting
        val preferredState = when {
            primary == null -> State.UNKNOWN
            CredentialProviderStatus.parseComponents(primary).any {
                it.pkg == packageName && it.cls == CredentialProviderStatus.CREDENTIAL_SERVICE
            } -> State.OK

            else -> State.MISSING
        }
        add(
            Item(
                id = ID_CREDENTIAL_PREFERRED,
                label = "Cloud Vault is the preferred provider",
                state = preferredState,
                detail = when (preferredState) {
                    State.OK -> "Preferred service in Passwords, passkeys & accounts"
                    State.MISSING -> "Another provider (or none) is preferred"
                    State.UNKNOWN -> "This Android version does not let apps read the preferred service"
                },
                action = Action.CREDENTIAL_PROVIDER_SETTINGS,
            ),
        )
    }

    /**
     * Done when nothing is red and the autofill service is confirmed. A grey "preferred" row
     * (unreadable on this device) does not hold the user back.
     */
    fun isComplete(items: List<Item>): Boolean =
        items.none { it.state == State.MISSING } &&
            items.any { it.id == ID_AUTOFILL_SERVICE && it.state == State.OK }

    /** The first red row with something to tap, which the setup step's main button opens. */
    fun nextAction(items: List<Item>): Action =
        items.firstOrNull { it.state != State.OK && it.action != Action.NONE }?.action
            ?: Action.NONE

    private fun Boolean?.toState(): State = when (this) {
        true -> State.OK
        false -> State.MISSING
        null -> State.UNKNOWN
    }
}
