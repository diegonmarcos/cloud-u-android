package com.diegonmarcos.cloudc3.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudc3.AppsCatalog
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * The Apps tab's Services and Tools pages (VIEW-ONLY). One class, two kinds: a list of the
 * declared services or tools, and a detail for one of them. The detail's cross-links
 * (service -> its tools, tool -> the services using it) open in place, so the two pages
 * link without a second navigation stack; the Back row returns to the page's own list.
 * Everything shown is read from ui.apps_page and the derived infra slice.
 *
 * 2026-10-15 owner decision: all apps must be Compose — this page is a ComposeView host.
 */
class CatalogFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val kind = arguments?.getString(ARG_KIND) ?: KIND_SERVICES
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { CloudKitTheme(c3KitPalette(context)) { Catalog(kind) } }
        }
    }

    @Composable
    private fun Catalog(kind: String) {
        // The open detail survives rotation and process death, as the old onSaveInstanceState did.
        var openKind by rememberSaveable { mutableStateOf<String?>(null) }
        var openId by rememberSaveable { mutableStateOf<String?>(null) }
        val scroll = rememberScrollState()
        LaunchedEffect(openKind, openId) { scroll.scrollTo(0) }
        fun open(k: String?, id: String?) { openKind = k; openId = id }

        val decl = Declarations.appsCatalog
        val slice = Declarations.infraSlice
        val id = openId
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(KitDensity.medium)) {
            when {
                id == null && kind == KIND_SERVICES -> decl.services.forEach { s ->
                    Row(s.label, "${s.tools.size} tools - ${s.views.size} views") { open(KIND_SERVICES, s.id) }
                }
                id == null -> decl.tools.forEach { t ->
                    val users = decl.usedBy(t.id)
                    Row(t.display, "used by " + users.joinToString(", ") { it.label }.ifBlank { "nothing" }) { open(KIND_TOOLS, t.id) }
                }
                openKind == KIND_SERVICES -> {
                    val s = decl.service(id)
                    Row("< Back", "") { open(null, null) }
                    if (s != null) {
                        Title(s.label)
                        Subheading("Tools it uses")
                        s.tools.mapNotNull { decl.tool(it) }.forEach { t ->
                            Row(t.display, "tap to view this tool") { open(KIND_TOOLS, t.id) }
                        }
                        s.views.forEach { v -> AppsCatalog.view(slice, v).forEach { Block(it) } }
                    }
                }
                else -> {
                    val t = decl.tool(id)
                    Row("< Back", "") { open(null, null) }
                    if (t != null) {
                        Title(t.display)
                        AppsCatalog.toolBlocks(slice, t.id).forEach { Block(it) }
                        Subheading("Used by services")
                        decl.usedBy(t.id).forEach { s -> Row(s.label, "tap to view this service") { open(KIND_SERVICES, s.id) } }
                    }
                }
            }
        }
    }

    @Composable
    private fun Title(t: String) {
        Text(t, color = LocalKitPalette.current.textPrimary, fontSize = KitDensity.head,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = KitDensity.small))
    }

    @Composable
    private fun Subheading(t: String) {
        Text(t, color = LocalKitPalette.current.accent, fontSize = KitDensity.caption,
            modifier = Modifier.padding(start = 4.dp, top = KitDensity.medium, end = 4.dp, bottom = 4.dp))
    }

    @Composable
    private fun Row(name: String, detail: String, onTap: () -> Unit) {
        val p = LocalKitPalette.current
        C3Card(onClick = onTap) {
            Text(name, color = p.textPrimary, fontSize = KitDensity.title)
            if (detail.isNotBlank()) Text(detail, color = p.textSecondary, fontSize = KitDensity.caption)
        }
    }

    @Composable
    private fun Block(b: AppsCatalog.Block) {
        val p = LocalKitPalette.current
        C3Card {
            Text(b.title, color = p.accent, fontSize = KitDensity.title)
            SelectionContainer {
                Column {
                    b.rows.forEach { (k, v) ->
                        Text(if (v.isBlank()) k else "$k  $v", color = p.textSecondary, fontSize = KitDensity.caption,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }

    companion object {
        const val KIND_SERVICES = "services"
        const val KIND_TOOLS = "tools"
        private const val ARG_KIND = "kind"
        fun newInstance(kind: String) = CatalogFragment().apply { arguments = Bundle().apply { putString(ARG_KIND, kind) } }
    }
}
