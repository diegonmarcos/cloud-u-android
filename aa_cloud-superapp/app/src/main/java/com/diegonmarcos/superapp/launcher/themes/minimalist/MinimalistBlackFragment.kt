package com.diegonmarcos.superapp.launcher.themes.minimalist

import com.diegonmarcos.superapp.system.ScreenLocker
import com.diegonmarcos.superapp.ui.Haptics

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment

/**
 * The Cloud Minimalist Black mode's home screen: every installed app as one
 * line of terminal output, with a live filter.
 *
 * ── What changed, and why ────────────────────────────────────────────────
 * This screen used to draw itself through the shared
 * `com.diegonmarcos.superapp.ui.LauncherPalette`, which meant its colours,
 * its type sizes and its paddings all came from the same place the default
 * launcher's did. The owner's rule — "all themes have their own folders, all
 * different, nothing can be equal" — makes that the wrong dependency: a mode
 * that shares a palette can only ever be a recolouring of whatever else uses
 * it. Everything visual now comes from [MinimalistDesign], which this folder
 * owns, and there is no palette in scope here to borrow from by accident.
 *
 * Behaviour is deliberately unchanged: same app source, same filter, same
 * double-tap-to-lock. This was a redesign, not a rewrite of what it does.
 */
class MinimalistBlackFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private var allApps: List<AppRow> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = MinimalistDesign.page(ctx)

        root.addView(MinimalistDesign.head(ctx, "~/apps"))
        root.addView(MinimalistDesign.space(ctx, MinimalistDesign.GAP))

        // The filter is the only input in this mode, so it is styled as a
        // continuation of the prompt rather than as a form field: no border,
        // no container, same face and same colour as everything above it.
        root.addView(
            EditText(ctx).apply {
                hint = "${MinimalistDesign.PROMPT}filter"
                setHintTextColor(MinimalistDesign.INK_DIM)
                setTextColor(MinimalistDesign.INK)
                typeface = MinimalistDesign.FACE
                textSize = MinimalistDesign.TYPE_BODY
                background = null
                setSingleLine(true)
                setPadding(0, 0, 0, MinimalistDesign.dp(ctx, MinimalistDesign.GAP))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: Editable?) {
                        rebuildList(e?.toString().orEmpty())
                    }
                })
            },
        )
        root.addView(MinimalistDesign.rule(ctx))

        listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MinimalistDesign.GROUND)
        }
        root.addView(
            ScrollView(ctx).apply {
                setBackgroundColor(MinimalistDesign.GROUND)
                // No over-scroll glow: the glow is the default theme's accent
                // and it used to flash at the top of this otherwise-green screen.
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
                )
                addView(listContainer)
            },
        )

        root.addView(MinimalistDesign.rule(ctx))
        root.addView(MinimalistDesign.space(ctx, MinimalistDesign.GAP_TIGHT))
        root.addView(MinimalistDesign.hint(ctx, "tap to launch  ·  double-tap to lock"))

        // Edge-to-edge: pad the CONTENT so the black still reaches the screen
        // edges and only the text clears the status and navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            root.setPadding(
                MinimalistDesign.dp(ctx, MinimalistDesign.GAP_WIDE),
                bars.top + MinimalistDesign.dp(ctx, MinimalistDesign.GAP),
                MinimalistDesign.dp(ctx, MinimalistDesign.GAP_WIDE),
                bars.bottom + MinimalistDesign.dp(ctx, MinimalistDesign.GAP),
            )
            insets
        }

        // LauncherApps.getActivityList is fast on a populated device but not
        // free — it stays on the UI thread because the system already caches
        // it internally and a background hop would only add a frame of blank.
        allApps = loadApps(ctx)
        rebuildList("")

        // Double-tap anywhere on the home root → lock the screen. App rows
        // still receive single taps via their own handlers; the root listener
        // only sees events the child rows did not claim.
        ScreenLocker.attachDoubleTapLock(root)

        return root
    }

    private fun rebuildList(filter: String) {
        listContainer.removeAllViews()
        val ctx = listContainer.context
        val needle = filter.trim().lowercase()
        val visible =
            if (needle.isBlank()) allApps
            else allApps.filter { it.label.lowercase().contains(needle) }

        visible.forEach { app ->
            listContainer.addView(
                MinimalistDesign.line(ctx, app.label) {
                    Haptics.tap(listContainer)
                    runCatching { app.launch() }
                },
            )
        }
        if (visible.isEmpty()) {
            listContainer.addView(MinimalistDesign.space(ctx, MinimalistDesign.GAP))
            listContainer.addView(MinimalistDesign.hint(ctx, "— no matches —"))
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

    companion object {
        fun newInstance() = MinimalistBlackFragment()
    }
}
