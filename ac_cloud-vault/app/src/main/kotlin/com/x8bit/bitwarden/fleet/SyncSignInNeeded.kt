package com.x8bit.bitwarden.fleet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Set when a background refresh/sync was refused by the server or its gate (401/403/invalid_grant). The vault stays
 * open and usable; the app only shows a non-blocking "Sync needs sign-in" notice. Cleared by a successful refresh.
 */
object SyncSignInNeeded {
    private val mutable = MutableStateFlow(false)
    val needed: StateFlow<Boolean> = mutable
    fun set(value: Boolean) {
        mutable.value = value
    }
}
