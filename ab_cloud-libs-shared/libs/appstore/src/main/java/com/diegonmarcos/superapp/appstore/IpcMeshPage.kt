package com.diegonmarcos.superapp.appstore

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * #ipc THE IPC page — "IPC Mesh": what sits behind the fleet's one mesh door (the SuperApp
 * gateway) on this phone. ONE composable, drawn by Cloud Store > Access > IPC (AccessPage) and
 * SuperApp > Configs > Network > IPC (its IpcMeshFragment host, [IpcMeshFragment] here), never
 * copied. Laid out like Apps Mesh (StoreMesh.render): a caption, the Store's own action
 * buttons and filter chips (the styles appstore-controls.json declares for StoreBar, drawn in
 * Compose), a summary line, then one card per fleet member in the
 * fleet's group order — its status, and, unfolded, every IPC endpoint it exposes with the
 * permission guarding it, its callers and whether it resolves here. Data: [IpcMesh].
 *
 * [expandAll] unfolds every member (the page test); the device starts folded.
 */
@Composable
fun IpcMeshPage(expandAll: Boolean = false, registry: IpcMesh.Registry? = null) {
    val ctx = LocalContext.current
    val reg = remember { registry ?: IpcMesh.baked() }
    val order = remember { IpcMesh.order() }
    val looks = remember { StoreControls.load(ctx) }
    var live by remember { mutableStateOf<IpcMesh.Live?>(null) }
    var doors by remember { mutableStateOf<Map<String, IpcMesh.Door>>(emptyMap()) }
    var pinging by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(IpcMesh.FILTERS.first()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        val app = ctx.applicationContext
        thread(name = "ipc-mesh-probe") { val l = IpcMesh.probe(app, reg, doors); live = l }
    }
    fun pingAll() {
        if (pinging) return
        pinging = true
        val app = ctx.applicationContext
        val installed = live?.installed?.keys ?: emptySet()
        thread(name = "ipc-mesh-ping") {
            val pool = Executors.newFixedThreadPool(6)
            val got = try {
                reg.members.filter { it.door && it.id in installed }
                    .map { m -> pool.submit(Callable { m.id to IpcMesh.ping(app, m) }) }
                    .mapNotNull { runCatching { it.get() }.getOrNull() }.toMap()
            } finally { pool.shutdownNow() }
            doors = got
            live = live?.let { IpcMesh.Live(it.installed, it.resolves, got) }
            pinging = false
        }
    }

    val s = StoreDensity.dpValue(StoreDensity.S4).dp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(StoreDensity.dpValue(StoreDensity.S12).dp),
        verticalArrangement = Arrangement.spacedBy(s)) {
        Text("IPC Mesh: how fleet apps on this phone talk to each other — Android IPC only, every endpoint behind the " +
            "fleet's signature permission or a declared intent. Off the phone, the SuperApp gateway is the one door; " +
            "these are the apps behind it.", color = DIM, fontSize = StoreDensity.T_CAPTION.sp)
        // the Store's own controls, as on Apps Mesh: actions, then the filter chips
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s)) {
            StoreAction(looks.action, "Re-probe", Modifier.weight(1f)) { tick++ }
            StoreAction(looks.action, "Ping doors", Modifier.weight(1f)) { pingAll() }
            StoreAction(looks.action, "Copy", Modifier.weight(1f)) { copy(ctx, IpcMesh.report(reg, live)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s)) {
            for (f in IpcMesh.FILTERS) {
                val n = reg.members.count { IpcMesh.matches(f, it, live) }
                StoreAction(looks.filter, "${label(f)} ($n)", Modifier.weight(1f).testTag(IpcMesh.TAG_FILTER + f), active = f == filter) { filter = f }
            }
        }
        Text((if (pinging) "⟳ asking each door… · " else "") + IpcMesh.summary(reg, live), color = DIM,
            fontSize = StoreDensity.T_META.sp, modifier = Modifier.testTag(IpcMesh.TAG_SUMMARY))

        for (row in IpcMesh.rows(reg, live, filter, expanded, expandAll, order)) {
            val m = row.member
            val e = row.endpoint
            if (e == null) MemberHead(m, live, open = expandAll || m.id in expanded, tag = row.tag) {
                expanded = if (m.id in expanded) expanded - m.id else expanded + m.id
            } else {
                val res = live?.resolves?.get(IpcMesh.key(m, e))
                Text(IpcMesh.line(e, res), Modifier.fillMaxWidth().background(CARD)
                    .padding(start = StoreDensity.dpValue(StoreDensity.S12).dp, end = s, bottom = s).testTag(row.tag),
                    color = when (res) { true -> Color.White; false -> RED; null -> DIM },
                    fontFamily = FontFamily.Monospace, fontSize = StoreDensity.T_CAPTION.sp)
            }
        }
    }
}

@Composable
private fun MemberHead(m: IpcMesh.Member, live: IpcMesh.Live?, open: Boolean, tag: String, onToggle: () -> Unit) {
    val version = live?.installed?.get(m.id)
    val door = live?.doors?.get(m.id)
    val (dot, status) = when {
        live == null -> DIM to "probing…"
        version == null -> DIM to "not installed"
        door == null -> BLUE to "${version.ifEmpty { "?" }} · door ${if (m.door) "content://${m.pkg}.fleet, not asked (Ping asks)" else "none (no libs:devtools)"}"
        door.ok -> GREEN to "${version.ifEmpty { "?" }} · door answers in ${door.ms} ms (${door.note})"
        else -> RED to "${version.ifEmpty { "?" }} · door does not answer: ${door.note}"
    }
    val p = StoreDensity.dpValue(StoreDensity.S12).dp
    Column(Modifier.fillMaxWidth().background(CARD).clickable(onClick = onToggle)
        .padding(start = p, end = p, top = StoreDensity.dpValue(StoreDensity.S6).dp, bottom = StoreDensity.dpValue(StoreDensity.S2).dp)
        .testTag(tag)) {
        Text((if (open) "▾ " else "▸ ") + m.label + (if (m.kind == "lib") "  · lib" else "") + "  ·  ${m.endpoints.size} endpoints",
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_TITLE.sp)
        Text("● $status", color = dot, fontSize = StoreDensity.T_CAPTION.sp)
    }
}

/** A button or chip in the style appstore-controls.json declares (the same tokens StoreBar draws). */
@Composable
private fun StoreAction(style: StoreControls.Style, text: String, modifier: Modifier, active: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape((style.radiusDp * StoreDensity.SCALE).dp)
    val fill = (if (active) style.fillActive else style.fill)?.let { Color(it) } ?: Color.Transparent
    val stroke = (if (active) style.strokeActive ?: style.stroke else style.stroke)?.let { Color(it) }
    var m = modifier.clip(shape).background(fill)
    if (stroke != null) m = m.border(StoreDensity.dpValue(StoreDensity.S1).dp, stroke, shape)
    Text(text, m.clickable(onClick = onClick).padding(StoreDensity.dpValue(StoreDensity.S6).dp),
        color = Color(if (active) style.textActive else style.text), textAlign = TextAlign.Center, maxLines = 1,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal, fontSize = StoreDensity.T_META.sp)
}

private fun label(f: String) = when (f) { "all" -> "All"; "installed" -> "Installed"; "answers" -> "Answers"; else -> "Not installed" }

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("IPC Mesh", text))
    Toast.makeText(ctx, "IPC Mesh copied", Toast.LENGTH_SHORT).show()
}

/** The host for a fragment-paged app (SuperApp > Configs > Network > IPC): only [IpcMeshPage]. */
class IpcMeshFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply { setContent { IpcMeshPage() } }
}

private val GREEN = Color(0xFF48BB78)
private val BLUE = Color(0xFF63B3ED)
private val RED = Color(0xFFF56565)
private val DIM = Color(0x99FFFFFF)
private val CARD = Color(0xFF1C1C24)
