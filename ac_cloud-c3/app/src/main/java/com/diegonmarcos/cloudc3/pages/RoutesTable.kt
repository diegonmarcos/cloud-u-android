package com.diegonmarcos.cloudc3.pages

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.cloud.C3HealthFragment
import com.diegonmarcos.cloudc3.pages.RoutesTableModel.Column as Col
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette

/** Test tags of the Table page, for the Compose suite. */
object RoutesTags {
    const val SCREEN = "routes_screen"
    const val COUNT = "routes_count"
    const val LIST = "routes_list"
    fun header(c: Col) = "routes_header_${c.key}"
    fun cell(c: Col) = "routes_cell_${c.key}"
    fun authChip(id: String) = "routes_auth_$id"
    fun vmChip(vm: String) = "routes_vm_$vm"
}

/** Where the chosen sort and filters live between launches. */
interface RoutesStateStore {
    fun load(): RoutesTableModel.State
    fun save(state: RoutesTableModel.State)
}

/** SharedPreferences-backed store; every access is guarded, a failure is the default state. */
class PrefsRoutesStateStore(context: Context) : RoutesStateStore {
    private val prefs = context.applicationContext.getSharedPreferences("c3_routes_table", Context.MODE_PRIVATE)
    override fun load() = RoutesTableModel.decode(runCatching { prefs.getString(KEY, null) }.getOrNull())
    override fun save(state: RoutesTableModel.State) {
        runCatching { prefs.edit().putString(KEY, RoutesTableModel.encode(state)).apply() }
    }
    private companion object { const val KEY = "state" }
}

private val widths = mapOf(
    Col.CATEGORY to 76.dp, Col.CONTAINER to 150.dp, Col.PUBLIC_URL to 250.dp, Col.AUTH_TYPE to 84.dp,
    Col.PRIVATE_URL to 190.dp, Col.VM_NAME to 100.dp, Col.VM_ADDR to 130.dp,
)
private val headers = mapOf(
    Col.CATEGORY to "Category", Col.CONTAINER to "Container", Col.PUBLIC_URL to "Public URL", Col.AUTH_TYPE to "Auth Type",
    Col.PRIVATE_URL to "Private URL", Col.VM_NAME to "VM Name", Col.VM_ADDR to "VM IP:PORT",
)

/**
 * Containers > Table: one row per container with Category, Container, Public URL, Auth Type, Private URL, VM Name and
 * VM IP:PORT. A header tap sorts by that column (again to flip, with a triangle for the direction); Auth Type and VM
 * are multi-select chips; the count line says how many rows the filters leave. [initial] and [onState] carry the
 * sort and filters so the host can persist them.
 */
@Composable
fun RoutesTableScreen(
    rows: List<RoutesTableModel.Row>,
    initial: RoutesTableModel.State = RoutesTableModel.State(),
    onState: (RoutesTableModel.State) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    var state by remember { mutableStateOf(initial) }
    fun update(s: RoutesTableModel.State) { state = s; onState(s) }
    val shown = remember(rows, state) { RoutesTableModel.view(rows, state) }
    val scroll = rememberScrollState()
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().testTag(RoutesTags.SCREEN)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            ChipRow("Auth") {
                RoutesTableModel.authOptions(rows).forEach { a ->
                    Chip(a.label, a.id in state.auth, RoutesTags.authChip(a.id)) { update(RoutesTableModel.toggleAuth(state, a.id)) }
                }
            }
            ChipRow("VM") {
                RoutesTableModel.vmOptions(rows).forEach { vm ->
                    Chip(vm, vm in state.vms, RoutesTags.vmChip(vm)) { update(RoutesTableModel.toggleVm(state, vm)) }
                }
            }
            Text(
                RoutesTableModel.countLine(shown.size, rows.size),
                Modifier.padding(top = 2.dp).testTag(RoutesTags.COUNT),
                color = c.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
            )
        }
        Row(Modifier.fillMaxWidth().background(c.surfaceVariant).horizontalScroll(scroll)) {
            Col.entries.forEach { col ->
                val active = state.sort == col
                val mark = if (!active) "" else if (state.ascending) " ▲" else " ▼"
                Text(
                    headers.getValue(col) + mark,
                    Modifier.width(widths.getValue(col)).height(30.dp)
                        .clickable(role = Role.Button) { update(RoutesTableModel.toggleSort(state, col)) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag(RoutesTags.header(col))
                        .semantics { selected = active },
                    color = if (active) c.primary else c.onSurface,
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag(RoutesTags.LIST)) {
            itemsIndexed(shown, key = { i, r -> "$i/${r.container}/${r.publicUrl}/${r.privateUrl}" }) { _, r ->
                Row(Modifier.fillMaxWidth().horizontalScroll(scroll).border(BorderStroke(0.5.dp, c.outline.copy(alpha = 0.25f)))) {
                    Col.entries.forEach { col ->
                        val text = r.text(col)
                        val open = col == Col.PUBLIC_URL && text.isNotBlank()
                        Text(
                            text,
                            Modifier.width(widths.getValue(col)).padding(horizontal = 8.dp, vertical = 5.dp)
                                .then(if (open) Modifier.clickable(role = Role.Button) { onOpenUrl("https://$text") } else Modifier)
                                .testTag(RoutesTags.cell(col)),
                            color = if (open) c.primary else c.onSurface,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, chips: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(36.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        chips()
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.primary.copy(alpha = 0.22f) else c.surfaceVariant.copy(alpha = 0.5f))
            .border(BorderStroke(1.dp, if (selected) c.primary else c.outline.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .testTag(tag).semantics { this.selected = selected; contentDescription = label },
    ) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = if (selected) c.primary else c.onSurface,
            style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** The Table page as a tab fragment: rows from the baked slice, state in SharedPreferences, a public URL opens in the browser. */
class RoutesTableFragment : KitComposeFragment() {

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val store = remember { PrefsRoutesStateStore(ctx) }
        val initial = remember { store.load() }
        RoutesTableScreen(
            rows = remember { Declarations.routes },
            initial = initial,
            onState = store::save,
            onOpenUrl = { (activity as? C3HealthFragment.UrlClickListener)?.onUrlClicked(it) },
        )
    }
}
