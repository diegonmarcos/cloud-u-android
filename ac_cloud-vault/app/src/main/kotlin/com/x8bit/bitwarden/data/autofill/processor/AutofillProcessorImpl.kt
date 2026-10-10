package com.x8bit.bitwarden.data.autofill.processor

import android.os.CancellationSignal
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.policies.PolicyType
import com.x8bit.bitwarden.data.autofill.builder.FillResponseBuilder
import com.x8bit.bitwarden.data.autofill.builder.FilledDataBuilder
import com.x8bit.bitwarden.data.autofill.builder.SaveInfoBuilder
import com.x8bit.bitwarden.data.autofill.cloud.AutofillUriResolver
import com.x8bit.bitwarden.data.autofill.model.AutofillAppInfo
import com.x8bit.bitwarden.data.autofill.model.AutofillPartition
import com.x8bit.bitwarden.data.autofill.model.AutofillRequest
import com.x8bit.bitwarden.data.autofill.model.AutofillSaveItem
import com.x8bit.bitwarden.data.autofill.parser.AutofillParser
import com.x8bit.bitwarden.data.autofill.util.createAutofillSavedItemIntentSender
import com.x8bit.bitwarden.data.autofill.util.toAutofillSaveItem
import com.x8bit.bitwarden.data.platform.manager.PolicyManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The default implementation of [AutofillProcessor]. Its purpose is to handle autofill related
 * processing.
 */
@Suppress("LongParameterList")
class AutofillProcessorImpl(
    dispatcherManager: DispatcherManager,
    private val policyManager: PolicyManager,
    private val filledDataBuilder: FilledDataBuilder,
    private val fillResponseBuilder: FillResponseBuilder,
    private val parser: AutofillParser,
    private val saveInfoBuilder: SaveInfoBuilder,
    private val settingsRepository: SettingsRepository,
    private val uriResolver: AutofillUriResolver = AutofillUriResolver.Passthrough,
) : AutofillProcessor {

    /**
     * The coroutine scope for launching asynchronous operations.
     */
    private val scope: CoroutineScope = CoroutineScope(dispatcherManager.unconfined)

    /**
     * The job being used to process the fill request.
     */
    private var job: Job = Job().apply { complete() }

    override fun processFillRequest(
        autofillAppInfo: AutofillAppInfo,
        cancellationSignal: CancellationSignal,
        fillCallback: FillCallback,
        request: FillRequest,
    ) {
        Timber.d("Begin processing Autofill fill request -- ${request.id}")
        // Set the listener so that any long running work is cancelled when it is no longer needed.
        cancellationSignal.setOnCancelListener {
            Timber.d("Autofill job cancelled")
            job.cancel()
        }
        // Process the OS data and handle invoking the callback with the result.
        job.cancel()
        job = scope.launch {
            process(
                autofillAppInfo = autofillAppInfo,
                fillCallback = fillCallback,
                fillRequest = request,
            )
        }
    }

    override fun processSaveRequest(
        autofillAppInfo: AutofillAppInfo,
        request: SaveRequest,
        saveCallback: SaveCallback,
    ) {
        if (settingsRepository.isAutofillSavePromptDisabled) {
            saveCallback.onSuccess()
            return
        }

        if (policyManager.getActivePolicies(PolicyType.ORGANIZATION_DATA_OWNERSHIP).any()) {
            saveCallback.onSuccess()
            return
        }

        val contexts = request.fillContexts
        contexts
            .lastOrNull()
            ?.structure
            ?.let { assistStructure ->
                val autofillRequest = parser.parse(
                    assistStructure = assistStructure,
                    autofillAppInfo = autofillAppInfo,
                )

                when (autofillRequest) {
                    is AutofillRequest.Fillable -> if (
                        // Cloud Vault: ID documents are never saved from a page.
                        autofillRequest.partition is AutofillPartition.Identity
                    ) {
                        saveCallback.onSuccess()
                    } else {
                        val saveItem = autofillRequest
                            .resolveUriForSave()
                            .toAutofillSaveItem()
                            .withUsernameFromEarlierScreens(
                                earlierRequests = if (contexts.size > 1) {
                                    contexts.dropLast(1).mapNotNull { context ->
                                        context?.structure?.let {
                                            parser.parse(
                                                assistStructure = it,
                                                autofillAppInfo = autofillAppInfo,
                                            ) as? AutofillRequest.Fillable
                                        }
                                    }
                                } else {
                                    emptyList()
                                },
                            )
                        val intentSender = createAutofillSavedItemIntentSender(
                            autofillAppInfo = autofillAppInfo,
                            autofillSaveItem = saveItem,
                        )

                        saveCallback.onSuccess(intentSender)
                    }

                    AutofillRequest.Unfillable -> saveCallback.onSuccess()
                }
            }
            ?: saveCallback.onSuccess()
    }

    /**
     * Cloud Vault: an app that is not a trusted browser cannot make us save a login under a
     * website it merely claims; the app's own URI is saved instead (see AutofillUriPolicy).
     */
    private fun AutofillRequest.Fillable.resolveUriForSave(): AutofillRequest.Fillable {
        if (uriResolver === AutofillUriResolver.Passthrough) return this
        val resolved = uriResolver.resolveForSave(uri = uri, packageName = packageName)
        return if (resolved == uri) this else copy(uri = resolved)
    }

    /**
     * Cloud Vault: a username-then-password login spans two screens. The first screen's
     * SaveInfo carries FLAG_DELAY_SAVE (SaveInfoBuilderImpl), so the framework hands both screens
     * to this save request; the username typed on the earlier one completes the login here.
     */
    private fun AutofillSaveItem.withUsernameFromEarlierScreens(
        earlierRequests: List<AutofillRequest.Fillable>,
    ): AutofillSaveItem {
        if (earlierRequests.isEmpty() || this !is AutofillSaveItem.Login || username != null) {
            return this
        }
        val earlierUsername = earlierRequests
            .asReversed()
            .firstNotNullOfOrNull { (it.toAutofillSaveItem() as? AutofillSaveItem.Login)?.username }
            ?: return this
        return copy(username = earlierUsername)
    }

    /**
     * Cloud Vault: which URI the vault matches against (a non-browser app's claimed website
     * must be backed by Digital Asset Links; see AutofillUriPolicy).
     */
    private suspend fun AutofillRequest.resolveUriForFill(): AutofillRequest {
        if (this !is AutofillRequest.Fillable || uriResolver === AutofillUriResolver.Passthrough) {
            return this
        }
        val resolved = uriResolver.resolveForFill(uri = uri, packageName = packageName)
        return if (resolved == uri) this else copy(uri = resolved)
    }

    /**
     * Process the [fillRequest] and invoke the [FillCallback] with the response.
     */
    private suspend fun process(
        autofillAppInfo: AutofillAppInfo,
        fillCallback: FillCallback,
        fillRequest: FillRequest,
    ) {
        // Parse the OS data into an [AutofillRequest] for easier processing.
        val autofillRequest = parser
            .parse(
                autofillAppInfo = autofillAppInfo,
                fillRequest = fillRequest,
            )
            .resolveUriForFill()
        when (autofillRequest) {
            is AutofillRequest.Fillable -> {
                Timber.d("Autofill request is Fillable -- ${fillRequest.id}")
                // Fulfill the [autofillRequest].
                val filledData = filledDataBuilder.build(
                    autofillRequest = autofillRequest,
                )
                val saveInfo = saveInfoBuilder.build(
                    autofillPartition = autofillRequest.partition,
                    fillRequest = fillRequest,
                    packageName = autofillRequest.packageName,
                )

                // Load the filledData and saveInfo into a FillResponse.
                val response = fillResponseBuilder.build(
                    autofillAppInfo = autofillAppInfo,
                    filledData = filledData,
                    saveInfo = saveInfo,
                )

                @Suppress("TooGenericExceptionCaught")
                try {
                    Timber.d("Autofill request success: Fillable -- ${fillRequest.id}")
                    fillCallback.onSuccess(response)
                } catch (e: RuntimeException) {
                    // This is to catch any TransactionTooLargeExceptions that could occur here.
                    // These exceptions get wrapped as a RuntimeException.
                    Timber.e(e, "Autofill Error")
                }
            }

            AutofillRequest.Unfillable -> {
                // If we are unable to fulfill the request, we should invoke the callback
                // with null. This effectively disables autofill for this view set and
                // allows the [AutofillService] to be unbound.
                Timber.d("Autofill request success: Unfillable -- ${fillRequest.id}")
                fillCallback.onSuccess(null)
            }
        }
    }
}
