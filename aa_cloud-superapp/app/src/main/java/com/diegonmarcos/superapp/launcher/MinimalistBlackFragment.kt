package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.system.ScreenLocker
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.settings.LauncherTheme

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Cloud Minimalist Black — the terminal-style home pane rendered when
 * LauncherTheme.CloudMinimalistBlack is active. Pure black background,
 * monospace green text, one column scroll listing every launchable
 * app on the device. Tapping a row launches its main activity via
 * [LauncherApps].
 *
 * No chrome — no toolbar, no bottom nav, no dynamic island. The
 * activity hides those when the theme is active so the screen reads
 * as a real terminal.
 *
 * Filter row at the top is a single-line monospace prompt
 * ("$ filter:") that does a case-insensitive label substring filter
 * against the loaded app list — KISS-style index search, not regex.
 *
 * ── How this differs from Cloud Power Saving ─────────────────────────────
 * Both are true black on an OLED panel and neither is the other. This one is a
 * FULL launcher in a terminal palette: every launchable app on the device, a
 * live filter, green monospace, scrolling. Power Saving is a REDUCED mode —
 * twelve fixed slots on one non-scrolling screen with everything else gone.
 * Black is what they share; the amount of phone you still have is what
 * separates them, and collapsing them into one theme would cost whichever of
 * the two the user actually picked.
 *
 * ── Where the colours come from ──────────────────────────────────────────
 * [LauncherPalette]. Every green here used to be a hex written at the view —
 * and a literal cannot follow a theme, which is exactly why choosing this theme
 * recoloured the system bars and the edge menu and not one pixel of the list
 * below them. The dim green was #FF335533: 2.49:1 against the black it is drawn
 * on, well under the 4.5:1 the 11-13sp text it was used for needs. The
 * theme_terminal_text_secondary token that replaced it is 8.98:1.
 */
class MinimalistBlackFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private var allApps: List<AppRow> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val palette = LauncherPalette.of(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // setBackgroundResource, not setBackgroundColor: the `window` role
            // names whichever resource paints that surface, and a theme is free
            // to make it a gradient drawable rather than a flat colour.
            setBackgroundResource(palette.windowRes)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // Terminal prompt / header
        root.addView(TextView(ctx).apply {
            text = "$ ~/apps"
            typeface = Typeface.MONOSPACE
            setTextColor(palette.accent)
            textSize = 16f
            setPadding(dp(ctx, 16), dp(ctx, 28), dp(ctx, 16), dp(ctx, 8))
        })

        // Filter input — a single line, monospace, no border.
        root.addView(EditText(ctx).apply {
            hint = "filter:"
            setHintTextColor(palette.textSecondary)
            setTextColor(palette.textPrimary)
            typeface = Typeface.MONOSPACE
            textSize = 14f
            background = null
            setSingleLine(true)
            setPadding(dp(ctx, 16), dp(ctx, 4), dp(ctx, 16), dp(ctx, 12))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    rebuildList(s?.toString().orEmpty())
                }
            })
        })

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(palette.windowRes)
        }
        scroll.addView(listContainer)
        root.addView(scroll)

        // Footer — terminal-style status line
        root.addView(TextView(ctx).apply {
            text = "—  esc / home → reload  •  taps to launch"
            typeface = Typeface.MONOSPACE
            setTextColor(palette.textSecondary)
            textSize = 11f
            gravity = Gravity.START
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 24))
        })

        // Load apps async-ish (LauncherApps.getActivityList is fast on
        // a populated device but not free — do it on the UI thread for
        // now since it's already cached internally by the system).
        allApps = loadApps(ctx)
        rebuildList("")

        // Double-tap anywhere on the home root → lock the screen
        // (mirrors Home3DFragment). App rows still receive single
        // taps via their own click handlers; the root listener only
        // sees events the child rows didn't claim.
        ScreenLocker.attachDoubleTapLock(root)

        return root
    }

    private fun rebuildList(filter: String) {
        listContainer.removeAllViews()
        val ctx = listContainer.context
        val palette = LauncherPalette.of(ctx)
        val needle = filter.trim().lowercase()
        val visible = if (needle.isBlank()) allApps
                      else allApps.filter { it.label.lowercase().contains(needle) }
        for (app in visible) {
            listContainer.addView(TextView(ctx).apply {
                text = app.label
                typeface = Typeface.MONOSPACE
                setTextColor(palette.textPrimary)
                textSize = 14f
                setPadding(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), dp(ctx, 6))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Haptics.tap(it)
                    runCatching { app.launch() }
                }
            })
        }
        if (visible.isEmpty()) {
            listContainer.addView(TextView(ctx).apply {
                text = "— no matches —"
                typeface = Typeface.MONOSPACE
                setTextColor(palette.textSecondary)
                textSize = 13f
                setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            })
        }
    }

    private data class AppRow(
        val label: String,
        val launch: () -> Unit,
    )

    private fun loadApps(ctx: Context): List<AppRow> {
        val launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return emptyList()
        val user = Process.myUserHandle()
        return runCatching {
            launcherApps.getActivityList(null, user)
                .map { info ->
                    val label = info.label?.toString().orEmpty()
                    val component = info.componentName
                    AppRow(
                        label = label.ifBlank { component.packageName },
                        launch = {
                            launcherApps.startMainActivity(component, user, null, null)
                        },
                    )
                }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = MinimalistBlackFragment() }
}
