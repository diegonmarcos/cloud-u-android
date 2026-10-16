package org.fossify.phone.sms

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony

/**
 * The default-SMS-app role. Android 10+ asks through RoleManager (ROLE_SMS); older releases through
 * Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT. Not being the default is a supported state: the app
 * then lists conversations read-only and shows a "make default" banner instead of a composer.
 */
object SmsRole {
    fun isDefault(context: Context): Boolean =
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    /** The intent that opens the system's "change default SMS app" prompt, or null if there is nothing to ask. */
    fun requestIntent(context: Context): Intent? {
        if (isDefault(context)) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java) ?: return null
            return if (roles.isRoleAvailable(RoleManager.ROLE_SMS) && !roles.isRoleHeld(RoleManager.ROLE_SMS)) {
                roles.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                null
            }
        }
        return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
    }
}
