package com.diegonmarcos.cloudsearch.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.diegonmarcos.cloudsearch.BuildConfig

/** Opens a result in cloud-browser (build.json::open_with), or in the system's browser when it is not installed. */
object Browser {
    fun open(ctx: Context, url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(Intent(view).setPackage(BuildConfig.BROWSER_PACKAGE))
        } catch (_: ActivityNotFoundException) {
            runCatching { ctx.startActivity(view) }
        }
    }
}
