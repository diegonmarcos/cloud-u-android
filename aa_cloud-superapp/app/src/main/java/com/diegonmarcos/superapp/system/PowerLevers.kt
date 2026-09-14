package com.diegonmarcos.superapp.system

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import com.diegonmarcos.superapp.adbdebug.ShellChannels

/** A command run on the privileged channel; null when the channel is down. */
typealias Exec = (String) -> String?

/**
 * Every system lever a mode is allowed to pull, as data.
 *
 * A mode is not a palette. Entering Cloud Power Saving is supposed to do what
 * Samsung's own power saving does — dim the panel, drop the refresh rate, stop
 * the radios scanning, park the apps — and this object is the whole of that
 * behaviour, expressed as one list rather than as a method per switch.
 *
 * ── The one rule ───────────────────────────────────────────────────────────
 * A lever REMEMBERS WHAT IT FOUND. [applyAll] reads the current value of every
 * lever and writes it to this app's own preferences BEFORE moving it, and
 * [restoreAll] puts exactly that value back. Without it "power saving" would be
 * a one-way door: leaving the mode would hand the user back a phone at 15%
 * brightness and 60Hz with no way to know what it used to be. Restoring a
 * remembered value is also why a lever that cannot be read is never written —
 * see [Lever.read] returning null.
 *
 * ── Why the authority exists at all ────────────────────────────────────────
 * These are `settings put`, `wm`, `cmd netpolicy` and `am` commands: they need
 * uid 2000 (SHELL), which this app has through its embedded adb client / the
 * privileged plane — the same channel PrivilegedPlaneWorker uses for `pm grant`
 * (see [PrivilegedGrants]). No channel means no levers, and that is reported as
 * such rather than silently skipped.
 *
 * ── What is deliberately NOT here ──────────────────────────────────────────
 * The CPU governor and the per-core maximum frequency are the two levers
 * Samsung's power saving pulls that uid 2000 cannot reach: writing
 * /sys/devices/system/cpu/.../scaling_max_freq needs root, which this phone
 * does not have. It is listed as a [Need.ROOT] lever anyway — a power screen
 * that quietly omits the one thing it cannot do is a power screen that lies
 * about its own coverage.
 */
object PowerLevers {

    /** What authority a lever needs, so the UI can say WHY one is not applied. */
    enum class Need { SHELL, APP, ROOT }

    /**
     * One lever.
     *
     * @param read the device's current value, or null when this lever has no
     *   readable value (a one-shot action such as killing background processes).
     *   A null read also means nothing is remembered and nothing is restored.
     * @param saving the value [read] reports once the lever is in its saving
     *   state — this is what the Battery Hunger rows compare against to show a
     *   live on/off light rather than a hopeful one.
     */
    data class Lever(
        val id: String,
        val label: String,
        val detail: String,
        val need: Need,
        val saving: String,
        val read: (Context, Exec) -> String?,
        val pull: (Context, Exec) -> String?,
        val restore: (Context, Exec, String?) -> String?,
    )

    // ── The table ──────────────────────────────────────────────────────────

    val ALL: List<Lever> = listOf(
        // Dark mode is two writes, not one: the secure setting is what survives
        // a reboot, `cmd uimode` is what makes the running system redraw now.
        Lever(
            id = "dark_mode",
            label = "Dark theme, system-wide",
            detail = "An OLED panel spends nothing on a black pixel.",
            need = Need.SHELL, saving = "2",
            read = { _, exec -> value(exec("settings get secure ui_night_mode")) },
            pull = { _, exec ->
                exec("settings put secure ui_night_mode 2")
                exec("cmd uimode night yes")
            },
            restore = { _, exec, prior ->
                exec("settings put secure ui_night_mode ${prior ?: "0"}")
                exec("cmd uimode night ${if (prior == "2") "yes" else "no"}")
            },
        ),
        setting("brightness_auto", "Adaptive brightness off",
            "Auto-brightness keeps the light sensor and its wake-ups alive.",
            "system", "screen_brightness_mode", "0"),
        setting("brightness_level", "Brightness capped",
            "The backlight is the single biggest draw on the device.",
            "system", "screen_brightness", "40"),
        setting("refresh_min", "Refresh rate floor 60Hz",
            "120Hz costs roughly a fifth of the panel budget for nothing here.",
            "system", "min_refresh_rate", "60"),
        setting("refresh_peak", "Refresh rate ceiling 60Hz",
            "The ceiling is the one the compositor actually honours.",
            "system", "peak_refresh_rate", "60"),
        setting("aod", "Always-On Display off",
            "A screen that is never fully off is a screen always drawing.",
            "secure", "doze_always_on", "0"),
        setting("aod_samsung", "Always-On Display off (Samsung)",
            "Samsung keeps its own AOD switch outside the AOSP one.",
            "system", "aod_mode", "0"),
        setting("screen_timeout", "Screen off after 15s",
            "The cheapest screen is the one that turned itself off.",
            "system", "screen_off_timeout", "15000"),
        setting("haptics", "Haptics off",
            "Every tap spins a motor that costs more than the tap saved.",
            "system", "haptic_feedback_enabled", "0"),
        setting("wifi_scan_always", "Wi-Fi scanning off",
            "Scanning-always keeps the Wi-Fi radio up with no link attached.",
            "global", "wifi_scan_always_enabled", "0"),
        setting("ble_scan_always", "Bluetooth scanning off",
            "Same radio cost, and nothing in this mode is listening for beacons.",
            "global", "ble_scan_always_enabled", "0"),
        setting("network_lte", "5G down to LTE",
            "The 5G modem draws materially more than LTE for the same mail poll.",
            "global", "preferred_network_mode", "9"),
        setting("window_animation", "Window animations off",
            "Animations are GPU frames drawn for a transition nobody watches.",
            "global", "window_animation_scale", "0"),
        setting("transition_animation", "Transition animations off",
            "Animations are GPU frames drawn for a transition nobody watches.",
            "global", "transition_animation_scale", "0"),
        setting("animator_duration", "Animator durations off",
            "Animations are GPU frames drawn for a transition nobody watches.",
            "global", "animator_duration_scale", "0"),

        // Fewer pixels to composite AND fewer to light. The numbers are derived
        // from what THIS device reports, never hardcoded: a wrong resolution
        // leaves a phone that cannot be tapped back out of the mode.
        Lever(
            id = "display_downscale",
            label = "Render at 75% size, 85% density",
            detail = "Fewer pixels composited and fewer lit, on this device's own numbers.",
            need = Need.SHELL, saving = "override",
            read = { _, exec ->
                if (exec("wm size")?.contains("Override size") == true) "override" else "physical"
            },
            pull = { ctx, exec ->
                // Scale from what the screen is ACTUALLY at, not from the panel's
                // physical numbers. A phone whose owner pulled Screen zoom or
                // display size below physical already sits under those numbers, so
                // 85% of physical can be an ENLARGEMENT rather than a saving.
                val priorSize = wmSize(exec, "Override size")
                val priorDensity = wmDensity(exec, "Override density")
                // Remember the override we are about to clobber, so restore can put
                // the user's own choice back instead of guessing.
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("prior.display_downscale.size", priorSize?.let { (pw, ph) -> "${pw}x${ph}" } ?: "")
                    .putString("prior.display_downscale.density", priorDensity?.toString() ?: "")
                    .apply()
                val (w, h) = priorSize ?: wmSize(exec, "Physical size") ?: (null to null)
                val density = priorDensity ?: wmDensity(exec, "Physical density")
                if (w != null && h != null) exec("wm size ${w * 3 / 4}x${h * 3 / 4}")
                if (density != null) exec("wm density ${density * 85 / 100}")
                "size=${w}x${h} density=$density"
            },
            restore = { ctx, exec, _ ->
                // NOT a plain reset. `wm density reset` clears the override and
                // lands on the PHYSICAL density — which is the wrong target for
                // anyone who had deliberately set Screen zoom or display size below
                // physical: one power-saving on/off cycle threw that setting away
                // and left the phone permanently bigger than they had it. Density
                // scales dp and sp alike, which is why it presented as "every icon
                // and every font grew" rather than as a font-size change.
                //
                // Reset only when there genuinely was no override to begin with.
                val priors = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val size = priors.getString("prior.display_downscale.size", "").orEmpty()
                val density = priors.getString("prior.display_downscale.density", "").orEmpty()
                if (size.isNotEmpty()) exec("wm size $size") else exec("wm size reset")
                if (density.isNotEmpty()) exec("wm density $density") else exec("wm density reset")
            },
        ),

        Lever(
            id = "background_data",
            label = "Background data restricted",
            detail = "Data Saver, applied globally — no app wakes the radio unattended.",
            need = Need.SHELL, saving = "true",
            read = { _, exec ->
                exec("cmd netpolicy get restrict-background")
                    ?.substringAfter(':')?.trim()?.lowercase()
            },
            pull = { _, exec -> exec("cmd netpolicy set restrict-background true") },
            restore = { _, exec, prior ->
                exec("cmd netpolicy set restrict-background ${prior ?: "false"}")
            },
        ),

        // ponytail: one-shot, no restore. The platform re-promotes an app's
        // bucket the moment the user opens it, so "restricted until you next
        // touch it" is the end state Samsung's power saving also lands on;
        // recording ~100 prior buckets would cost ~100 shell round-trips on
        // every mode switch to restore a value the platform overwrites anyway.
        Lever(
            id = "standby_restrict",
            label = "Third-party apps parked",
            detail = "Every non-fleet app dropped to the restricted standby bucket.",
            need = Need.SHELL, saving = "applied",
            read = { _, _ -> null },
            pull = { ctx, exec ->
                val fleet = PrivilegedGrants.fleetPackages(ctx)
                val parked = thirdPartyPackages(ctx).filterNot { it in fleet }
                parked.forEach { exec("am set-standby-bucket $it restricted") }
                "parked ${parked.size}"
            },
            restore = { _, _, _ -> null },
        ),

        // ponytail: one-shot by nature — there is no "un-kill".
        Lever(
            id = "kill_background",
            label = "Background processes killed",
            detail = "Everything already running in the background is stopped once.",
            need = Need.SHELL, saving = "applied",
            read = { _, _ -> null },
            pull = { _, exec -> exec("am kill-all") },
            restore = { _, _, _ -> null },
        ),

        // No shell needed — WRITE_SYNC_SETTINGS is a normal permission, so this
        // lever works even when the privileged channel is down.
        Lever(
            id = "master_sync",
            label = "Account auto-sync off",
            detail = "Contacts, calendar and photo sync stop polling their servers.",
            need = Need.APP, saving = "false",
            read = { _, _ -> ContentResolver.getMasterSyncAutomatically().toString() },
            pull = { _, _ ->
                ContentResolver.setMasterSyncAutomatically(false); "false"
            },
            restore = { _, _, prior ->
                ContentResolver.setMasterSyncAutomatically(prior != "false"); prior
            },
        ),

        // Listed precisely because it can NOT be done. See the object KDoc.
        Lever(
            id = "cpu_cap",
            label = "CPU frequency cap",
            detail = "Needs root — uid 2000 cannot write scaling_max_freq. Not applied.",
            need = Need.ROOT, saving = "unavailable",
            read = { _, _ -> "unavailable" },
            pull = { _, _ -> null },
            restore = { _, _, _ -> null },
        ),
    )

    // ── Driving the table ──────────────────────────────────────────────────

    /**
     * Pull every lever, remembering what each one found first.
     *
     * Idempotent on purpose: [applyForTheme][BackgroundOrchestrator.applyForTheme]
     * runs on every onResume, and re-reading an already-saving value would
     * overwrite the remembered original with the saving one — so a second apply
     * is a no-op rather than a slow way to lose the restore point.
     *
     * @return how many levers were actually moved.
     */
    fun applyAll(ctx: Context): Int {
        val exec = exec(ctx)
        val priors = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (priors.getBoolean(KEY_APPLIED, false)) return 0
        var moved = 0
        val editor = priors.edit()
        for (lever in ALL) {
            if (lever.need == Need.ROOT) continue
            runCatching {
                lever.read(ctx, exec)?.let { editor.putString("prior.${lever.id}", it) }
                editor.apply()
                if (lever.pull(ctx, exec) != null) moved++
            }
        }
        editor.putBoolean(KEY_APPLIED, true).apply()
        return moved
    }

    /** Put every remembered value back, and forget it. */
    fun restoreAll(ctx: Context): Int {
        val exec = exec(ctx)
        val priors = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!priors.getBoolean(KEY_APPLIED, false)) return 0
        var moved = 0
        for (lever in ALL) {
            if (lever.need == Need.ROOT) continue
            runCatching {
                val prior = priors.getString("prior.${lever.id}", null)
                if (lever.restore(ctx, exec, prior) != null) moved++
            }
        }
        priors.edit().clear().apply()
        return moved
    }

    /** True once [applyAll] has run and before [restoreAll] has undone it. */
    fun isApplied(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_APPLIED, false)

    /**
     * What the device says RIGHT NOW for each lever, for the Battery Hunger rows.
     *
     * Read from the device rather than from our own "applied" flag on purpose:
     * a row that reports our intention instead of the phone's actual state is
     * the exact failure the whole screen exists to make impossible. Call off the
     * main thread — every entry is a shell round-trip.
     */
    fun liveState(ctx: Context): Map<String, String?> {
        val exec = exec(ctx)
        return ALL.associate { it.id to runCatching { it.read(ctx, exec) }.getOrNull() }
    }

    /** Whether a privileged channel exists at all, for the screen's header line. */
    fun channelName(ctx: Context): String? = ShellChannels.active(ctx)?.name()

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun exec(ctx: Context): Exec {
        val channel = ShellChannels.active(ctx)
        return { command -> channel?.exec(ctx, command) }
    }

    /** A `settings get/put` lever, which is most of them. */
    private fun setting(
        id: String, label: String, detail: String,
        namespace: String, key: String, saving: String,
    ) = Lever(
        id = id, label = label, detail = detail, need = Need.SHELL, saving = saving,
        read = { _, exec -> value(exec("settings get $namespace $key")) },
        pull = { _, exec -> exec("settings put $namespace $key $saving") },
        restore = { _, exec, prior ->
            // `settings get` on an unset key prints "null"; putting that back
            // would store the literal string, so an unset key is deleted again.
            if (prior == null) exec("settings delete $namespace $key")
            else exec("settings put $namespace $key $prior")
        },
    )

    /** `settings get` prints "null" for an unset key — that is absence, not a value. */
    private fun value(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /**
 * `wm size` / `wm density` always print the panel's "Physical" line and print an
 * "Override" line only when something has changed it. Samsung's Screen zoom and
 * Android's own display-size setting are both overrides, so "Physical" is NOT
 * what the screen is rendering at on any phone whose owner has touched either.
 * Pass the label you actually mean.
 */
private fun wmSize(exec: Exec, label: String): Pair<Int, Int>? {
        val line = exec("wm size")?.lineSequence()
            ?.firstOrNull { it.contains(label) } ?: return null
        val parts = line.substringAfter(':').trim().split('x')
        val width = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val height = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        return width to height
    }

    private fun wmDensity(exec: Exec, label: String): Int? = exec("wm density")?.lineSequence()
        ?.firstOrNull { it.contains(label) }
        ?.substringAfter(':')?.trim()?.toIntOrNull()

    /** Installed packages that did not ship with the system. */
    private fun thirdPartyPackages(ctx: Context): List<String> = runCatching {
        ctx.packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.packageName }
    }.getOrDefault(emptyList())

    private const val PREFS = "power_levers"
    private const val KEY_APPLIED = "applied"
}
