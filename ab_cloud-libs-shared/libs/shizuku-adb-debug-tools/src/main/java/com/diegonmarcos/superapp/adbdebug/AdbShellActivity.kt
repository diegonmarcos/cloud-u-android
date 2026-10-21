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

/**
 * An activity that hosts the ADB Shell page INSIDE its own frame (Cloud Store: Configs > ADB Shell; SuperApp: Configs >
 * Network > ADB Shell). [openAdbShellInFrame] navigates there and returns true; false = this host cannot right now.
 */
interface AdbShellHost {
    fun openAdbShellInFrame(): Boolean
}

/**
 * "Open the ADB Shell page": the one call every chip makes. An app that hosts the page in its own frame
 * ([AdbShellHost]) opens THAT page; only an app with no such page (Termux, Nix-on-Droid) gets the full-screen
 * [AdbShellActivity].
 */
object AdbShellLink {
    fun open(ctx: Context) {
        var c: Context? = ctx
        while (c is android.content.ContextWrapper) {
            val host = c as? AdbShellHost
            if (host != null && runCatching { host.openAdbShellInFrame() }.getOrDefault(false)) return
            c = c.baseContext
        }
        val i = Intent(ctx, AdbShellActivity::class.java)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }
}
