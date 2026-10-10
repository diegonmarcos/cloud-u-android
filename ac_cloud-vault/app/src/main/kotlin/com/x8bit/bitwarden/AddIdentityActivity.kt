package com.x8bit.bitwarden

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.bitwarden.annotation.OmitFromCoverage
import com.x8bit.bitwarden.data.autofill.cloud.AddIdentityCallerPolicy
import com.x8bit.bitwarden.data.autofill.cloud.AddIdentityRequest
import com.x8bit.bitwarden.data.autofill.cloud.PendingAddIdentity

/**
 * Cloud Vault's add-identity entry point (see [AddIdentityRequest]): a trampoline with no UI.
 *
 * Exported only behind the signature permission
 * `com.diegonmarcos.cloud.permission.VAULT_ADD_IDENTITY` (AndroidManifest.xml), so only fleet
 * apps signed with the constellation key can start it. It reads the ID document from the extras,
 * removes them from its intent, hands the document to [MainActivity] in memory and finishes.
 * [MainActivity] opens the new-Identity screen prefilled, after the unlock screen when the vault
 * is locked; the item is saved only when the user taps Save. Nothing is logged.
 */
@OmitFromCoverage
class AddIdentityActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val currentIntent = intent
        if (savedInstanceState != null || currentIntent == null) {
            currentIntent?.let(AddIdentityRequest::clear)
            finish()
            return
        }
        val item = if (isCallerAllowed()) {
            AddIdentityRequest.readAndClear(currentIntent)
        } else {
            AddIdentityRequest.clear(currentIntent)
            null
        }
        setIntent(currentIntent)
        if (item == null) {
            setResult(RESULT_CANCELED)
        } else {
            PendingAddIdentity.offer(item)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(PendingAddIdentity.EXTRA_RELAY, true)
                    // The vault's own task: saving there ends with finishAndRemoveTask(), which
                    // must close the vault, not the caller's task.
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
            setResult(RESULT_OK)
        }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        // Single use: a second request starts a new instance; this one only drops the extras.
        AddIdentityRequest.clear(intent)
        super.onNewIntent(intent)
    }

    private fun isCallerAllowed(): Boolean {
        val launchedFromUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            launchedFromUid
        } else {
            Process.INVALID_UID
        }
        return AddIdentityCallerPolicy.isAllowed(
            launchedFromUid = launchedFromUid,
            unknownUid = Process.INVALID_UID,
            uidHoldsPermission = { uid ->
                packageManager.getPackagesForUid(uid).orEmpty().any { packageName ->
                    packageManager.checkPermission(AddIdentityRequest.PERMISSION, packageName) ==
                        PackageManager.PERMISSION_GRANTED
                }
            },
        )
    }
}
