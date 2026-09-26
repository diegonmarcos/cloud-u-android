package com.diegonmarcos.cloudlib.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The OAuth 2.0 device grant, driven end to end (#573, shared by #587): ask
 * for a code, show it, poll until approved, read the identity the token
 * proves. ONE loop for GitHub, Google and whatever else speaks RFC 8628 — the
 * provider is a [SignIn.Provider] read off the declaration and nothing here
 * knows which one it is.
 *
 * Pure of UI: it reports typed [Phase]s and the host words them. It suspends
 * on the caller's coroutine, so cancelling that coroutine (the dialog closing)
 * stops the polling at once. The access token is returned to the caller and
 * nowhere else — never stored, never logged.
 */
object DeviceGrant {

    sealed class Phase {
        object Asking : Phase()
        /** The code is on screen; [pending] is the provider's last word while waiting. */
        data class Prompt(val code: SignIn.DeviceCode, val pending: String = "") : Phase()
        data class Approved(val identity: String) : Phase()
        data class Failed(val message: String) : Phase()
        object Expired : Phase()
    }

    data class Approval(val provider: SignIn.Provider, val accessToken: String, val identity: String)

    /**
     * @param report every phase, in order, on the caller's thread.
     * @param openUrl opens the verification page for the owner; a failure to
     *   open is swallowed — the code stays on screen and selectable.
     * @return the approval, or null after the failure has been reported.
     */
    suspend fun run(p: SignIn.Provider, report: (Phase) -> Unit, openUrl: (String) -> Unit): Approval? {
        report(Phase.Asking)
        val code = withContext(Dispatchers.IO) { SignIn.requestDeviceCode(p) }
            .getOrElse { report(Phase.Failed(it.message ?: it.javaClass.simpleName)); return null }
        report(Phase.Prompt(code))
        runCatching { openUrl(code.verificationUri) }

        val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
        var interval = code.intervalSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            when (val step = withContext(Dispatchers.IO) { SignIn.pollForToken(p, code.deviceCode) }) {
                is SignIn.Step.Token -> {
                    val who = withContext(Dispatchers.IO) { SignIn.identity(p, step.accessToken) }.orEmpty()
                    report(Phase.Approved(who))
                    return Approval(p, step.accessToken, who)
                }
                is SignIn.Step.Failed -> { report(Phase.Failed(step.message)); return null }
                is SignIn.Step.Pending -> {
                    // slow_down means the provider wants a longer gap, and
                    // ignoring it gets the whole flow rate-limited.
                    if (step.slowDown) interval += 5_000L
                    report(Phase.Prompt(code, step.message))
                }
            }
        }
        report(Phase.Expired)
        return null
    }
}
