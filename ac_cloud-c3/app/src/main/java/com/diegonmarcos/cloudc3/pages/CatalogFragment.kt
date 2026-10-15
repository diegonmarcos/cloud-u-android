package com.diegonmarcos.cloudc3.pages

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
import com.diegonmarcos.cloudc3.AppsCatalog
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R

/**
 * The Apps tab's Services and Tools pages (VIEW-ONLY). One class, two kinds: a list of the
 * declared services or tools, and a detail for one of them. The detail's cross-links
 * (service -> its tools, tool -> the services using it) open in place, so the two pages
 * link without a second navigation stack; the Back row returns to the page's own list.
 * Everything shown is read from ui.apps_page and the derived infra slice.
 */
class CatalogFragment : Fragment() {

    private lateinit var list: LinearLayout
    private var kind = KIND_SERVICES
    private var openKind: String? = null
    private var openId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        kind = arguments?.getString(ARG_KIND) ?: KIND_SERVICES
        openKind = savedInstanceState?.getString(S_KIND)
        openId = savedInstanceState?.getString(S_ID)
        val ctx = requireContext()
        list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        paint()
        return ScrollView(ctx).apply {
            isFillViewport = true
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(S_KIND, openKind)
        outState.putString(S_ID, openId)
    }

    private fun open(k: String?, id: String?) {
        openKind = k; openId = id
        paint()
        (list.parent as? ScrollView)?.scrollTo(0, 0)
    }

    private fun paint() {
        list.removeAllViews()
        val decl = Declarations.appsCatalog
        val slice = Declarations.infraSlice
        val id = openId
        when {
            id == null && kind == KIND_SERVICES -> decl.services.forEach { s ->
                list.addView(row(s.label, "${s.tools.size} tools - ${s.views.size} views") { open(KIND_SERVICES, s.id) })
            }
            id == null -> decl.tools.forEach { t ->
                val users = decl.usedBy(t.id)
                list.addView(row(t.display, "used by " + users.joinToString(", ") { it.label }.ifBlank { "nothing" }) { open(KIND_TOOLS, t.id) })
            }
            openKind == KIND_SERVICES -> {
                val s = decl.service(id)
                list.addView(row("< Back", "") { open(null, null) })
                if (s != null) {
                    list.addView(title(s.label))
                    list.addView(subheading("Tools it uses"))
                    s.tools.mapNotNull { decl.tool(it) }.forEach { t ->
                        list.addView(row(t.display, "tap to view this tool") { open(KIND_TOOLS, t.id) })
                    }
                    s.views.forEach { v -> AppsCatalog.view(slice, v).forEach { list.addView(block(it)) } }
                }
            }
            else -> {
                val t = decl.tool(id)
                list.addView(row("< Back", "") { open(null, null) })
                if (t != null) {
                    list.addView(title(t.display))
                    AppsCatalog.toolBlocks(slice, t.id).forEach { list.addView(block(it)) }
                    list.addView(subheading("Used by services"))
                    decl.usedBy(t.id).forEach { s -> list.addView(row(s.label, "tap to view this service") { open(KIND_SERVICES, s.id) }) }
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(t: String) = TextView(requireContext()).apply {
        text = t
        setTextAppearance(android.R.style.TextAppearance_Material_Title)
        setTextColor(ContextCompat.getColor(context, R.color.c3_text))
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun subheading(t: String) = TextView(requireContext()).apply {
        text = t
        setTextAppearance(android.R.style.TextAppearance_Material_Small)
        setTextColor(ContextCompat.getColor(context, R.color.c3_accent_on_card))
        setPadding(dp(4), dp(12), dp(4), dp(4))
    }

    private fun card(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(ContextCompat.getColor(context, R.color.c3_surface))
            setStroke(dp(1), ContextCompat.getColor(context, R.color.c3_outline))
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) }
    }

    private fun text(t: String, secondary: Boolean) = TextView(requireContext()).apply {
        text = t
        setTextAppearance(if (secondary) android.R.style.TextAppearance_Material_Small else android.R.style.TextAppearance_Material_Subhead)
        setTextColor(ContextCompat.getColor(context, if (secondary) R.color.c3_text_secondary else R.color.c3_text))
    }

    private fun row(name: String, detail: String, onTap: () -> Unit): View = card().apply {
        addView(text(name, false))
        if (detail.isNotBlank()) addView(text(detail, true))
        isClickable = true
        setOnClickListener { onTap() }
    }

    private fun block(b: AppsCatalog.Block): View = card().apply {
        addView(TextView(context).apply {
            text = b.title
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(ContextCompat.getColor(context, R.color.c3_accent_on_card))
        })
        b.rows.forEach { (k, v) ->
            addView(text(if (v.isBlank()) k else "$k  $v", true).apply { setPadding(0, dp(4), 0, 0); setTextIsSelectable(true) })
        }
    }

    companion object {
        const val KIND_SERVICES = "services"
        const val KIND_TOOLS = "tools"
        private const val ARG_KIND = "kind"
        private const val S_KIND = "open_kind"
        private const val S_ID = "open_id"
        fun newInstance(kind: String) = CatalogFragment().apply { arguments = Bundle().apply { putString(ARG_KIND, kind) } }
    }
}
