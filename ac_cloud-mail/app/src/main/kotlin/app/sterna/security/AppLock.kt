package app.sterna.security

import app.sterna.core.data.account.AccountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the app's lock state. When app lock is enabled the UI is hidden behind a
 */
class AppLock(private val store: AccountStore) {
    private val _locked = MutableStateFlow(store.appLockEnabled())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt = 0L

    val isEnabled: Boolean get() = store.appLockEnabled()

    /** Called when the user toggles the setting. Turning it off clears any lock. */
    fun setEnabled(enabled: Boolean) {
        store.setAppLockEnabled(enabled)
        if (!enabled) _locked.value = false
    }

    fun onAppBackgrounded(nowMs: Long) {
        backgroundedAt = nowMs
    }

    fun onAppForegrounded(nowMs: Long) {
        if (store.appLockEnabled() && nowMs - backgroundedAt >= GRACE_MS) {
            _locked.value = true
        }
    }

    fun unlock() {
        _locked.value = false
    }

    private companion object {
        const val GRACE_MS = 10_000L
    }
}
