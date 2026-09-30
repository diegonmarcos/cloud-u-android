package com.diegonmarcos.cloudc3.pages

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudc3.BuildConfig
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.cloudc3.cloud.Services

/**
 * #648 the two tabs that are a single page each: Home and Configs ▸ About.
 *
 * Both report only what they can MEASURE. Home counts the declared estate and the declared
 * pages; About reads what gradle baked. Neither claims a status it has not established —
 * on an ops surface a confident number nobody verified is the defect, not the feature.
 */

/**
 * HOME — the centre tab, the position every fleet five-tab shell gives it.
 *
 * A summary of the other tabs' DECLARATIONS plus the real size of the estate, so it cannot
 * drift from what the app offers: declaring a page adds it here with no edit. The live
 * per-service state belongs to the tab that measures it and arrives when those pages do;
 * Home will read the SAME state they render rather than fetching its own, which is the rule
 * that stops two screens disagreeing about one machine.
 */
class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        }

        val pub = Services.publicServices().size
        val priv = Services.privateServices().size

        col.addView(
            card(
                ctx,
                label("topology"),
                listOf(
                    getString(R.string.c3_health_section_public, pub),
                    getString(R.string.c3_health_section_private, priv),
                ),
            ),
        )
        col.addView(
            card(
                ctx,
                label("observ"),
                Declarations.observPages.map { it.label },
            ),
        )
        col.addView(
            card(
                ctx,
                label("apps"),
                Declarations.externalApps.map { "${it.display} · ${it.packageName}" },
            ),
        )

        return ScrollView(ctx).apply {
            isFillViewport = true
            addView(
                col,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /** A card title names a TAB, so it is read from the tab declaration, never spelled. */
    private fun label(tabId: String): String =
        Declarations.tabs.firstOrNull { it.id == tabId }?.label ?: tabId
}

/** CONFIGS ▸ ABOUT — everything here was baked by gradle, so it reports and never guesses. */
class AboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        }
        col.addView(
            card(
                ctx,
                getString(R.string.about_title),
                listOf(
                    "${getString(R.string.about_version)}: ${BuildConfig.VERSION_NAME}",
                    "${getString(R.string.about_commit)}: ${BuildConfig.GIT_SHORT_SHA}",
                    "${getString(R.string.about_build)}: ${BuildConfig.BUILD_TIMESTAMP}",
                ),
            ),
        )
        return ScrollView(ctx).apply {
            isFillViewport = true
            addView(
                col,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }
}

/**
 * The one card shape both pages use. Declared here once rather than per page, for the same
 * reason the colours live in colors.xml: a second copy is a second thing to keep in step.
 */
private fun card(ctx: Context, title: String, lines: List<String>): View {
    val d = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * d).toInt()
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(12), px(12), px(12), px(12))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = px(12).toFloat()
            setColor(ContextCompat.getColor(ctx, R.color.c3_surface))
            setStroke(px(1), ContextCompat.getColor(ctx, R.color.c3_outline))
        }
        addView(
            TextView(ctx).apply {
                text = title
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
                setTextColor(ContextCompat.getColor(ctx, R.color.c3_accent_on_card))
            },
        )
        for (line in lines) {
            addView(
                TextView(ctx).apply {
                    text = line
                    setTextAppearance(android.R.style.TextAppearance_Material_Small)
                    setTextColor(ContextCompat.getColor(ctx, R.color.c3_text_secondary))
                    setPadding(0, px(4), 0, 0)
                },
            )
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.bottomMargin = px(8)
        layoutParams = lp
    }
}
