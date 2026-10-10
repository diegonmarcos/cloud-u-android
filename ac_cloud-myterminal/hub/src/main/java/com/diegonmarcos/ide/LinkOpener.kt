package com.diegonmarcos.ide

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Where a URL from the terminal opens (long-press on a link, or on an entry of
 * the pane menu's URL list): the fleet's Cloud Browser when it is installed,
 * else whatever the phone's default browser is. The package is
 * build.json::links.browser_package, baked as BuildConfig.LINK_BROWSER_PACKAGE.
 */
object LinkOpener {

    /** A bare domain from the terminal ("example.com/x") opens as https. */
    fun normalize(url: String): String =
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(url)) url else "https://$url"

    fun open(ctx: Context, url: String) {
        val uri = Uri.parse(normalize(url.trim()))
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pkg = BuildConfig.LINK_BROWSER_PACKAGE
        if (pkg.isNotEmpty()) {
            try {
                ctx.startActivity(Intent(view).setPackage(pkg))
                return
            } catch (_: ActivityNotFoundException) {
                // Cloud Browser is not installed: the default browser below.
            }
        }
        try {
            ctx.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "No browser installed for $uri", Toast.LENGTH_LONG).show()
        }
    }
}
