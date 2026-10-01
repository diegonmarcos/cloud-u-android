package com.diegonmarcos.superapp.appstore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment

/**
 * #733 Configs ▸ Watchdog ▸ Mesh ▸ Apps Mesh (page id `apps-mesh`). The SAME
 * page Store ▸ Apps Mesh draws — both call [AppsMesh.page] — hosted on its own.
 * There is no Store row to open from here, so `store` is not offered.
 */
class AppsMeshFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (14 * ctx.resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        }
        AppsMesh.page(this, col)
        return ScrollView(ctx).apply { addView(col) }
    }
}
