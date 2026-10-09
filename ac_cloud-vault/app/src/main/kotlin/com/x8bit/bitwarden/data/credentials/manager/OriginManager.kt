package com.x8bit.bitwarden.data.credentials.manager

import androidx.credentials.provider.CallingAppInfo
import com.x8bit.bitwarden.data.credentials.model.ValidateOriginResult

/**
 * Responsible for managing FIDO2 origin validation.
 */
interface OriginManager {

    /**
     * Validates the origin of a calling app.
     *
     * @param relyingPartyId The ID of the relying party that sent the request.
     * @param callingAppInfo The calling app info.
     *
     * @return The result of the validation.
     */
    suspend fun validateOrigin(
        relyingPartyId: String,
        callingAppInfo: CallingAppInfo,
    ): ValidateOriginResult

    /**
     * Resolves the relying party ID of a request that carries none (`rp.id` / `rpId` are optional
     * in WebAuthn, the default being the effective domain of the origin).
     *
     * Only a browser on the privileged-app allow lists can vouch for a web origin, so this
     * returns the host of `CallingAppInfo.getOrigin(allowList)` for those, and null for native
     * apps or browsers that are not allowed.
     */
    suspend fun resolveRelyingPartyIdFromOrigin(callingAppInfo: CallingAppInfo): String?
}
