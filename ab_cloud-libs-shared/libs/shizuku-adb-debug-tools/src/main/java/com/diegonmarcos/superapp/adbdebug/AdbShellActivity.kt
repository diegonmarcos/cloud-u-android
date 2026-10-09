package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView

/**
 * The ADB Shell page as its own screen. Declared once, in this module's manifest, so EVERY app that links
 * the module has it; every status chip (the Store bar, Access > Android Perms, the Permissions page,
 * Account's Perms page) opens it with [AdbShellLink.open] instead of carrying channel controls of its own.
 * It hosts the same [AdbShellScreen] the in-app pages draw.
 */
class AdbShellActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply { setContent { AdbShellScreen() } })
    }
}

/** "Open the ADB Shell page": the one call every chip makes. */
object AdbShellLink {
    fun open(ctx: Context) {
        val i = Intent(ctx, AdbShellActivity::class.java)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }
}
