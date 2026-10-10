package com.diegonmarcos.superapp.batterystats

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.ShellActivity

/**
 * The one door to Configs › About › Battery (page:config/battery, declared
 * hidden in build.json): the home-screen battery popup's "Battery stats ›",
 * About's Battery section and the Battery badge all open it through here.
 * Inside the shell it is the shell's own navigation; from anywhere else it is
 * MainActivity's page: shortcut, the route every badge uses.
 */
object BatteryStatsPage {

    const val SECTION = "config"
    const val PAGE = "battery"
    const val SHORTCUT = "page:config/battery"

    fun open(ctx: Context) {
        val shell = shellOf(ctx)
        if (shell != null) { runCatching { shell.openSectionPage(SECTION, PAGE) }; return }
        runCatching { ctx.startActivity(intent(ctx)) }
            .onFailure { Toast.makeText(ctx, "Could not open Battery stats", Toast.LENGTH_SHORT).show() }
    }

    fun intent(ctx: Context): Intent = Intent(ctx, MainActivity::class.java)
        .putExtra("shortcut_action", SHORTCUT)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun shellOf(ctx: Context): ShellActivity? {
        var c: Context? = ctx
        while (c != null) {
            if (c is ShellActivity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }
}
