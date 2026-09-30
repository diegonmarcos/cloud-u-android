package com.diegonmarcos.cloudc3.cloud

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudc3.R

/**
 * C3 · Health — fed from data/services_*.json snapshots (the SuperApp's data/regen.sh
 * derives them from cloud-data's _cloud-data-consolidated.json).
 *
 * MOVED from aa_cloud-superapp (#648), body unchanged. Three things had to change and
 * nothing else did: the package, the R it resolves against, and the two collaborators it
 * reached for as SuperApp singletons — Sections.publicServices()/privateServices() is now
 * [Services], and the row tap went through the launcher's TileGridFragment.TileClickListener,
 * which does not exist in an app with no tile grid, so it is [UrlClickListener] here. The
 * rendering, the colours, the emoji, the scope rules and the row layout are the ones that
 * shipped, because this is a spin-off: the pages were already designed.
 *
 * `scope` controls which tables render:
 *   - "all"     -> both Public and Private (the Observ tab's Health page)
 *   - "public"  -> only Public  (the Topology tab's Public addresses table)
 *   - "private" -> only Private (the Topology tab's Private addresses table)
 */
class C3HealthFragment : Fragment(R.layout.fragment_c3_health) {

    /** What a row tap does. The host decides; this fragment only reports the URL. */
    interface UrlClickListener {
        fun onUrlClicked(url: String)
    }

    private val scope: String
        get() = arguments?.getString(ARG_SCOPE) ?: SCOPE_ALL

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val root = view.findViewById<LinearLayout>(R.id.health_root)
        val status = view.findViewById<TextView>(R.id.health_status)
        val spinner = view.findViewById<ProgressBar>(R.id.health_loading)
        spinner.isVisible = false

        val showPub = scope == SCOPE_ALL || scope == SCOPE_PUBLIC
        val showPriv = scope == SCOPE_ALL || scope == SCOPE_PRIVATE

        val pub = if (showPub) Services.publicServices() else emptyList()
        val priv = if (showPriv) Services.privateServices() else emptyList()

        status.text = when (scope) {
            SCOPE_PUBLIC -> getString(R.string.c3_health_section_public, pub.size)
            SCOPE_PRIVATE -> getString(R.string.c3_health_section_private, priv.size)
            else -> getString(R.string.c3_health_status_split, pub.size, priv.size)
        }
        // When embedded as a single-scope card the bare status line above a section
        // header is redundant — hide it.
        status.isVisible = scope == SCOPE_ALL

        val inflater = LayoutInflater.from(requireContext())

        if (pub.isNotEmpty()) {
            if (scope == SCOPE_ALL) {
                addSectionHeader(root, getString(R.string.c3_health_section_public, pub.size))
            }
            for (svc in pub) {
                val row = inflater.inflate(R.layout.item_c3_health_row, root, false)
                row.findViewById<View>(R.id.h_status_dot).background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF2E7D32.toInt())   // green = public
                }
                row.findViewById<TextView>(R.id.h_name).text = svc.name
                row.findViewById<TextView>(R.id.h_auth).text = "auth: ${svc.auth.ifBlank { "—" }}"
                row.findViewById<TextView>(R.id.h_domain).text = "🌐 https://${svc.publicUrl}"
                row.findViewById<TextView>(R.id.h_private).text =
                    if (svc.privateDns.isNotBlank()) "🔒 ${svc.privateDns}" else "—"
                row.findViewById<TextView>(R.id.h_vm).text = svc.vm
                row.setOnClickListener {
                    (activity as? UrlClickListener)?.onUrlClicked("https://${svc.publicUrl}")
                }
                root.addView(row)
            }
        }

        if (priv.isNotEmpty()) {
            if (scope == SCOPE_ALL) {
                addSectionHeader(root, getString(R.string.c3_health_section_private, priv.size))
            }
            for (svc in priv) {
                val row = inflater.inflate(R.layout.item_c3_health_row, root, false)
                row.findViewById<View>(R.id.h_status_dot).background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF1565C0.toInt())   // blue = private
                }
                row.findViewById<TextView>(R.id.h_name).text = svc.name
                row.findViewById<TextView>(R.id.h_auth).text = buildString {
                    append(svc.protocol)
                    if (svc.dbEngine.isNotBlank()) { append(" · "); append(svc.dbEngine) }
                }
                row.findViewById<TextView>(R.id.h_domain).text = svc.service.ifBlank { "—" }
                row.findViewById<TextView>(R.id.h_private).text = "🔒 ${svc.privateDns}"
                row.findViewById<TextView>(R.id.h_vm).text = svc.vm
                root.addView(row)
            }
        }
    }

    private fun addSectionHeader(parent: LinearLayout, label: String) {
        val tv = TextView(requireContext()).apply {
            text = label
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            setTextColor(resources.getColor(R.color.c3_accent, requireContext().theme))
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad * 2, pad, pad / 2)
        }
        parent.addView(tv)
    }

    companion object {
        const val SCOPE_ALL = "all"
        const val SCOPE_PUBLIC = "public"
        const val SCOPE_PRIVATE = "private"
        private const val ARG_SCOPE = "scope"

        fun newInstance(scope: String = SCOPE_ALL): C3HealthFragment =
            C3HealthFragment().apply {
                arguments = Bundle().apply { putString(ARG_SCOPE, scope) }
            }
    }
}
