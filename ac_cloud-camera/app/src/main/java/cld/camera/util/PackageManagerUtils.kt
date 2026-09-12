package cld.camera.util

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

fun PackageManager.resolveActivity(intent: Intent, flags: Long): ResolveInfo? {
    return if (Build.VERSION.SDK_INT >= 33) {
        resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags))
    } else {
        @Suppress("DEPRECATION")
        resolveActivity(intent, flags.toInt())
    }
}

/**
 * Whether [packageName] is installed at all, as opposed to installed-but-unable-to-handle
 * some intent. Those are different states with different fixes, and resolveActivity()
 * returning null cannot tell them apart.
 *
 * Requires the package to be visible: on targetSdk 30+ this throws NameNotFoundException
 * for an installed package that is not covered by a <queries> entry, which would answer
 * "absent" for an app sitting on the home screen.
 */
fun PackageManager.isPackageInstalled(packageName: String): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= 33) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
