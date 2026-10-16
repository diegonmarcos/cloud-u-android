package org.fossify.phone.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** The runtime grants the SMS screens ask for, and the two questions they answer from them. */
object SmsAccess {
    fun permissions(): Array<String> = buildList {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Listing conversations needs READ_SMS; as the default SMS app the role grants it. */
    fun canRead(context: Context): Boolean = granted(context, Manifest.permission.READ_SMS)

    fun canSend(context: Context): Boolean = SmsRole.isDefault(context) && granted(context, Manifest.permission.SEND_SMS)
}
