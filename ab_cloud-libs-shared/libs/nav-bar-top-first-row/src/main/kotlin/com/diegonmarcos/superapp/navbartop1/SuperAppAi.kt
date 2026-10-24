package com.diegonmarcos.superapp.navbartop1

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The AI icon of an app that has no assistant of its own (#1154 owner decision): it hands over to the SuperApp, whose assistant it is
 * (`extapp:cloud-superapp/ai`, as Cloud Me does). A missing SuperApp is a false return, never a crash.
 */
public object SuperAppAi {
    public const val PKG: String = "com.diegonmarcos.superapp"
    public fun open(ctx: Context): Boolean = runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        ctx.startActivity(i); true
    }.getOrDefault(false)
}

@Composable
public fun rememberSuperAppAiLauncher(): () -> Unit {
    val ctx = LocalContext.current
    return remember(ctx) { { SuperAppAi.open(ctx); Unit } }
}
