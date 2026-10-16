package com.diegonmarcos.superapp.bottomnav

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * How an app opens Cloud Account (ac_cloud-account, `extapp:cloud-account`): the same explicit
 * `com.diegonmarcos.cloudaccount.OPEN` intent SuperApp's Account entry sends (AccountHandoff) and the
 * browser sends. Kept here so the Settings dropdown's Account entry is ONE launch, not one per app.
 * A package-pinned intent: an app that is not installed is a false return, never a crash.
 */
public object AccountLaunch {
    public const val PKG: String = "com.diegonmarcos.cloudaccount"
    public const val ACTION_OPEN: String = "$PKG.OPEN"

    public fun intent(): Intent = Intent(ACTION_OPEN).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens Cloud Account. False when it is not installed or will not start. */
    public fun open(ctx: Context): Boolean = runCatching { ctx.startActivity(intent()); true }.getOrDefault(false)
}

/**
 * The Account action for [AppTopBarScaffold]: launches Cloud Account, and tells the user (a toast) when it
 * is not installed or will not start. [onMissing] replaces the toast (an app that offers the install).
 */
@Composable
public fun rememberAccountLauncher(onMissing: (() -> Unit)? = null): () -> Unit {
    val ctx = LocalContext.current
    val missing = stringResource(R.string.app_top_bar_account_missing)
    val fallback by rememberUpdatedState(onMissing)
    return remember(ctx, missing) {
        {
            if (!AccountLaunch.open(ctx)) {
                val handler = fallback
                if (handler != null) handler() else Toast.makeText(ctx, missing, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
