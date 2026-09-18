package com.diegonmarcos.superapp.settings
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.BackgroundOrchestrator
import com.diegonmarcos.superapp.system.SystemDisplay
import com.diegonmarcos.superapp.configs.DeviceControls
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.ui.StatusLight
import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.apps.PhoneAppsFragment

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import org.json.JSONArray

/**
 * Configs → Launcher → Modes (tab 2 of three: Profiles · Modes · One-Hand) —
 * mode picker for the SuperApp's Home / Launcher.
 *
 * It used to be tab 1 and it used to open on the profile picker, which made it
 * one endless scroll holding two unrelated decisions. Profiles is now
 * [LauncherProfilesFragment] on its own tab, and it leads, because the picked
 * profile is the foundation the mode sits on rather than a header halfway down
 * the mode's page.
 *
 * The themes list is data-driven from
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

    /**
     * Rows on this page that can repaint THEMSELVES from the store.
     *
     * #349: every control here used to answer a flip with [rerenderPage] — a
     * detach+attach of the whole fragment — to change one badge and one derived
     * label. That rebuilt twenty-four tiles and two pickers, and threw the
     * scroll position away on every tap.
     *
     * A row registers a lambda that RE-READS the store and repaints, so nothing
     * cached in a captured `val` can go stale: the registry is the only state,
     * and it is refilled from scratch by [onCreateView].
     */
    private val repaints = mutableListOf<() -> Unit>()

    /** Every registered row re-reads the store and repaints. The whole of what
     *  a flip now costs. */
    private fun repaintFromStore() = repaints.forEach { it() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        // This view is being built from nothing, so every lambda in here points
        // at views that are about to be thrown away.
        repaints.clear()
        val palette = LauncherPalette.of(ctx)
        val themePrefs = LauncherThemePrefs(ctx)

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

        // Profiles used to open this page. They are their own tab now
        // (LauncherProfilesFragment) — see the `launcher` page in build.json,
        // whose `tabs` array is the whole of that move.

        // ── Theme section ──────────────────────────────────────────
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

        // A theme IS a mode, and the screen has to say so before the tiles.
        // Calling these "themes" taught the wrong thing: a theme is a colour
        // scheme you can try on, and picking one here instead rewrites the
        // launcher's whole design and flips the service toggles below. The
        // word and the subtitle are the only warning the user gets before
        // tapping.
        root.addView(sectionHeader(ctx, "Mode",
            "A mode is not a colour scheme. It owns this launcher's design and " +
                "the service toggles below. Switching mode changes all of them " +
                "together."))

        // Mode tiles — data-driven from BuildConfig.
        val themes = LauncherThemes.loadFromBuildConfig()
        val current = themePrefs.theme
        // The tiles live in their own box because their label is DERIVED: a
        // hand-flip below can move the selected mode into "· modified", so this
        // box is refilled when a toggle changes. Twelve tiles rebuilt inside an
        // existing page, not a page torn down and rebuilt around them — the
        // scroll position, and everything else on the screen, stays put.
        val modesBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(modesBox)
        fun fillModes() {
            modesBox.removeAllViews()
            // Re-read, never captured: "· modified" is the whole reason this
            // function exists, and a captured value is exactly the stale one.
            // See LauncherThemes.isModified for why the honest label beats the
            // flattering one.
            val modified = LauncherThemes.isModified(ctx, current)
            for (themeRow in themes) {
                val isCurrent = themeRow.id == current.id
                modesBox.addView(genericTile(
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
                    // A MODE is the one change on this page that legitimately
                    // recreates the Activity — Android resolves a Material3
                    // style once, at inflate time. SectionTabsFragment now
                    // brings the strip back on this tab afterwards.
                    (activity as? ShellActivity)?.notifyLauncherThemeChanged()
                    com.diegonmarcos.superapp.appstore.ConstellationWorker.start(requireContext())
                })
                modesBox.addView(spacer(ctx, dp(ctx, 8)))
            }
        }
        fillModes()
        repaints += { fillModes() }

        // ── Screensaver section ────────────────────────────────────
        val settingsPrefs = LauncherSettingsPrefs(ctx)
        root.addView(spacer(ctx, dp(ctx, 24)))
        root.addView(sectionHeader(ctx, "Screensaver",
            "Which screensaver the floating-nav ‘Screensaver’ action shows, and the idle auto-start timer."))
        // Same shape as the mode tiles above: the ● / ○ is derived from the
        // store, so the box is refilled rather than the page rebuilt.
        val saversBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(saversBox)
        fun fillSavers() {
            saversBox.removeAllViews()
            val currentSaver = settingsPrefs.screensaver
            for (saver in LauncherSettingsPrefs.Config.screensavers) {
                saversBox.addView(
                    genericTile(ctx, saver.label, saver.subtitle, saver.id == currentSaver) {
                        settingsPrefs.screensaver = saver.id
                        repaintFromStore()
                    })
                saversBox.addView(spacer(ctx, dp(ctx, 8)))
            }
        }
        fillSavers()
        repaints += { fillSavers() }
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
                // The master's own position is DERIVED from its children, so it
                // is passed as a question, not an answer: flipping one child
                // below has to move this switch, and it re-asks instead of
                // being told.
                root.addView(toggleRow(ctx, group.masterLabel, group.masterSubtitle,
                    { rows.all { settingsPrefs.toggle(it) } }) { on ->
                    // A master must not stomp a user-owned switch either: the
                    // edge menus are not the master's to turn off.
                    rows.filterNot { it.userOwned }.forEach { settingsPrefs.setToggle(it, on) }
                    onToggleChanged()
                    repaintFromStore() // reflect the child switches
                })
                root.addView(spacer(ctx, dp(ctx, 8)))
            }

            root.addView(toggleGrid(ctx, rows, settingsPrefs) { t, on ->
                // Dark mode is a device-wide setting written over the shell
                // channel, so it is the one switch that must not be flipped
                // on this thread — and the one that has to say so when
                // there is no channel, instead of lighting up and doing nothing.
                if (t.store == LauncherSettingsPrefs.Config.STORE_NIGHTMODE) {
                    if (!SystemDisplay.hasChannel(ctx)) Toast.makeText(
                        ctx, "Dark mode needs the shell channel — turn on wireless debugging first.",
                        Toast.LENGTH_LONG).show()
                    // A switch thumb moved under the finger; a tile does not. So
                    // the repaint has to be asked for, and only AFTER the write
                    // lands — repaint eagerly and the grid draws the old value.
                    else Thread({
                        settingsPrefs.setToggle(t, on)
                        root.post { repaintFromStore() }
                    }, "night-mode").start()
                    return@toggleGrid
                }
                settingsPrefs.setToggle(t, on)
                // Eye protection = the ANDROID SYSTEM night-light (blue-light
                // filter), NOT a custom overlay — open its settings to enable.
                if (t.id == "eye_protection" && on) {
                    runCatching { startActivity(Intent("android.settings.NIGHT_DISPLAY_SETTINGS")) }
                        .onFailure { runCatching { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } }
                }
                onToggleChanged()
                // The tile that was tapped repaints itself, and the mode tiles
                // refill — a hand-flip can move the selected mode into its
                // "modified" state, and that label is derived, not stored. What
                // this used to do was detach and re-attach the whole fragment
                // for those two facts: #349's "the page reloads".
                repaintFromStore()
            })
            root.addView(spacer(ctx, dp(ctx, 8)))

            // Scale lives in the Others box because it is the same kind of
            // whole-UI preference as the switches above it — Samsung draws
            // Screen zoom and Font size on this screen too, as two separate
            // knobs that drift apart. SystemDisplay drives both from this one.
            if (group.id == "others") {
                val sc = LauncherSettingsPrefs.Config.scale
                // ticks, the one-tap sizes AND the shipped default all come off
                // the SAME declared record the range does (#384, #408). Nothing
                // about this control is written twice, so no edit to build.json
                // can leave a button setting a number the slider never had, or
                // a button called "Default" that is not what the app installs
                // with — Slider.default IS the shipped preset's value.
                root.addView(sliderRow(ctx, sc.label, sc.subtitle, sc.min, sc.max, settingsPrefs.scale,
                    ticks = sc.ticks, presets = sc.presets) { v ->
                    settingsPrefs.scale = v
                    if (!SystemDisplay.hasChannel(ctx)) Toast.makeText(
                        ctx, "Scale needs the shell channel — turn on wireless debugging first.",
                        Toast.LENGTH_LONG).show()
                    else Thread({ SystemDisplay.applyScale(ctx, v) }, "ui-scale").start()
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

        return scroll
    }

    /** A label/subtitle + right-aligned switch row, persisted on toggle.
     *
     *  [checked] is a QUESTION, not a value: this row is the group master and
     *  its position is derived from the children below it, which the user can
     *  flip one at a time. It is re-asked on every [repaintFromStore]. */
    private fun toggleRow(
        ctx: android.content.Context,
        label: String, subtitle: String, checked: () -> Boolean,
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
        // Persist FIRST, then buzz — otherwise Haptics.tap reads the
        // stale (pre-toggle) value and the "Vibration on tap" switch
        // itself silently no-ops when flipped ON.
        val listener = android.widget.CompoundButton.OnCheckedChangeListener { v, isOn ->
            onChange(isOn); Haptics.tap(v)
        }
        val sw = SwitchCompat(ctx).apply {
            isChecked = checked()
            setOnCheckedChangeListener(listener)
        }
        // Follow the children without being rebuilt. The listener is DETACHED
        // for the write: setChecked fires it, and a sync that re-entered
        // onChange would write the master's new position straight back over
        // every child — turning "you turned one thing off" into "everything
        // off".
        repaints += {
            val want = checked()
            if (sw.isChecked != want) {
                sw.setOnCheckedChangeListener(null)
                sw.isChecked = want
                sw.setOnCheckedChangeListener(listener)
            }
        }
        addView(sw)
        }
    }

    /**
     * One box of switches drawn the way Configs ▸ Panel ▸ Control draws its
     * controls: a grid of round icon badges with a status light under each.
     *
     * WHY IT IS NOT A COLUMN OF SWITCH ROWS ANY MORE. It was, and the whole
     * Modes tab had become twenty-four stacked sentences each ending in a
     * switch, read one row at a time top to bottom. What the owner actually
     * does here is SCAN — find the one that is on, or the one to turn off —
     * and an icon grid is scanned in one look. Same argument, same shape and
     * the same two theme colours swapped as #246 applied to Control, which is
     * the screen this now matches by design and not by coincidence.
     *
     * The column count is [DeviceControls.columns], the SAME number Control's
     * grid uses, rather than a constant written here. Two grids that are
     * supposed to look like one another must not be able to disagree about how
     * wide they are, and one of two constants is always the one that gets
     * missed.
     *
     * A tile REPAINTS ITSELF. It used to be painted once and rely on the page
     * being rebuilt under it, on the grounds that a flip rebuilt the page
     * anyway — and that assumption is #349: one badge changing colour was
     * paying for a detach+attach of a page holding twenty-four tiles and two
     * pickers, and it took the scroll position with it. The tile still tracks
     * no state of its own: it re-reads [prefs] each time it paints, which is
     * why [prefs] is passed down here instead of a boolean.
     */
    private fun toggleGrid(
        ctx: android.content.Context,
        items: List<LauncherSettingsPrefs.Item>,
        prefs: LauncherSettingsPrefs,
        onFlip: (LauncherSettingsPrefs.Item, Boolean) -> Unit,
    ): View {
        val columns = DeviceControls.columns
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(LauncherPalette.of(ctx).surface)
            val pad = dp(ctx, 8); setPadding(pad, pad, pad, pad)
        }
        var strip: LinearLayout? = null
        items.forEachIndexed { index, item ->
            if (index % columns == 0) {
                strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(strip)
            }
            strip?.addView(toggleTile(ctx, item, prefs, onFlip))
        }
        // Pad the last strip so four tiles over three columns leaves the fourth
        // under the first, not stretched to a third of the screen.
        val remainder = items.size % columns
        if (remainder != 0) repeat(columns - remainder) {
            strip?.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
        }
        return grid
    }

    /** One cell of [toggleGrid]: filled badge + icon + label + status light.
     *  Badge fill and icon ink are the same two palette colours swapped, so a
     *  tile reads as on or off before any word on it is read. */
    private fun toggleTile(
        ctx: android.content.Context,
        item: LauncherSettingsPrefs.Item,
        prefs: LauncherSettingsPrefs,
        onFlip: (LauncherSettingsPrefs.Item, Boolean) -> Unit,
    ): View {
        val palette = LauncherPalette.of(ctx)

        val badgeFill = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val icon = ImageView(ctx).apply {
            setImageResource(Sections.iconResFor(ctx, item.icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(ctx, TILE_ICON_DP), dp(ctx, TILE_ICON_DP),
                Gravity.CENTER)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val badge = FrameLayout(ctx).apply {
            background = badgeFill
            layoutParams = LinearLayout.LayoutParams(dp(ctx, TILE_BADGE_DP), dp(ctx, TILE_BADGE_DP))
                .apply { gravity = Gravity.CENTER_HORIZONTAL }
            addView(icon)
        }
        val status = TextView(ctx).apply {
            textSize = 9f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val pad = dp(ctx, 6); setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
            addView(badge)
            addView(TextView(ctx).apply {
                text = item.label
                setTextColor(palette.textPrimary)
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 6), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(status)
        }

        // Badge fill and icon ink are the same two palette colours swapped, plus
        // the status word under them — the three things that say on or off, and
        // the only three a flip changes. Read from the store every time: a
        // store-backed switch (dark mode) is a VIEW OF the owning store and can
        // change without this page being touched at all.
        fun paint() {
            val on = prefs.toggle(item)
            val state = if (on) StatusLight.State.ON else StatusLight.State.OFF
            badgeFill.setColor(if (on) palette.accent else palette.surfaceSelected)
            icon.imageTintList = ColorStateList.valueOf(
                if (on) palette.surface else palette.textSecondary)
            status.text = StatusLight.text(ctx, state)
            status.setTextColor(StatusLight.colour(ctx, state))
            // The grid replaced labelled rows with icons, which took the text a
            // screen reader was reading off the screen — so the whole tile
            // carries the sentence and its children are skipped. It carries the
            // STATE too, so it has to be re-spoken when the state moves.
            cell.contentDescription = StatusLight.description(ctx, item.label, state)
        }
        paint()
        repaints += { paint() }

        cell.setOnClickListener {
            Haptics.tap(it)
            // Asked of the store, not of a value captured when this tile was
            // built. The tile now outlives its own flips, so a captured `on`
            // would be right once and wrong every time after.
            onFlip(item, !prefs.toggle(item))
        }
        return cell
    }

    /** A label + SeekBar row. [value] pre-positions the thumb; onChange fires
     *  on release so we don't spam writes while dragging.
     *
     *  [ticks] (#384) draws one mark per step under the line and puts the
     *  current step beside the label. A SeekBar's progress was ALREADY an
     *  integer, so the handle always landed on a step — what was missing was
     *  any way to SEE that, which is the whole of the owner's "then it don't
     *  have middle range numbers and we can see in which level is". Marks +
     *  readout, not a new snapping rule. It is declared per slider because
     *  Screen brightness is 0..255 and 256 marks are a grey smear.
     *
     *  [presets] (#384, widened by #408) adds one one-tap action per declared
     *  size — "Default" and "Normal" on the Scale row, the owner's own words —
     *  each moving the handle to a value that came out of the declaration and
     *  never out of a literal here. The row does not know which of them is the
     *  shipped one and must not: that is Slider.default's job, derived from the
     *  same records, so the button and the first install cannot disagree.
     *
     *  NEITHER reloads the page. The restore path writes the store, sets
     *  `progress` on THIS SeekBar and calls the same onChange a drag would, so
     *  it costs one view update — not the detach+attach (#349a) or the
     *  Activity.recreate (#349b) that made a tap on this page lose the scroll
     *  position and come back on the Profiles tab. */
    private fun sliderRow(
        ctx: android.content.Context,
        label: String, subtitle: String, min: Int, max: Int, value: Int,
        ticks: Boolean = false,
        presets: List<LauncherSettingsPrefs.Preset> = emptyList(),
        onChange: (Int) -> Unit,
    ): View {
        val palette = LauncherPalette.of(ctx)
        return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
        setBackgroundColor(palette.surface)
        // The live step readout. Built before the header so the header can hold
        // it, and updated from onProgressChanged so it follows the FINGER, not
        // the release — a number that only catches up when you let go is the
        // same "which level am I on" question with an extra second of delay.
        val readout = TextView(ctx).apply {
            setTextColor(palette.accent)
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        }
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(ctx).apply {
                text = label
                setTextColor(palette.textPrimary)
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (ticks) addView(readout)
        })
        if (subtitle.isNotBlank()) addView(TextView(ctx).apply {
            text = subtitle
            setTextColor(palette.textSecondary)
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, 0, 0, dp(ctx, 4))
        })
        val bar = SeekBar(ctx).apply {
            this.min = min
            this.max = max
            // The platform's own discrete-slider mark: AbsSeekBar draws this
            // drawable once per step across the track, so the marks and the
            // stops are the SAME fact and cannot drift. A hand-drawn tick bar
            // under the line would be a second copy of the range (#170).
            if (ticks) tickMark = android.graphics.drawable.ShapeDrawable(
                android.graphics.drawable.shapes.OvalShape()).apply {
                intrinsicWidth = dp(ctx, 4); intrinsicHeight = dp(ctx, 4)
                paint.color = palette.textSecondary
            }
            progress = value.coerceIn(min, max)
            readout.text = progress.toString()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    readout.text = p.toString()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { onChange(sb.progress) }
            })
        }
        addView(bar)
        if (ticks) addView(LinearLayout(ctx).apply {
            // The two ends named, so the row reads as a scale even before the
            // handle is touched. Derived from the declared bounds.
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(ctx).apply {
                text = min.toString()
                setTextColor(palette.textSecondary)
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = max.toString()
                setTextColor(palette.textSecondary)
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            })
        })
        if (presets.isNotEmpty()) addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 8), 0, 0)
            // One button per DECLARED preset, in declared order. A loop and not
            // two blocks: a third size is then a build.json entry, the way a
            // fourth toggle group was (#384's "a third box was a code change").
            for (preset in presets) addView(TextView(ctx).apply {
                // The number is shown, and it is the declared one — a label
                // reading "Default" over a handle that landed somewhere else is
                // the disagreement this whole row is shaped to prevent.
                text = "${preset.label} (${preset.value})"
                setTextColor(palette.accent)
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, 0, dp(ctx, 18), 0)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    Haptics.tap(it)
                    // Setting progress moves the handle and re-fires
                    // onProgressChanged (so the readout follows) but NOT
                    // onStopTrackingTouch, which is a touch-only callback — so
                    // the write is asked for explicitly here and happens
                    // exactly once.
                    bar.progress = preset.value.coerceIn(min, max)
                    onChange(bar.progress)
                }
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

    /** What every switch has to do after it is written, whichever group it is
     *  in: re-apply the launcher chrome so stars / cube / pets pick the change
     *  up, and re-arm the constellation worker so `fleet_check` takes effect
     *  now rather than at the next cold start. One place, so a new group cannot
     *  be added without it.
     *
     *  IT DOES NOT CALL notifyLauncherThemeChanged, and the sentence above is
     *  why: that is the MODE-change hook, and since 7600f71a9 its body is
     *  `LauncherStyle.restartForModeChange(this)` — an `Activity.recreate()`.
     *  A mode HAS to recreate (Android resolves a Material3 style once per
     *  Activity, at inflate time, so a mode applied any other way leaves every
     *  XML surface in the old one). A TOGGLE names no style, and this line
     *  bought a whole-Activity rebuild for each of twenty-four switches: the
     *  page flashed, the scroll position went, and the tab strip around it came
     *  back on Profiles because the controller that remembers the tab died with
     *  the Activity. That is #349, both halves, from one call.
     *
     *  [ShellActivity.applyLauncherChrome] is what "re-apply the launcher
     *  chrome" always meant — public, on the NavHost interface, documented
     *  idempotent — and [applyShellLiveToggles] pushes the three prefs whose
     *  views live in the shell and so cannot re-read themselves. Between them
     *  they are what the recreate was actually being used for. */
    private fun onToggleChanged() {
        (activity as? ShellActivity)?.applyLauncherChrome()
        applyShellLiveToggles(activity)
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

    companion object {
        fun newInstance() = LauncherConfigFragment()

        // The same two numbers Configs ▸ Panel ▸ Control uses for its badges, so
        // the two grids read as one screen. #338 is why they are stated once:
        // that regression was a size bumped in one place and not the other.
        private const val TILE_BADGE_DP = 56
        private const val TILE_ICON_DP = 26
    }
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
     * on, and the honest answer is "Cloud Minimalist Black, except you changed something".
     */
    fun isModified(ctx: android.content.Context, theme: LauncherTheme): Boolean {
        val settings = LauncherSettingsPrefs(ctx)
        val byId = LauncherSettingsPrefs.Config.toggles.associateBy { it.id }
        return togglesFor(theme).any { (id, want) ->
            val item = byId[id] ?: return@any false
            settings.toggle(item) != want
        }
    }

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
