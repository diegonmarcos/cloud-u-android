package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Shizuku as this device sees it, and the one tap that moves it forward. */
object ShizukuStatus {
    private const val PKG = "moe.shizuku.privileged.api"

    fun installed(ctx: Context): Boolean = ctx.packageManager.getLaunchIntentForPackage(PKG) != null

    fun current(ctx: Context): ShizukuState =
        ShizukuState.of(installed(ctx), ShizukuAdb.isAvailable(), ShizukuAdb.isGranted())

    /** Permission needed -> ask for it; otherwise open the Shizuku app (where its service is started). */
    fun act(ctx: Context): String = when (current(ctx)) {
        ShizukuState.PERMISSION_NEEDED -> { ShizukuAdb.requestPermission { }; "Approve the Shizuku prompt." }
        ShizukuState.NOT_INSTALLED -> "Shizuku is not installed."
        else -> ctx.packageManager.getLaunchIntentForPackage(PKG)?.let {
            runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; "Opened Shizuku."
        } ?: "Shizuku is not installed."
    }
}

/** Reads every layer on a device (blocking: call off the main thread) and runs the page's actions. */
object ShellChannelProbe {

    fun layers(ctx: Context): ShellChannelLayers {
        val server = if (LocalShellChannel.isReady(ctx)) LocalShellChannel.exec(ctx, "id") else null
        val nice = AdbShellBootstrap.niceName()
        val up = if (server != null) LocalShellChannel.exec(ctx,
            "p=\$(pgrep -f '${nice.dropLast(1)}[${nice.last()}]' | head -1); [ -n \"\$p\" ] && ps -o etime= -p \$p")?.trim()?.ifBlank { null } else null
        return ShellChannelLayers(
            mode = ChannelModePrefs.current(ctx),
            wirelessDebug = WirelessDebugging.isOn(ctx),
            adbPaired = EmbeddedAdbChannel.everPaired(ctx) || EmbeddedAdbChannel.isReady(ctx),
            adbConnected = EmbeddedAdbChannel.isReady(ctx),
            serverPort = AdbShellBootstrap.port(),
            serverRunning = server != null,
            serverUid = ShellChannelLayers.uidOf(server),
            serverUptime = up,
            shizuku = ShizukuStatus.current(ctx),
            route = ShellChannels.active(ctx)?.name(),
        )
    }

    fun openDeveloperOptions(ctx: Context) {
        val dev = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(if (dev.resolveActivity(ctx.packageManager) != null) dev else
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun pair(ctx: Context): String {
        openDeveloperOptions(ctx)
        runCatching { AdbPairingService.start(ctx) }
        return "Pairing notification is up - enter the code there."
    }

    /** Blocking. */
    fun reconnect(ctx: Context): String = ShellAccess.ensure(ctx) { }

    /** Blocking. Starts (or replaces) the local server through a ready non-server channel. */
    fun startServer(ctx: Context, restart: Boolean): String {
        val via = ShellChannels.all.firstOrNull { it !== LocalShellChannel && it.isReady(ctx) }
            ?: return "No adb or Shizuku channel is up to start the server with - Reconnect first."
        if (restart) { stopServer(ctx); Thread.sleep(500) }
        val ok = AdbShellBootstrap.ensureServer(ctx, listOf(via), force = true)
        return if (ok) "Local server running (started via ${via.name()})." else "The server did not come up (${AdbShellBootstrap.bootstrapState(ctx)})."
    }

    /** Blocking. */
    fun stopServer(ctx: Context): String {
        val nice = AdbShellBootstrap.niceName()
        val via = ShellChannels.all.firstOrNull { it.isReady(ctx) } ?: return "No channel is up to stop it with."
        via.exec(ctx, "pkill -f '${nice.dropLast(1)}[${nice.last()}]'")
        return "Stop sent to the local server."
    }

    /** Blocking. An `id` round trip over the active route, with its output. */
    fun test(ctx: Context): String {
        val ch = ShellChannels.active(ctx) ?: return "No route: nothing answered."
        val out = ch.exec(ctx, "id")?.trim()
        return "via ${ch.name()}: " + (out ?: "no output")
    }
}
