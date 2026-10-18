package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import java.io.File
import java.security.MessageDigest

/**
 * The person / large-icon avatar a notification carried when the listener captured it, kept as a 40dp PNG in the app's
 * cache (one file per notification key). One UI draws it as a round image on the right of the body. Cache, not files:
 * Android may drop it under pressure, and a missing avatar just means the row draws without one.
 */
object NotifAvatars {
    const val SIZE_DP = 40
    private const val DIR = "notif-avatars"

    /** The file for [key] (a StatusBarNotification key), whether or not it exists. */
    fun file(ctx: Context, key: String): File = File(File(ctx.cacheDir, DIR), fileName(key))

    /** Stable, filesystem-safe name: sbn keys hold '|' and user handles. */
    fun fileName(key: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return d.joinToString("") { "%02x".format(it) } + ".png"
    }

    /** Pixel edge of the stored square for a display density. */
    fun sizePx(density: Float): Int = (SIZE_DP * density + 0.5f).toInt().coerceAtLeast(1)

    /** Capture the avatar of [n] under [key]. Never throws: an avatar is decoration. */
    fun capture(ctx: Context, key: String, n: Notification) {
        runCatching {
            val icon = pick(n.extras) ?: return
            val d = icon.loadDrawable(ctx) ?: return
            val px = sizePx(ctx.resources.displayMetrics.density)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, px, px)
            d.draw(Canvas(bmp))
            val f = file(ctx, key)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    /** MessagingStyle's last sender with an icon wins (the person who wrote), then the large icon. */
    @Suppress("DEPRECATION")
    private fun pick(extras: Bundle?): Icon? {
        if (extras == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (msgs != null) {
                for (m in msgs.reversed()) {
                    val person = (m as? Bundle)?.getParcelable<Person>("sender_person")
                    person?.icon?.let { return it }
                }
            }
            extras.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON)?.icon?.let { return it }
        }
        (extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_LARGE_ICON))?.let { v ->
            when (v) {
                is Icon -> return v
                is Bitmap -> return Icon.createWithBitmap(v)
                else -> Unit
            }
        }
        return null
    }
}
