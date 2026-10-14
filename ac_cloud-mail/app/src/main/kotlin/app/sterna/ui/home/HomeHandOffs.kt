package app.sterna.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.sterna.R

private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
private const val HERMES_BOT = "Cloud_agent_hermes_bot"

private const val C3_PACKAGE = "com.diegonmarcos.cloudc3"
private const val C3_ACTIVITY = "com.diegonmarcos.cloudc3.MainActivity"
/** The fleet's launcher deep-link contract: the target page id travels as this string extra. */
private const val C3_TARGET_EXTRA = "shortcut_action"
/** Cloud C3's Observ > Dagu page (page id `c3/dagu`), the native list of deterministic workflows. */
private const val C3_WORKFLOWS_PAGE = "c3/dagu"
private const val WORKFLOWS_WEB = "https://workflows.diegonmarcos.com"

/** Home > AI: the Hermes agent's Telegram bot, same target as the superapp's AI action; t.me when Telegram is absent. Reports whether it left. */
internal fun openHermesAi(context: Context): Boolean {
    val tg = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$HERMES_BOT")).setPackage(TELEGRAM_PACKAGE)
    return try {
        context.startActivity(tg)
        true
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$HERMES_BOT"))) }.isSuccess
    }
}

/** Home > Workflows: Cloud C3's Dagu page (deep link); the Dagu web UI when C3 is not installed. Reports whether it left. */
internal fun openWorkflows(context: Context): Boolean {
    val c3 = Intent().setClassName(C3_PACKAGE, C3_ACTIVITY).putExtra(C3_TARGET_EXTRA, "page:$C3_WORKFLOWS_PAGE")
    return try {
        context.startActivity(c3)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.home_workflows_not_installed, Toast.LENGTH_SHORT).show()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WORKFLOWS_WEB))) }.isSuccess
    }
}
