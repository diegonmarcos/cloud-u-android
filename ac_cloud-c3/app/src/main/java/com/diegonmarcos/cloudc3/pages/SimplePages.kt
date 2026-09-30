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
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.cloudc3.BuildConfig
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.cloudc3.cloud.ContainerSheet
import com.diegonmarcos.cloudc3.cloud.FleetDown
import com.diegonmarcos.cloudc3.cloud.Services
import com.diegonmarcos.cloudc3.cloud.StatusLight
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

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
 * FIRST, every declared container that is DOWN across the fleet ([FleetDown]), grouped by
 * VM; a row opens the same [ContainerSheet] the Topology dashboards open, so start / stop /
 * restart / logs are one implementation. The fleet runs with compose restart "no", so a box
 * that exits stays exited until someone looks — this card is where they look. It has three
 * honest faces: the API did not answer (said so, with its reason), some VMs could not be
 * listed (named, with their declared count, never folded into "up"), or the list — and an
 * EMPTY list only ever means every declared container was seen running.
 *
 * Below it, a summary of the other tabs' DECLARATIONS plus the real size of the estate, so
 * it cannot drift from what the app offers: declaring a page adds it here with no edit.
 */
class HomeFragment : Fragment() {

    private var downBox: LinearLayout? = null

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

        downBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(downBox)

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        measure()
    }

    override fun onDestroyView() {
        downBox = null
        super.onDestroyView()
    }

    /** Re-measured on open, on Refresh, and whenever a container sheet closes. */
    private fun measure() {
        val box = downBox ?: return
        val ctx = box.context
        val declared = FleetDown.declared()
        box.removeAllViews()
        box.addView(card(ctx, getString(R.string.down_title), listOf(
            getString(R.string.down_measuring, declared.size, declared.map { it.vm }.distinct().size))))
        val bearer = runCatching { DaguPrefs(ctx).bearerToken }.getOrDefault("")
        viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { FleetDown.load(bearer) }
            render(report)
        }
    }

    private fun render(report: FleetDown.Report) {
        val box = downBox ?: return
        val ctx = box.context
        box.removeAllViews()
        val c = card(ctx, getString(R.string.down_title), emptyList())
        box.addView(c)
        fun line(text: String, light: StatusLight.State? = null, onTap: (() -> Unit)? = null) =
            c.addView(TextView(ctx).apply {
                this.text = text
                setTextAppearance(android.R.style.TextAppearance_Material_Small)
                setTextColor(light?.let { StatusLight.colour(ctx, it) }
                    ?: ContextCompat.getColor(ctx, R.color.c3_text_secondary))
                val d = ctx.resources.displayMetrics.density
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                setTextIsSelectable(onTap == null)
                onTap?.let { tap -> isClickable = true; setOnClickListener { tap() } }
            })

        when (report) {
            is FleetDown.Report.Unreachable -> line(
                getString(R.string.down_unreachable, report.kind.name, report.detail), StatusLight.State.UNKNOWN)
            FleetDown.Report.NothingDeclared -> line(getString(R.string.down_nothing_declared), StatusLight.State.UNKNOWN)
            is FleetDown.Report.Measured -> {
                val downCount = report.down.values.sumOf { it.size }
                if (downCount == 0 && report.blind.isEmpty()) {
                    line(getString(R.string.down_all_up, report.declared, report.declaredPerVm.size), StatusLight.State.ON)
                } else {
                    line(getString(R.string.down_summary, downCount, report.declared))
                }
                for (vm in (report.down.keys + report.blind.keys).sorted()) {
                    report.blind[vm]?.let {
                        line(getString(R.string.down_vm_blind, vm, report.declaredPerVm[vm] ?: 0, it),
                             StatusLight.State.UNKNOWN)
                    }
                    val rows = report.down[vm] ?: continue
                    line(getString(R.string.down_vm_heading, vm, rows.size))
                    for (r in rows) {
                        line(getString(R.string.down_row, StatusLight.glyph(StatusLight.State.OFF), r.name,
                                       ContainerSheet.stateText(ctx, r.state)), StatusLight.State.OFF) {
                            ContainerSheet.show(requireActivity(), r.name, r.name, "", onDismiss = ::measure)
                        }
                    }
                }
            }
        }
        line(getString(R.string.down_refresh,
                       DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())), onTap = ::measure)
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
private fun card(ctx: Context, title: String, lines: List<String>): LinearLayout {
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
