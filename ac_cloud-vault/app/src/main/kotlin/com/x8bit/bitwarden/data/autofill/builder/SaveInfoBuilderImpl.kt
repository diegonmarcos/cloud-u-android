package com.x8bit.bitwarden.data.autofill.builder

import android.service.autofill.FillRequest
import android.service.autofill.SaveInfo
import com.x8bit.bitwarden.data.autofill.model.AutofillPartition
import com.x8bit.bitwarden.data.autofill.model.AutofillView
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import timber.log.Timber

/**
 * The primary implementation of [SaveInfoBuilder].This is used for converting autofill data into
 * a save info.
 */
class SaveInfoBuilderImpl(
    val settingsRepository: SettingsRepository,
) : SaveInfoBuilder {

    override fun build(
        autofillPartition: AutofillPartition,
        fillRequest: FillRequest,
        packageName: String?,
    ): SaveInfo? {
        Timber.d("Autofill request constructing SaveInfo -- ${fillRequest.id}")
        if (settingsRepository.isAutofillSavePromptDisabled) return null
        // Cloud Vault: a username-only screen (the first half of a two-screen login) keeps its
        // value for the password screen instead of offering a save on its own.
        buildDelayedUsernameSaveInfoOrNull(autofillPartition, fillRequest)?.let { return it }
        // Make sure that the save prompt is possible.
        val canPerformSaveRequest = autofillPartition.canPerformSaveRequest
        if (!canPerformSaveRequest) return null

        // Docs state that password fields cannot be reliably saved
        // in Compat mode since they show as masked values.
        val isInCompatMode = (fillRequest.flags or
            FillRequest.FLAG_COMPATIBILITY_MODE_REQUEST) == fillRequest.flags
        Timber.d("Autofill request isInCompatMode=$isInCompatMode -- ${fillRequest.id}")

        // If login and compat mode, the password might be obfuscated,
        // in which case we should skip the save request.
        return if (autofillPartition is AutofillPartition.Login && isInCompatMode) {
            null
        } else {
            SaveInfo
                .Builder(
                    autofillPartition.saveType,
                    autofillPartition.requiredSaveIds.toTypedArray(),
                )
                .apply {
                    // setOptionalIds will throw an IllegalArgumentException if the array is empty
                    autofillPartition
                        .optionalSaveIds
                        .takeUnless { it.isEmpty() }
                        ?.let { setOptionalIds(it.toTypedArray()) }
                    if (isInCompatMode) setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                }
                .build()
        }
    }
}

/**
 * FLAG_DELAY_SAVE (API 29+): the framework remembers this screen's username and hands it to the
 * save request of the next screen, where the password is typed. Only for a login screen with a
 * username or email field and no password field, outside compatibility mode.
 */
private fun buildDelayedUsernameSaveInfoOrNull(
    autofillPartition: AutofillPartition,
    fillRequest: FillRequest,
): SaveInfo? {
    if (autofillPartition !is AutofillPartition.Login) return null
    if (autofillPartition.views.any { it is AutofillView.Login.Password }) return null
    val usernameIds = autofillPartition
        .views
        .filter { it is AutofillView.Login.Username || it is AutofillView.Login.Email }
        .map { it.data.autofillId }
    if (usernameIds.isEmpty()) return null
    val isInCompatMode = (fillRequest.flags and FillRequest.FLAG_COMPATIBILITY_MODE_REQUEST) != 0
    if (isInCompatMode) return null
    return SaveInfo
        .Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME, usernameIds.toTypedArray())
        .setFlags(SaveInfo.FLAG_DELAY_SAVE)
        .build()
}
