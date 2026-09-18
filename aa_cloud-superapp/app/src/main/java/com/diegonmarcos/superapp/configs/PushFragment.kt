package com.diegonmarcos.superapp.configs

import android.content.Context
import android.os.Base64
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.LauncherPalette
import org.json.JSONObject

/**
 * Configs ▸ Panel ▸ Push (#497) — every notification-center / badge producer
 * this app ships, drawn from `build.json::ui.notification_center` and NOTHING
 * this file invents.
 *
 * Diego asked for the KDE badge, the music badge, the quickmarks badge and
 * trailed off with "…." — that trailing-off is the whole reason this page
 * iterates [producers] rather than drawing three rows by name: adding or
 * dropping a producer in the declaration changes this page with no Kotlin
 * edit, same contract [ControlFragment] keeps with `DeviceControls`.
 *
 * The split is the same one Control uses: this file owns PRESENTATION only —
 * it reads what a producer IS (label, subtitle, icon, which surface it
 * occupies, whether it is declared on) and draws exactly that. The
 * CAPABILITY — the actual channel, the actual notify()/cancel() calls — stays
 * in the producer's own Kotlin file, named as `owner` in the declaration.
 * Nothing here polls a live device state, so unlike Control there is no
 * ticker: a producer's declared `enabled` is a build-time fact, not something
 * that can go stale between reads.
 */
class PushFragment : Fragment() {

    private data class Producer(
        val id: String,
        val label: String,
        val subtitle: String,
        val icon: String,
        val surface: String,
        val enabled: Boolean,
    )

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val pad = dp(16)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title(ctx, getString(R.string.push_title)))
        root.addView(caption(ctx, getString(R.string.push_caption)))

        val producers = declaredProducers()
        if (producers.isEmpty()) {
            root.addView(caption(ctx, getString(R.string.push_none_declared)))
        } else {
            for (p in producers) root.addView(row(ctx, p))
        }

        return ScrollView(ctx).apply { addView(root) }
    }

    // ─────────────────────────────── Rows ───────────────────────────────

    private fun row(ctx: Context, p: Producer): View {
        val palette = LauncherPalette.of(ctx)
        val v = dp(8)

        val icon = ImageView(ctx).apply {
            setImageResource(Sections.iconResFor(ctx, p.icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply {
                marginEnd = dp(12)
            }
            imageTintList = android.content.res.ColorStateList.valueOf(palette.textPrimary)
        }

        val label = TextView(ctx).apply {
            text = p.label
            textSize = 15f
            setTextColor(palette.textPrimary)
        }

        val subtitle = TextView(ctx).apply {
            text = p.subtitle
            textSize = 12f
            setTextColor(palette.textSecondary)
        }

        val state = TextView(ctx).apply {
            text = getString(if (p.enabled) R.string.push_producer_on else R.string.push_producer_off)
            textSize = 11f
            setTextColor(if (p.enabled) palette.accent else palette.textSecondary)
        }

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(label)
            addView(subtitle)
            addView(state)
        }

        // ONE announcement per row, not four — the icon and the three text
        // lines are one fact about one producer.
        for (child in listOf(icon, label, subtitle, state)) {
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, v, 0, v)
            isFocusable = true
            contentDescription = "${p.label}. ${p.subtitle} " +
                getString(if (p.enabled) R.string.push_producer_on else R.string.push_producer_off)
            addView(icon)
            addView(texts)
        }
    }

    private fun title(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        textSize = 22f
        setTextColor(LauncherPalette.of(ctx).textPrimary)
        setPadding(0, 0, 0, dp(4))
    }

    private fun caption(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        textSize = 12f
        setTextColor(LauncherPalette.of(ctx).textSecondary)
        setPadding(0, 0, 0, dp(8))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ────────────────────────────── Declaration ──────────────────────────

    /**
     * `build.json::ui.notification_center.producers`, baked into BuildConfig
     * at build time — the SAME mechanism `DeviceControls.declaration` uses
     * for `ui.control_panel`. A producer missing a field is dropped rather
     * than drawn half-blank: a row with no label or icon is not a producer,
     * it is a parse error nobody would notice on a settings page.
     */
    private fun declaredProducers(): List<Producer> {
        val arr = declaration.optJSONArray("producers") ?: return emptyList()
        val out = mutableListOf<Producer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val label = o.optString("label")
            if (id.isBlank() || label.isBlank()) continue
            out += Producer(
                id = id,
                label = label,
                subtitle = o.optString("subtitle", ""),
                icon = o.optString("icon", ""),
                surface = o.optString("surface", ""),
                enabled = o.optBoolean("enabled", false),
            )
        }
        return out
    }

    private val declaration: JSONObject by lazy {
        runCatching {
            JSONObject(String(Base64.decode(BuildConfig.UI_NOTIFICATION_CENTER_B64, Base64.NO_WRAP)))
        }.getOrDefault(JSONObject())
    }

    companion object {
        private const val ICON_DP = 28

        fun newInstance(): PushFragment = PushFragment()
    }
}
