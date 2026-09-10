package com.diegonmarcos.superapp.settings
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.launcher.AppIconTile
import com.diegonmarcos.superapp.system.BackgroundOrchestrator
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.apps.PhoneAppsFragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.pm.LauncherApps
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import org.json.JSONArray

/**
 * Configs → Launcher → Theme (tab 1 of that page; One-Hand is tab 2) — theme
 * picker for the SuperApp's Home / Launcher
 * mode. The themes list is data-driven from
 * build.json::ui.launcher_themes (baked into BuildConfig as a base64
 * JSON blob). Tapping a theme persists it via [LauncherThemePrefs] and
 * MainActivity re-reads on next render to apply the new theme's
 * chrome.
 *
 * Below the picker there's a "Set as default launcher" call-to-action
 * that fires the system Home settings intent — Android handles the
 * actual chooser; we just navigate the user there.
 */
class LauncherConfigFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val palette = LauncherPalette.of(ctx)
        val themePrefs = LauncherThemePrefs(ctx)
        val profilePrefs = LauncherProfilePrefs(ctx)

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 16); setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)

        // ── Profiles section ───────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text = "Profile"
            setTextColor(palette.textPrimary)
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, 0, 0, dp(ctx, 8))
        })
        root.addView(TextView(ctx).apply {
            text = "Personal / Work / Guest. The picked profile is the " +
                "foundation for filtering which apps + folders the Phone " +
                "tab will surface (wired in a follow-up patch)."
            setTextColor(palette.textSecondary)
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setPadding(0, 0, 0, dp(ctx, 16))
        })
        val profiles = LauncherProfiles.loadFromBuildConfig()
        val currentProfile = profilePrefs.profile
        for (profileRow in profiles) {
            root.addView(genericTile(
                ctx,
                label    = profileRow.label,
                subtitle = profileRow.subtitle,
                isSelected = profileRow.id == currentProfile.id,
            ) {
                profilePrefs.profile = LauncherProfile.fromId(profileRow.id)
                rerender()
            })
            root.addView(spacer(ctx, dp(ctx, 8)))
        }

        // ── Theme section ──────────────────────────────────────────
        root.addView(spacer(ctx, dp(ctx, 24)))
        root.addView(TextView(ctx).apply {
            text = "Launcher theme"
            setTextColor(palette.textPrimary)
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, 0, 0, dp(ctx, 8))
        })
        root.addView(TextView(ctx).apply {
            text = "Pick the look the home screen uses when the SuperApp " +
                "is set as the Android default launcher."
            setTextColor(palette.textSecondary)
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setPadding(0, 0, 0, dp(ctx, 16))
        })

        // Theme tiles — data-driven from BuildConfig.
        val themes = LauncherThemes.loadFromBuildConfig()
        val current = themePrefs.theme
        // "· modified" on the selected tile when the device no longer matches
        // what the theme declares — see LauncherThemes.isModified for why the
        // honest label beats the flattering one.
        val modified = LauncherThemes.isModified(ctx, current)
        for (themeRow in themes) {
            val isCurrent = themeRow.id == current.id
            root.addView(genericTile(
                ctx,
                label    = themeRow.label + if (isCurrent && modified) "  ·  modified" else "",
                subtitle = if (isCurrent && modified)
                    "You changed a switch by hand, so this is no longer exactly " +
                        "${themeRow.label}. Tap to re-apply it."
                else themeRow.subtitle,
                isSelected = isCurrent,
            ) {
                // ONE action, both effects: chrome + every toggle the theme declares.
                LauncherThemes.apply(ctx, LauncherTheme.fromId(themeRow.id))
                (activity as? ShellActivity)?.notifyLauncherThemeChanged()
                com.diegonmarcos.superapp.appstore.ConstellationWorker.start(requireContext())
                rerender()
            })
            root.addView(spacer(ctx, dp(ctx, 8)))
        }

        // ── Screensaver section ────────────────────────────────────
        val settingsPrefs = LauncherSettingsPrefs(ctx)
        root.addView(spacer(ctx, dp(ctx, 24)))
        root.addView(sectionHeader(ctx, "Screensaver",
            "Which screensaver the floating-nav ‘Screensaver’ action shows, and the idle auto-start timer."))
        val currentSaver = settingsPrefs.screensaver
        for (saver in LauncherSettingsPrefs.Config.screensavers) {
            root.addView(genericTile(ctx, saver.label, saver.subtitle, saver.id == currentSaver) {
                settingsPrefs.screensaver = saver.id
                rerender()
            })
            root.addView(spacer(ctx, dp(ctx, 8)))
        }
        // Idle auto-start timer (seconds; 0 = never).
        val st = LauncherSettingsPrefs.Config.screensaverTimeout
        root.addView(sliderRow(ctx, st.label, st.subtitle, st.min, st.max,
            settingsPrefs.screensaverTimeout) { v -> settingsPrefs.screensaverTimeout = v })

        // ── Toggles ────────────────────────────────────────────────
        // One box per build.json::ui.launcher_settings.toggle_groups entry, in
        // declared order, holding the toggles that name it. This replaced a
        // `filter { it.battery }` / `filter { !it.battery }` split, which could
        // express exactly two boxes and no more — the four the owner asked for
        // were a Kotlin change under that shape and are a data change under this
        // one. A group with no toggles draws nothing rather than an empty header.
        val allToggles = LauncherSettingsPrefs.Config.toggles
        for (group in LauncherSettingsPrefs.Config.groups) {
            val rows = allToggles.filter { it.group == group.id }
            if (rows.isEmpty()) continue

            root.addView(spacer(ctx, dp(ctx, 24)))
            root.addView(sectionHeader(ctx, group.label, group.subtitle))

            if (group.master) {
                val allOn = rows.all { settingsPrefs.toggle(it) }
                root.addView(toggleRow(ctx, group.masterLabel, group.masterSubtitle, allOn) { on ->
                    // A master must not stomp a user-owned switch either: the
                    // edge menus are not the master's to turn off.
                    rows.filterNot { it.userOwned }.forEach { settingsPrefs.setToggle(it, on) }
                    onToggleChanged()
                    rerender() // reflect the child switches
                })
                root.addView(spacer(ctx, dp(ctx, 8)))
            }

            for (t in rows) {
                root.addView(toggleRow(ctx, t.label, t.subtitle, settingsPrefs.toggle(t)) { on ->
                    settingsPrefs.setToggle(t, on)
                    // Eye protection = the ANDROID SYSTEM night-light (blue-light
                    // filter), NOT a custom overlay — open its settings to enable.
                    if (t.id == "eye_protection" && on) {
                        runCatching { startActivity(Intent("android.settings.NIGHT_DISPLAY_SETTINGS")) }
                            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } }
                    }
                    onToggleChanged()
                    // Re-render because a hand-flip can move the theme tile into
                    // its "modified" state, and that label is derived, not stored.
                    rerender()
                })
                root.addView(spacer(ctx, dp(ctx, 8)))
            }
        }
        // Screen brightness (device-wide → needs WRITE_SETTINGS)
        val b = LauncherSettingsPrefs.Config.brightness
        root.addView(sliderRow(ctx, b.label, b.subtitle, b.min, b.max,
            settingsPrefs.brightness.let { if (it < b.min) b.min else it }) { v ->
            settingsPrefs.brightness = v
            applySystemBrightness(ctx, v)
        })

        // "Set as default launcher" CTA
        root.addView(spacer(ctx, dp(ctx, 16)))
        root.addView(TextView(ctx).apply {
            text = if (isDefaultLauncher(ctx))
                "✓ SuperApp is the active default launcher."
            else
                "SuperApp is NOT the default launcher yet."
            setTextColor(palette.textPrimary)
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setPadding(0, 0, 0, dp(ctx, 8))
        })
        root.addView(TextView(ctx).apply {
            text = "Set as default launcher →"
            setTextColor(palette.accent)
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            setBackgroundColor(palette.surfaceSelected)
            isClickable = true; isFocusable = true
            setOnClickListener {
                Haptics.tap(it)
                runCatching {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                }.onFailure {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    }
                }
            }
        })

        // ── Power Saving home apps — the very bottom of the page ────────────
        addPowerSavingAppsEditor(root, ctx)

        return scroll
    }

    /**
     * The twelve Cloud Power Saving slots, drawn as the grid they actually are.
     *
     * WHY IT IS NOT TWELVE SPINNERS ANY MORE. It was, and the owner's word for
     * it was that it needed to look "more nicee": twelve stacked dropdowns of
     * app NAMES, in a vertical column, editing a screen that is two rows of six
     * with icons on it. Nothing about the control resembled the thing it
     * controlled, and an app is recognised by its icon well before its name is
     * read. Now the editor IS the grid — same shape, same order, same real
     * icons, from the same central classification the Phone tab reads.
     *
     * The number of columns is the theme's own declared grid ("6x2"), not a 6
     * written here: the pane and its editor must not be able to disagree about
     * the layout, and one of two constants is always the one that gets missed.
     *
     * Writes back to [PowerSavingAppsPrefs], which is the same store the home
     * pane reads — the defaults it falls back to are the ones declared in
     * build.json, so this editor overrides data rather than replacing it, and a
     * slot the user never touches keeps tracking whatever default we ship next.
     */
    private fun addPowerSavingAppsEditor(root: LinearLayout, ctx: android.content.Context) {
        val themeId = LauncherTheme.CloudPowerSaving.id
        val slots = LauncherThemes.homeAppsFor(themeId)
        if (slots.isEmpty()) return

        root.addView(spacer(ctx, dp(ctx, 32)))
        root.addView(sectionHeader(ctx,
            ctx.getString(R.string.power_saving_apps_title),
            ctx.getString(R.string.power_saving_apps_caption)))

        val prefs = PowerSavingAppsPrefs(ctx)
        root.addView(AppIconTile.grid(
            ctx,
            slots = prefs.resolved(themeId).map { slot ->
                AppIconTile.Slot(
                    id = slot.id,
                    label = slot.label,
                    target = slot.target,
                    // "Selected" here means the user chose this one, as against
                    // a slot still tracking whatever build.json ships. That is
                    // the distinction the screen exists to make visible: which
                    // of the twelve are yours and which are still ours.
                    selected = prefs.target(slot.id)?.isNotBlank() == true,
                )
            },
            columns = LauncherThemes.gridColumnsFor(themeId),
            showSelection = true,
        ) { slot -> chooseAppFor(ctx, prefs, slots, slot.id, themeId) })
    }

    /**
     * Point one slot somewhere else.
     *
     * A single-choice dialog rather than the Spinner it replaces, for the
     * reason the Spinner's own comment documented at length: Spinner.setSelection
     * POSTS its callback, so merely opening the screen re-entered the listener
     * and could persist an override for all twelve slots, freezing them against
     * every future default. A dialog only calls back when a human taps a row,
     * so that whole class of bug has nowhere to live.
     */
    private fun chooseAppFor(
        ctx: android.content.Context,
        prefs: PowerSavingAppsPrefs,
        declared: List<LauncherThemes.HomeApp>,
        slotId: String,
        themeId: String,
    ) {
        val slot = declared.firstOrNull { it.id == slotId } ?: return
        // Derived here rather than threaded in from the caller: the row/slot
        // numbers this dialog prints have to describe the grid the user just
        // tapped, and a column count passed down is a copy that can be handed
        // in stale.
        val columns = LauncherThemes.gridColumnsFor(themeId)
        val index = declared.indexOfFirst { it.id == slotId }
        // Entry 0 names THIS slot's own shipped app: "Default (Mail)" is
        // readable where a bare "Default" leaves the user guessing.
        val options = listOf(
            Option(ctx.getString(R.string.power_saving_slot_default, slot.label), null),
        ) + installedApps(ctx).map { Option(it.label, "app:${it.pkg}") }
        val current = prefs.target(slotId)
        val selected = options.indexOfFirst { it.target == current }.coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(
                R.string.power_saving_slot_chooser,
                index / columns + 1,
                index % columns + 1,
            ))
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), selected) { dialog, which ->
                prefs.setTarget(slotId, options[which].target)
                dialog.dismiss()
                rerender()
            }
            .show()
    }

    private data class Option(val label: String, val target: String?)
    private data class InstalledApp(val label: String, val pkg: String)

    /** Launchable apps on this device, by label. The editor offers real
     *  packages only — a slot cannot be pointed at something that is not there. */
    private fun installedApps(ctx: android.content.Context): List<InstalledApp> = runCatching {
        val la = ctx.getSystemService(android.content.Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return emptyList()
        la.getActivityList(null, android.os.Process.myUserHandle())
            .map { InstalledApp(it.label?.toString().orEmpty().ifBlank { it.componentName.packageName },
                                it.componentName.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    /** Shared "selectable card" row used by both the Profiles and the
     *  Themes pickers — label up top, optional subtitle beneath,
     *  selected-state filled in brand purple, unselected on 13% white. */
    private fun genericTile(
        ctx: android.content.Context,
        label: String,
        subtitle: String,
        isSelected: Boolean,
        onClick: () -> Unit,
    ): View {
        val palette = LauncherPalette.of(ctx)
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
            setBackgroundColor(if (isSelected) palette.surfaceSelected else palette.surface)
            isClickable = true; isFocusable = true
            setOnClickListener {
                Haptics.tap(it)
                onClick()
            }
        }
        tile.addView(TextView(ctx).apply {
            text = (if (isSelected) "● " else "○ ") + label
            setTextColor(palette.textPrimary)
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        })
        if (subtitle.isNotBlank()) {
            tile.addView(TextView(ctx).apply {
                text = subtitle
                setTextColor(palette.textSecondary)
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(ctx, 4), 0, 0)
            })
        }
        return tile
    }

    private fun spacer(ctx: android.content.Context, h: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    /** Section title + caption block — matches the hand-rolled headers above. */
    private fun sectionHeader(ctx: android.content.Context, title: String, subtitle: String): View {
        val palette = LauncherPalette.of(ctx)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = title
                setTextColor(palette.textPrimary)
                setTextAppearance(android.R.style.TextAppearance_Material_Headline)
                setPadding(0, 0, 0, dp(ctx, 8))
            })
            addView(TextView(ctx).apply {
                text = subtitle
                setTextColor(palette.textSecondary)
                setTextAppearance(android.R.style.TextAppearance_Material_Body2)
                setPadding(0, 0, 0, dp(ctx, 16))
            })
        }
    }

    /** A label/subtitle + right-aligned switch row, persisted on toggle. */
    private fun toggleRow(
        ctx: android.content.Context,
        label: String, subtitle: String, checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val palette = LauncherPalette.of(ctx)
        return LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
        setBackgroundColor(palette.surface)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = label
                setTextColor(palette.textPrimary)
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            })
            if (subtitle.isNotBlank()) addView(TextView(ctx).apply {
                text = subtitle
                setTextColor(palette.textSecondary)
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            })
        })
        addView(SwitchCompat(ctx).apply {
            isChecked = checked
            // Persist FIRST, then buzz — otherwise Haptics.tap reads the
            // stale (pre-toggle) value and the "Vibration on tap" switch
            // itself silently no-ops when flipped ON.
            setOnCheckedChangeListener { v, isOn -> onChange(isOn); Haptics.tap(v) }
        })
        }
    }

    /** A label + SeekBar row. [value] pre-positions the thumb; onChange fires
     *  on release so we don't spam writes while dragging. */
    private fun sliderRow(
        ctx: android.content.Context,
        label: String, subtitle: String, min: Int, max: Int, value: Int,
        onChange: (Int) -> Unit,
    ): View {
        val palette = LauncherPalette.of(ctx)
        return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
        setBackgroundColor(palette.surface)
        addView(TextView(ctx).apply {
            text = label
            setTextColor(palette.textPrimary)
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        })
        if (subtitle.isNotBlank()) addView(TextView(ctx).apply {
            text = subtitle
            setTextColor(palette.textSecondary)
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, 0, 0, dp(ctx, 4))
        })
        addView(SeekBar(ctx).apply {
            this.min = min
            this.max = max
            progress = value.coerceIn(min, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { onChange(sb.progress) }
            })
        })
        }
    }

    /** Device-wide brightness via Settings.System (manual mode). Needs the
     *  WRITE_SETTINGS special-access grant — if absent, route the user to the
     *  grant screen and apply on their next adjustment. value<min = untouched. */
    private fun applySystemBrightness(ctx: android.content.Context, value: Int) {
        if (value < 0) return
        if (!Settings.System.canWrite(ctx)) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + ctx.packageName)))
            }
            return
        }
        runCatching {
            Settings.System.putInt(ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
        }
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** Force the fragment to rebuild so picker selections refresh. detach+attach
     *  in ONE transaction is collapsed to a no-op by the FragmentManager (the
     *  net state is unchanged) — splitting into two commitNow() calls guarantees
     *  onCreateView re-runs and the "● / ○" selection state updates. */
    private fun rerender() {
        runCatching {
            val fm = parentFragmentManager
            fm.beginTransaction().detach(this).commitNow()
            fm.beginTransaction().attach(this).commitNow()
        }
    }

    /** What every switch has to do after it is written, whichever group it is
     *  in: re-apply the launcher chrome so stars / cube / pets pick the change
     *  up, and re-arm the constellation worker so `fleet_check` takes effect
     *  now rather than at the next cold start. One place, so a new group cannot
     *  be added without it. */
    private fun onToggleChanged() {
        (activity as? ShellActivity)?.notifyLauncherThemeChanged()
        runCatching {
            com.diegonmarcos.superapp.appstore.ConstellationWorker.start(requireContext())
        }
    }

    /** True when the SuperApp's MainActivity is currently the resolved
     *  default Home Screen handler. The picker uses this to surface a
     *  live "active / not active" hint above the CTA. */
    private fun isDefaultLauncher(ctx: android.content.Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolved = ctx.packageManager.resolveActivity(intent, 0)
        return resolved?.activityInfo?.packageName == ctx.packageName
    }

    companion object { fun newInstance() = LauncherConfigFragment() }
}

/** Parses build.json::ui.launcher_themes from the baked BuildConfig
 *  blob. New themes only need a build.json entry + a LauncherTheme
 *  enum case — no Kotlin list edits required here.
 *
 *  Also hosts the data-driven [Features] decoder used by MainActivity
 *  / BackgroundOrchestrator to apply each theme's behavior (kept in
 *  this file so the picker code and the runtime code share one
 *  registry — no risk of two parallel parsers drifting). */
object LauncherThemes {
    data class Theme(
        val id: String,
        val label: String,
        val subtitle: String,
        val default: Boolean,
    )

    fun loadFromBuildConfig(): List<Theme> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_THEMES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            Theme(
                id       = o.optString("id"),
                label    = o.optString("label"),
                subtitle = o.optString("subtitle"),
                default  = o.optBoolean("default", false),
            )
        }
    }.getOrDefault(emptyList())

    /** Mirrors the keys in build.json::ui.launcher_themes[*].features. */
    data class Features(
        val home3d:            Boolean,
        val bottomNav:         Boolean,
        val dynamicIsland:     Boolean,
        val animations:        Boolean,
        val drawer:            Boolean,
        val oledBlack:         Boolean,
        val grid:              String,
        val backgroundPause:   Boolean,
        val wireguardRequired: Boolean,
    ) {
        companion object {
            val SAFE_DEFAULT = Features(
                home3d            = true,
                bottomNav         = true,
                dynamicIsland     = true,
                animations        = true,
                drawer            = true,
                oledBlack         = false,
                grid              = "default",
                backgroundPause   = false,
                wireguardRequired = false,
            )
        }
    }

    private val featuresById: Map<String, Features> by lazy { parseFeatures() }

    fun featuresFor(themeId: String): Features = featuresById[themeId] ?: Features.SAFE_DEFAULT
    fun featuresFor(theme: LauncherTheme): Features = featuresFor(theme.id)

    /**
     * Tiles per row, from the theme's declared `features.grid` ("6x2" ⇒ 6).
     *
     * The home pane and the editor that configures it BOTH read this. They each
     * used to carry their own 6 — a private const in one file and an integer
     * division in another — which is two constants that have to agree and no
     * mechanism making them, so changing the grid in build.json would have
     * silently relaid one of the two.
     *
     * A grid string that is not "<columns>x<rows>" falls back to the whole list
     * on one row rather than throwing: a wrong-looking grid is recoverable, a
     * home screen that crashes on a typo is not.
     */
    fun gridColumnsFor(themeId: String): Int =
        featuresFor(themeId).grid.substringBefore('x').toIntOrNull()?.takeIf { it > 0 }
            ?: Int.MAX_VALUE

    // ── Theme → toggle mapping ──────────────────────────────────────────────
    /**
     * The device state each theme sets, read from
     * build.json::ui.launcher_themes[*].toggles. Picking a theme applies the
     * chrome ([Features]) and this map in ONE action, which is what makes the
     * switches on the Theme tab agree with what the theme actually did.
     *
     * The mapping is DATA. A new theme is a new entry in that array — there is
     * deliberately no `when (theme)` here to add an arm to, because the previous
     * shape of this code had exactly that and it is why the toggles and the
     * theme could disagree: two places to edit, one of them easy to forget.
     *
     * A toggle the theme does not name keeps whatever the user had.
     */
    private val togglesById: Map<String, Map<String, Boolean>> by lazy { parseToggles() }

    fun togglesFor(themeId: String): Map<String, Boolean> = togglesById[themeId] ?: emptyMap()
    fun togglesFor(theme: LauncherTheme): Map<String, Boolean> = togglesFor(theme.id)

    private fun parseToggles(): Map<String, Map<String, Boolean>> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_LAUNCHER_THEMES_B64, Base64.NO_WRAP)))
        // Read ONCE, here, and drop the ids no theme may write. Filtering at the
        // source means no caller can forget to: an `edge_menus` key mistakenly
        // added to a theme record simply does not survive parsing. Doing it at
        // the call sites instead would be one `if` per site and one forgotten
        // site away from a theme silently switching the user's edge menus off.
        val userOwned = LauncherSettingsPrefs.Config.userOwnedIds
        val out = mutableMapOf<String, Map<String, Boolean>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val t = o.optJSONObject("toggles") ?: continue
            val map = mutableMapOf<String, Boolean>()
            for (key in t.keys()) {
                if (key in userOwned) continue
                map[key] = t.optBoolean(key)
            }
            out[id] = map
        }
        out
    }.getOrDefault(emptyMap())

    /**
     * CHOOSING A THEME SETS THE TOGGLES AND APPLIES THE UI TOGETHER — one call,
     * both effects. Every surface that changes the theme goes through here, so
     * there is no path that applies the chrome without the device state.
     */
    fun apply(ctx: android.content.Context, theme: LauncherTheme) {
        LauncherThemePrefs(ctx).theme = theme
        val settings = LauncherSettingsPrefs(ctx)
        val byId = LauncherSettingsPrefs.Config.toggles.associateBy { it.id }
        for ((id, want) in togglesFor(theme)) {
            // Route through the Item so a switch backed by another subsystem's
            // store is written where that subsystem reads it. An unknown id is
            // skipped rather than written blind: it would create a preference
            // key no switch displays, which is the invisible state this whole
            // change exists to remove.
            val item = byId[id] ?: continue
            settings.setToggle(item, want)
        }
    }

    /**
     * Does the device still match what [theme] declares?
     *
     * DERIVED, never stored. The alternative — a "modified" flag written when a
     * switch is flipped — is a second copy of the truth that goes stale the
     * moment anything else writes a toggle. Comparing against the mapping cannot
     * be wrong, because the mapping is what "being on this theme" MEANS.
     *
     * The picker shows "· modified" when this is true rather than silently
     * claiming a theme the phone no longer matches. A label that lies is worse
     * than no label: the user came to this screen precisely to find out what is
     * on, and the honest answer is "Power Saving, except you changed something".
     */
    fun isModified(ctx: android.content.Context, theme: LauncherTheme): Boolean {
        val settings = LauncherSettingsPrefs(ctx)
        val byId = LauncherSettingsPrefs.Config.toggles.associateBy { it.id }
        return togglesFor(theme).any { (id, want) ->
            val item = byId[id] ?: return@any false
            settings.toggle(item) != want
        }
    }

    // ── Power Saving's twelve home slots ────────────────────────────────────
    /** One launch target on the Power Saving home pane. [target] is the ordinary
     *  tile grammar, so it goes through the same dispatcher every other tile
     *  uses and `extapp:` keeps its install-if-missing fallback. */
    data class HomeApp(val id: String, val label: String, val target: String)

    private val homeAppsById: Map<String, List<HomeApp>> by lazy { parseHomeApps() }

    /** The theme's DEFAULT slots. The user's edits live in PowerSavingAppsPrefs,
     *  which falls back to this list per slot. */
    fun homeAppsFor(themeId: String): List<HomeApp> = homeAppsById[themeId] ?: emptyList()

    private fun parseHomeApps(): Map<String, List<HomeApp>> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_LAUNCHER_THEMES_B64, Base64.NO_WRAP)))
        val out = mutableMapOf<String, List<HomeApp>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val slots = o.optJSONArray("home_apps") ?: continue
            out[id] = (0 until slots.length()).mapNotNull { idx ->
                val s = slots.optJSONObject(idx) ?: return@mapNotNull null
                HomeApp(s.optString("id"), s.optString("label"), s.optString("target"))
            }
        }
        out
    }.getOrDefault(emptyMap())

    private fun parseFeatures(): Map<String, Features> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_THEMES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = mutableMapOf<String, Features>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val f = o.optJSONObject("features") ?: org.json.JSONObject()
            out[id] = Features(
                home3d            = f.optBoolean("home_3d",            true),
                bottomNav         = f.optBoolean("bottom_nav",         true),
                dynamicIsland     = f.optBoolean("dynamic_island",     true),
                animations        = f.optBoolean("animations",         true),
                drawer            = f.optBoolean("drawer",             true),
                oledBlack         = f.optBoolean("oled_black",         false),
                grid              = f.optString("grid",                "default"),
                backgroundPause   = f.optBoolean("background_pause",   false),
                wireguardRequired = f.optBoolean("wireguard_required", false),
            )
        }
        out
    }.getOrDefault(emptyMap())
}

/** Parses build.json::ui.launcher_profiles from the baked BuildConfig
 *  blob. Same shape as [LauncherThemes]. Add a profile via build.json
 *  + LauncherProfile enum — no other Kotlin edits needed.
 *
 *  Also hosts the [Behavior] decoder + [allowedPackagesFor] used by
 *  PhoneAppsFragment / MainActivity to enforce per-profile lockdowns. */
object LauncherProfiles {
    data class Profile(
        val id: String,
        val label: String,
        val subtitle: String,
        val default: Boolean,
    )

    fun loadFromBuildConfig(): List<Profile> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_PROFILES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            Profile(
                id       = o.optString("id"),
                label    = o.optString("label"),
                subtitle = o.optString("subtitle"),
                default  = o.optBoolean("default", false),
            )
        }
    }.getOrDefault(emptyList())

    /** Mirrors build.json::ui.launcher_profiles[*].behavior. */
    data class Behavior(
        val appFilter:     String,        // "all" | "whitelist"
        val whitelist:     List<String>,
        val wireguard:     String,        // "on" | "off"
        val allowIntents:  Any,           // "all" | List<String>
    ) {
        val isLockdown: Boolean get() = appFilter == "whitelist"
        val wireguardOff: Boolean get() = wireguard.equals("off", ignoreCase = true)

        companion object {
            val PASSTHROUGH = Behavior(
                appFilter    = "all",
                whitelist    = emptyList(),
                wireguard    = "on",
                allowIntents = "all",
            )
        }
    }

    private val behaviorById: Map<String, Behavior> by lazy { parseBehaviors() }

    fun behaviorFor(profileId: String): Behavior = behaviorById[profileId] ?: Behavior.PASSTHROUGH
    fun behaviorFor(profile: LauncherProfile): Behavior = behaviorFor(profile.id)

    /** Resolve the set of allowed package names for the active profile.
     *  Returns null when the active profile is passthrough — caller
     *  should NOT filter. Returns a possibly-empty set otherwise. */
    fun allowedPackagesFor(context: android.content.Context, profile: LauncherProfile): Set<String>? {
        val b = behaviorFor(profile)
        if (!b.isLockdown) return null
        val out = mutableSetOf<String>()
        b.whitelist.forEach { tag -> out.addAll(packagesForTag(context, tag)) }
        return out
    }

    private fun packagesForTag(context: android.content.Context, tag: String): Set<String> = when (tag) {
        "browser" -> browserPackages(context)
        else      -> setOf(tag)
    }

    private fun browserPackages(context: android.content.Context): Set<String> {
        val pm = context.packageManager
        val probe = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("http://example.com"),
        )
        val flags = android.content.pm.PackageManager.MATCH_ALL or
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        val matches = try { pm.queryIntentActivities(probe, flags) } catch (_: Throwable) { emptyList() }
        return matches.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    private fun parseBehaviors(): Map<String, Behavior> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_PROFILES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = mutableMapOf<String, Behavior>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val b = o.optJSONObject("behavior") ?: org.json.JSONObject()
            val whitelist = mutableListOf<String>()
            val wlArr = b.optJSONArray("whitelist") ?: JSONArray()
            for (j in 0 until wlArr.length()) {
                val s = wlArr.optString(j); if (s.isNotBlank()) whitelist.add(s)
            }
            val rawAllow = b.opt("allow_intents")
            val allow: Any = when (rawAllow) {
                is JSONArray -> (0 until rawAllow.length()).mapNotNull {
                    val s = rawAllow.optString(it); if (s.isBlank()) null else s
                }
                is String -> rawAllow
                else -> "all"
            }
            out[id] = Behavior(
                appFilter    = b.optString("app_filter", "all"),
                whitelist    = whitelist,
                wireguard    = b.optString("wireguard",  "on"),
                allowIntents = allow,
            )
        }
        out
    }.getOrDefault(emptyMap())
}
