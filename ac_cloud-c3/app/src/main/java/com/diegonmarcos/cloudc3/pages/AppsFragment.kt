package com.diegonmarcos.cloudc3.pages

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R

/**
 * #648 THE APPS TAB — the three C3 sibling APKs.
 *
 * All three are already standalone fleet members with their own directory, build.json, ship
 * workflow and applicationId, so this tab LAUNCHES them and declares no page of its own.
 * Nothing here knows what any of the three DOES.
 *
 * Each row RESOLVES its package against the device rather than assuming it is installed —
 * AndroidManifest declares all three under <queries>, without which API 30+ reports an
 * uninstalled-looking null for an app that is present, and the row's "Not installed" would
 * be a lie. Resolution happens in onResume, because the usual reason to come back to this
 * tab is having just installed one.
 *
 * The printed name is DERIVED from the fleet name ([Declarations.ExternalAppDecl.display]):
 * c3-watchdog reads "Watchdog". No display label is stored anywhere (#351/#224).
 */
class AppsFragment : Fragment() {

    private lateinit var list: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        }
        return ScrollView(ctx).apply {
            isFillViewport = true
            addView(
                list,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        paint()
    }

    private fun paint() {
        val ctx = requireContext()
        list.removeAllViews()
        for (tile in Declarations.externalApps) {
            val installed = isInstalled(ctx, tile.packageName)
            list.addView(row(ctx, tile, installed))
        }
    }

    private fun row(
        ctx: Context,
        tile: Declarations.ExternalAppDecl,
        installed: Boolean,
    ): View {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val name = TextView(ctx).apply {
            text = tile.display
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(ContextCompat.getColor(ctx, R.color.c3_text))
        }
        val state = TextView(ctx).apply {
            text = getString(if (installed) R.string.apps_open else R.string.apps_not_installed)
            setTextAppearance(android.R.style.TextAppearance_Material_Small)
            setTextColor(ContextCompat.getColor(ctx, R.color.c3_text_secondary))
        }
        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(state)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(12), px(12), px(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(12).toFloat()
                setColor(ContextCompat.getColor(ctx, R.color.c3_surface))
                setStroke(px(1), ContextCompat.getColor(ctx, R.color.c3_outline))
            }
            @Suppress("DiscouragedApi")
            val icon = ctx.resources.getIdentifier(tile.icon, "drawable", ctx.packageName)
            if (icon != 0) {
                name.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                name.compoundDrawablePadding = px(8)
            }
            addView(
                text,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (installed) {
                isClickable = true
                setOnClickListener { launch(ctx, tile.packageName, tile.display) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = px(8)
            layoutParams = lp
        }
    }

    /**
     * "Installed" here means precisely "openable": resolved through the launch intent rather
     * than getPackageInfo, because an app with no launcher activity could not be opened by
     * this row even if it were present. Never throws — an invisible or absent package is
     * false, which is what the row then says.
     */
    private fun isInstalled(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) != null }
            .getOrDefault(false)

    /** Opens the sibling, and says so when it cannot rather than failing silently. */
    private fun launch(ctx: Context, pkg: String, label: String) {
        val intent = runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        if (intent == null) {
            Toast.makeText(
                ctx,
                getString(R.string.apps_launch_failed, label),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(
                ctx,
                getString(R.string.apps_launch_failed, label),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
