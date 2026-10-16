package com.diegonmarcos.cloudc3.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.delay
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
import com.diegonmarcos.cloudc3.cloud.C3StackFragment
import com.diegonmarcos.cloudc3.cloud.ContainerSheet
import com.diegonmarcos.cloudc3.pages.RoutesTableModel.Column as Col
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette

/** Test tags of the Table page, for the Compose suite. */
object RoutesTags {
    const val SCREEN = "routes_screen"
    const val COUNT = "routes_count"
    const val CATEGORY_CHIP = "routes_category_chip"
    const val MENU_COPY = "routes_menu_copy"
    const val MENU_OPEN = "routes_menu_open"
    const val MENU_COPY_OPEN = "routes_menu_copy_open"
    const val SEARCH = "routes_search"
    const val SEARCH_CLEAR = "routes_search_clear"
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoutesTableScreen(
    rows: List<RoutesTableModel.Row>,
    initial: RoutesTableModel.State = RoutesTableModel.State(),
    onState: (RoutesTableModel.State) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onOpen: (RoutesTableModel.OpenTarget) -> Unit = {},
) {
    var state by remember { mutableStateOf(initial) }
    fun update(s: RoutesTableModel.State) { state = s; onState(s) }
    val shown = remember(rows, state) { RoutesTableModel.view(rows, state) }
    val scroll = rememberScrollState()
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
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
            if (state.category.isNotEmpty()) ChipRow("Cat") {
                Chip("${state.category}  \u00D7", true, RoutesTags.CATEGORY_CHIP) { update(state.copy(category = "")) }
            }
            SearchBox(state.query) { update(state.copy(query = it)) }
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
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            Text(
                                text,
                                Modifier.width(widths.getValue(col)).padding(horizontal = 8.dp, vertical = 5.dp)
                                    .combinedClickable(
                                        role = Role.Button,
                                        onClick = { if (open) onOpenUrl("https://$text") },
                                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                                    )
                                    .testTag(RoutesTags.cell(col)),
                                color = if (open) c.primary else c.onSurface,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val target = RoutesTableModel.openTarget(col, text)
                            fun doOpen() {
                                if (target is RoutesTableModel.OpenTarget.Category) update(state.copy(category = target.name))
                                else if (target != null) onOpen(target)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Copy") }, enabled = text.isNotBlank(),
                                    modifier = Modifier.testTag(RoutesTags.MENU_COPY),
                                    onClick = { menu = false; copyToClipboard(ctx, text) })
                                DropdownMenuItem(text = { Text("Open") }, enabled = target != null,
                                    modifier = Modifier.testTag(RoutesTags.MENU_OPEN),
                                    onClick = { menu = false; doOpen() })
                                DropdownMenuItem(text = { Text("Copy & Open") }, enabled = target != null && text.isNotBlank(),
                                    modifier = Modifier.testTag(RoutesTags.MENU_COPY_OPEN),
                                    onClick = { menu = false; copyToClipboard(ctx, text); doOpen() })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Copy [text] to the system clipboard and say so. */
private fun copyToClipboard(ctx: Context, text: String) {
    runCatching {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("c3", text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }
}

/** Free-text search under the filters: live (debounced), with a clear button. [onQuery] gets the settled text. */
@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(query) }
    val latest by rememberUpdatedState(onQuery)
    LaunchedEffect(text) {
        if (text != query) { delay(SEARCH_DEBOUNCE_MS); latest(text) }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, c.outline.copy(alpha = 0.4f)), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Search all columns", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = c.onSurface),
                cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth().testTag(RoutesTags.SEARCH),
            )
        }
        if (text.isNotEmpty()) {
            Text(
                "\u00D7",
                Modifier.clickable(role = Role.Button) { text = ""; latest("") }.padding(horizontal = 8.dp)
                    .testTag(RoutesTags.SEARCH_CLEAR).semantics { contentDescription = "Clear search" },
                color = c.onSurface, style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private const val SEARCH_DEBOUNCE_MS = 200L

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
        val ctx = LocalContext.current
        var container by remember { mutableStateOf<String?>(null) }
        val store = remember { PrefsRoutesStateStore(ctx) }
        val initial = remember { store.load() }
        RoutesTableScreen(
            rows = remember { Declarations.routes },
            initial = initial,
            onState = store::save,
            onOpenUrl = { (activity as? C3HealthFragment.UrlClickListener)?.onUrlClicked(it) },
            onOpen = { t ->
                when (t) {
                    is RoutesTableModel.OpenTarget.Url -> (activity as? C3HealthFragment.UrlClickListener)?.onUrlClicked(t.url)
                    is RoutesTableModel.OpenTarget.Container -> container = t.name
                    // The VM's place is the Infra page (Stack > VMs); C3 has no page per VM.
                    is RoutesTableModel.OpenTarget.Vm -> (activity as? C3StackFragment.TargetListener)?.onTargetClicked("page:c3/stack")
                    is RoutesTableModel.OpenTarget.Category -> Unit // handled inside the screen: it sets the category filter
                }
            },
        )
        container?.let { name ->
            ContainerSheet.Sheet(
                target = ContainerSheet.Target(name, name, ""),
                onOpenUrl = { url -> (activity as? C3HealthFragment.UrlClickListener)?.onUrlClicked(url) },
                onDismiss = { container = null },
            )
        }
    }
}
