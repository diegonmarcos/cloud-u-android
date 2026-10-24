package com.diegonmarcos.superapp.cloud

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.launcher.TileGridFragment
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs
import com.diegonmarcos.superapp.ui.KitSheet
import com.diegonmarcos.superapp.ui.StatusLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a container icon opens on the C3 page.
 *
 * Every tile on that dashboard IS a docker/podman container, and tapping one
 * used to do exactly one thing — open its URL in the browser. That threw away
 * everything the estate already declares about the box and left no way to act
 * on it from the phone at all.
 *
 * The sheet has two tabs, each split the same way:
 *
 *   Infos    | Container: where it runs, how it is addressed
 *            | App:       what it serves and who may reach it
 *   Actions  | Container: start · stop · restart · update
 *            | App:       open · copy · service start · service stop
 *
 * THE SPLIT IS NOT COSMETIC. c3-infra-api addresses a container and its
 * service through different routes (/containers/{name} vs /services/{service}),
 * because restarting the box and restarting what runs inside it are different
 * operations with different blast radius. The UI shows them apart for the same
 * reason.
 *
 * Everything under Infos is DECLARATIVE — read from the same
 * data/services_{public,private}.json the dashboard itself is built from, so
 * the sheet cannot disagree with the tile that opened it, and it renders
 * instantly with no network call. Only Actions talk to the network.
 */
object ContainerSheet {

    private val INK = Color(0xFFECE6F5)
    private val DIM = Color(0xFF9B93AB)
    private val FAINT = Color(0xFF6F6880)
    private val CARD = Color(0xFF1B1428)
    private val CARD_EDGE = Color(0xFF322A44)
    private val ACCENT = Color(0xFFB794F6)

    fun show(activity: FragmentActivity, containerName: String, label: String, openUrl: String) {
        KitSheet.show(activity) { close -> Sheet(containerName, label, openUrl, close) }
    }

    /** The sheet's content, split from [show] so it can be drawn without a window. */
    @Composable
    internal fun Sheet(containerName: String, label: String, openUrl: String, close: () -> Unit) {
        val pub = remember { Sections.publicServices().firstOrNull { it.name == containerName } }
        val priv = remember { Sections.privateServices().firstOrNull { it.name == containerName } }

        // vm and service are what every ops route is keyed by; without a vm
        // there is nothing to POST to and the Actions tab says so rather than
        // offering buttons that would 404.
        val vm = pub?.vm ?: priv?.vm ?: ""
        val service = pub?.service ?: priv?.service ?: containerName
        var tab by remember { mutableStateOf("infos") }

        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 8.dp)) {
                Text(label, color = INK, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (vm.isBlank()) containerName else "$containerName · $vm", color = DIM, fontSize = 12.sp)
            }
            // The shared pill strip (libs:bottomnav); in a sheet, so no live top inset.
            PageTabs(pages = listOf(NavPage("infos", "Infos", "info"), NavPage("actions", "Actions", "bolt")), selectedId = tab,
                onSelect = { tab = it.id }, underTopChrome = false)
            Column(Modifier.fillMaxWidth().height(420.dp).verticalScroll(rememberScrollState()).testTag("container:pane:$tab")) {
                if (tab == "infos") Infos(containerName, vm, service, pub, priv)
                else Actions(containerName, vm, service, pub, priv, openUrl, close)
            }
        }
    }

    // ── Infos ────────────────────────────────────────────────────────────

    @Composable
    private fun Infos(name: String, vm: String, service: String, pub: Sections.PublicService?, priv: Sections.PrivateService?) {
        Card {
            BlockTitle("Container")
            Kv("Name", name)
            Kv("VM", vm.ifBlank { "—" })
            Kv("Private DNS", (pub?.privateDns ?: priv?.privateDns).orEmpty().ifBlank { "—" })
            Kv("Protocol", priv?.protocol.orEmpty().ifBlank { "—" })
            Kv("Category", (pub?.category ?: priv?.category).orEmpty().ifBlank { "—" })
            priv?.dbEngine?.takeIf { it.isNotBlank() && it != "null" }?.let { Kv("DB engine", it) }
        }
        Card {
            BlockTitle("App")
            Kv("Service", service)
            Kv("Public URL", pub?.publicUrl.orEmpty().ifBlank { "not published" })
            Kv("Auth", pub?.auth.orEmpty().ifBlank { if (pub == null) "private (mesh only)" else "—" })
            Kv("Exposure", if (pub != null) "public via Caddy" else "private — reachable over WireGuard only")
        }

        // App-specific configuration. The generic fields above describe the box;
        // these describe the JOB — Caddy's routes, WireGuard's mesh, Authelia's
        // guard list. Long lists are expected here (41 public routes), so each
        // block is its own card and the pane scrolls.
        val extras = remember(name) { ContainerConfigs.forContainer(name) }
        for (b in extras) Card {
            BlockTitle(b.title)
            if (b.subtitle.isNotBlank()) Text(b.subtitle, color = FAINT, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
            for ((k, v) in b.rows) Kv(k, v)
        }

        Note("Declared, not probed. Every value above comes from data/services_public.json, " +
            "data/services_private.json and data/mesh.json — the same files the dashboard tile " +
            "was built from, so this sheet cannot disagree with the icon that opened it, and it " +
            "still reads correctly with the mesh down." +
            if (extras.isEmpty()) "\n\nNo app-specific config block is written for this " +
                "container yet; ContainerConfigs is where one goes." else "")
    }

    // ── Actions ──────────────────────────────────────────────────────────

    @Composable
    private fun Actions(
        name: String, vm: String, service: String,
        pub: Sections.PublicService?, priv: Sections.PrivateService?, openUrl: String, close: () -> Unit,
    ) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val bearer = remember { runCatching { DaguPrefs(ctx).bearerToken }.getOrDefault("") }
        // The outcome line: its text and whether the action WORKED (true / false / null = still in flight).
        var status by remember { mutableStateOf<Pair<String, Boolean?>?>(null) }
        fun dispatch(call: () -> OpsClient.Outcome) {
            status = "… working" to null
            scope.launch {
                val r = withContext(Dispatchers.IO) { call() }
                status = when (r) {
                    is OpsClient.Outcome.Ok -> r.message to true
                    is OpsClient.Outcome.Failed -> "✗ ${r.kind}\n${r.message}" to false
                }
            }
        }

        Card {
            BlockTitle("Container")
            if (vm.isBlank()) {
                Note("No VM recorded for $name, so there is no /vms/{vm}/containers/ " +
                    "path to call. Add it to data/services_*.json and the buttons appear.", inCard = true)
            } else {
                for ((verb, act) in listOf("Start" to "start", "Stop" to "stop", "Restart" to "restart", "Update" to "update")) {
                    ActionRow(verb, "$act container") { dispatch { OpsClient.container(vm, name, act, bearer) } }
                }
            }
        }

        Card {
            BlockTitle("App")
            val url = pub?.publicUrl?.takeIf { it.isNotBlank() }?.let {
                if (it.startsWith("http")) it else "https://$it"
            } ?: openUrl
            if (url.isNotBlank()) {
                ActionRow("Open", url.removePrefix("https://")) {
                    // Same target the C3 tile itself dispatches when the sheet is
                    // bypassed, so it must open the same way: through the activity,
                    // in the app's browser. Two paths to one service URL that
                    // disagreed about where it opens is the bug this removes.
                    (ctx as? TileGridFragment.TileClickListener)?.onTileClicked(url)
                    close()
                }
            }
            (pub?.privateDns ?: priv?.privateDns)?.takeIf { it.isNotBlank() }?.let { dns ->
                ActionRow("Copy private DNS", dns) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(name, dns))
                    status = "Copied $dns" to null
                }
            }
            if (vm.isNotBlank()) {
                for ((verb, act) in listOf("Start service" to "start", "Stop service" to "stop")) {
                    ActionRow(verb, "$act $service") { dispatch { OpsClient.service(vm, service, act, bearer) } }
                }
            }
        }

        status?.let { (text, ok) ->
            SelectionContainer {
                // Coloured by whether the action WORKED, through StatusLight - never a private palette.
                Text(text, fontSize = 11.sp, color = Color(StatusLight.colour(ctx, StatusLight.of(ok))),
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp).testTag("container:status"))
            }
        }
        if (bearer.isBlank() && vm.isNotBlank()) {
            Note("The four container actions and the two service actions need an Authelia bearer — " +
                "they sit behind forward_auth. This device has none stored. Import one via " +
                "Configs → Profile → Config import, or paste it in the Dagu login; both write the " +
                "same token. Open and Copy work without it.")
        }
    }

    // ── small composables ────────────────────────────────────────────────

    /**
     * The outcome line under the actions, coloured by whether the action WORKED.
     *
     * [ok] is the same three-state reading [StatusLight] was built for -- true is a
     * confirmed success, false a confirmed failure, and null "the call is still in
     * flight, nobody can say yet" -- so it resolves through [StatusLight] rather than
     * through a private palette. This method used to carry three colour literals of its
     * own, and they had DRIFTED: its green, its red and its grey were each a different
     * value from the ones every other status in this app uses, so the same fact about
     * the same fleet was painted one colour on this sheet and another everywhere else.
     * A status light is a promise the owner learns to trust at a glance, and two greens
     * mean one of them is lying about which green means healthy.
     *
     * Restating the palette here also put it out of reach of the tester that recomputes
     * the contrast ratios against the themes this app ships, including the pure-black
     * launcher palette: a colour compiled into Kotlin is one no theme can override and
     * nothing was checking.
     */
    @Composable
    private fun Card(content: @Composable () -> Unit) {
        val shape = RoundedCornerShape(14.dp)
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 14.dp).background(CARD, shape)
            .border(1.dp, CARD_EDGE, shape).padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 12.dp)) { content() }
    }

    @Composable
    private fun BlockTitle(text: String) =
        Text(text, color = ACCENT, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))

    @Composable
    private fun Kv(k: String, v: String) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(k, color = DIM, fontSize = 13.sp, modifier = Modifier.weight(1f))
            SelectionContainer(Modifier.weight(1.4f)) {
                Text(v, color = INK, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun ActionRow(label: String, detail: String, onTap: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 10.dp).testTag("container:action:$label"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = INK, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(detail, color = FAINT, fontSize = 11.sp, textAlign = TextAlign.End, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.2f))
        }
    }

    @Composable
    private fun Note(text: String, inCard: Boolean = false) {
        Text(text, color = FAINT, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = if (inCard) Modifier else Modifier.padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 16.dp))
    }
}
