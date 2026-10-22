// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocomplete

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock

/**
 * #1077/#1103: asks for the phone's screen lock (PIN, pattern, fingerprint) before the Secrets page shows or pastes a
 * value. A keyboard has no window of its own to host the system prompt, so this transparent activity does it and grants
 * [AutocompleteUnlock] for a minute on success. A phone with no screen lock cannot unlock: the Secrets stay masked.
 */
class AutocompleteUnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = if (km.isDeviceSecure) km.createConfirmDeviceCredentialIntent("Autocomplete", "Unlock to show and paste secrets") else null
        if (intent == null) { finish(); return }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST && resultCode == RESULT_OK) AutocompleteUnlock.grant(SystemClock.elapsedRealtime())
        finish()
    }

    companion object {
        private const val REQUEST = 1077
        fun start(context: Context) {
            context.startActivity(Intent(context, AutocompleteUnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
