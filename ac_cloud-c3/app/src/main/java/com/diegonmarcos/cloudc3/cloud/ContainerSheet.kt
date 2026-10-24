package com.diegonmarcos.cloudc3.cloud
/* #648 MOVED from aa_cloud-superapp with the c3 pages; the package, the R/BuildConfig it resolves against,
   and the collaborators (Sections -> Services; the row tap goes through the host's onOpenUrl, the same swap C3HealthFragment already made) are the only edits.
   2026-10-16 owner decision: all apps must be Compose — the sheet is a ModalBottomSheet under the host's CloudKitTheme. */

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.CoroutineScope
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
 *   Actions  | Container: start · stop · restart · update · logs
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

    /** The container a sheet is open on. A host keeps one of these in state and clears it on dismiss. */
    data class Target(val containerName: String, val label: String, val openUrl: String)

    /** The outcome line under the actions: [ok] true = confirmed, false = failed, null = in flight. */
    private data class Status(val text: String, val ok: Boolean?)

    /** [onDismiss] lets the Home down list re-measure once the sheet closes, so a box an
     *  action brought back leaves the list on the reading that proved it, not on a guess.
     *  [onOpenUrl] is the host's browser seam, the one every service row uses. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Sheet(target: Target, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
        val p = LocalKitPalette.current
        val containerName = target.containerName
        val pub = remember(containerName) { Services.publicServices().firstOrNull { it.name == containerName } }
        val priv = remember(containerName) { Services.privateServices().firstOrNull { it.name == containerName } }

        // vm and service are what every ops route is keyed by; without a vm
        // there is nothing to POST to and the Actions tab says so rather than
        // offering buttons that would 404.
        val vm = pub?.vm ?: priv?.vm ?: ""
        val service = pub?.service ?: priv?.service ?: containerName

        var tab by remember { mutableStateOf("infos") }
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = p.surface,
            contentColor = p.textPrimary,
        ) {
            Header(target.label, containerName, vm)
            // #868 the sheet's two tabs are libs:bottomnav's pill strip, not a TabLayout. A sheet has
            // no toolbar above it, so the strip adds no status-bar inset of its own.
            NavBarTopSecondRow(
                pages = listOf(NavPage("infos", "Infos", "info"), NavPage("actions", "Actions", "bolt")),
                selectedId = tab,
                onSelect = { page -> tab = page.id },
                underTopChrome = false,
            )
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (tab == "infos") Infos(containerName, vm, service, pub, priv)
                else Actions(containerName, vm, service, pub, priv, target.openUrl, onOpenUrl, onDismiss)
            }
        }
    }

    // ── Infos ────────────────────────────────────────────────────────────

    @Composable
    private fun Infos(
        name: String, vm: String, service: String,
        pub: Services.PublicService?, priv: Services.PrivateService?,
    ) {
        val p = LocalKitPalette.current
        Block("Container") {
            Kv("Name", name)
            Kv("VM", vm.ifBlank { "—" })
            Kv("Private DNS", (pub?.privateDns ?: priv?.privateDns).orEmpty().ifBlank { "—" })
            Kv("Protocol", priv?.protocol.orEmpty().ifBlank { "—" })
            Kv("Category", (pub?.category ?: priv?.category).orEmpty().ifBlank { "—" })
            priv?.dbEngine?.takeIf { it.isNotBlank() && it != "null" }?.let { Kv("DB engine", it) }
        }
        Block("App") {
            Kv("Service", service)
            Kv("Public URL", pub?.publicUrl.orEmpty().ifBlank { "not published" })
            Kv("Auth", pub?.auth.orEmpty().ifBlank { if (pub == null) "private (mesh only)" else "—" })
            Kv("Exposure", if (pub != null) "public via Caddy" else "private — reachable over WireGuard only")
        }

        // App-specific configuration. The generic fields above describe the box;
        // these describe the JOB — Caddy's routes, WireGuard's mesh, Authelia's
        // guard list. Long lists are expected here (41 public routes), so each
        // block is its own card and the pane scrolls.
        val extras = ContainerConfigs.forContainer(name)
        for (b in extras) {
            Block(b.title) {
                if (b.subtitle.isNotBlank()) Text(b.subtitle, color = p.textSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 2.dp))
                for ((k, v) in b.rows) Kv(k, v)
            }
        }

        Note(
            "Declared, not probed. Every value above comes from data/services_public.json, " +
                "data/services_private.json and data/mesh.json — the same files the dashboard tile " +
                "was built from, so this sheet cannot disagree with the icon that opened it, and it " +
                "still reads correctly with the mesh down." +
                if (extras.isEmpty()) "\n\nNo app-specific config block is written for this " +
                    "container yet; ContainerConfigs is where one goes." else "",
        )
    }

    // ── Actions ──────────────────────────────────────────────────────────

    @Composable
    private fun Actions(
        name: String, vm: String, service: String,
        pub: Services.PublicService?, priv: Services.PrivateService?, openUrl: String,
        onOpenUrl: (String) -> Unit, onDismiss: () -> Unit,
    ) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf<Status?>(null) }
        var stopping by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
        var logs by remember { mutableStateOf<String?>(null) }
        val bearer = remember { runCatching { DaguPrefs(ctx).bearerToken }.getOrDefault("") }

        /** Stop takes a running service away from whoever uses it, so it asks first. */
        fun confirmStop(what: String, go: () -> Unit) { stopping = what to go }

        Block("Container") {
            if (vm.isBlank()) {
                Note("No VM recorded for $name, so there is no /vms/{vm}/containers/ " +
                    "path to call. Add it to data/services_*.json and the buttons appear.")
            } else {
                for ((verb, act) in listOf("Start" to "start", "Stop" to FleetDown.STOP,
                                           "Restart" to "restart", "Update" to "update")) {
                    ActionRow(verb, "$act container") {
                        if (act == FleetDown.STOP) confirmStop(name) { containerAction(ctx, scope, { status = it }, vm, name, act, bearer) }
                        else containerAction(ctx, scope, { status = it }, vm, name, act, bearer)
                    }
                }
                ActionRow(stringResource(R.string.sheet_logs), stringResource(R.string.sheet_logs_detail)) {
                    status = Status(ctx.getString(R.string.sheet_logs_loading, name), null)
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { FleetDown.logs(vm, name, bearer) }
                        when (r) {
                            is OpsClient.Outcome.Failed -> status = Status("✗ ${r.kind}\n${r.message}", false)
                            is OpsClient.Outcome.Ok -> {
                                status = null
                                logs = r.body.ifBlank { ctx.getString(R.string.sheet_logs_empty) }
                            }
                        }
                    }
                }
            }
        }

        Block("App") {
            val url = pub?.publicUrl?.takeIf { it.isNotBlank() }?.let {
                if (it.startsWith("http")) it else "https://$it"
            } ?: openUrl
            if (url.isNotBlank()) {
                ActionRow("Open", url.removePrefix("https://")) {
                    // Same target the C3 tile itself dispatches when the sheet is
                    // bypassed, so it must open the same way: through the activity,
                    // in the app's browser. Two paths to one service URL that
                    // disagreed about where it opens is the bug this removes.
                    onOpenUrl(url)
                    onDismiss()
                }
            }
            (pub?.privateDns ?: priv?.privateDns)?.takeIf { it.isNotBlank() }?.let { dns ->
                ActionRow("Copy private DNS", dns) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(name, dns))
                    status = Status("Copied $dns", null)
                }
            }
            if (vm.isNotBlank()) {
                for ((verb, act) in listOf("Start service" to "start", "Stop service" to FleetDown.STOP)) {
                    ActionRow(verb, "$act $service") {
                        val go = { dispatch(scope, { status = it }) { OpsClient.service(vm, service, act, bearer) } }
                        if (act == FleetDown.STOP) confirmStop(service, go) else go()
                    }
                }
            }
        }

        status?.let { s ->
            val colour = Color(StatusLight.colour(ctx, StatusLight.of(s.ok)))
            SelectionContainer {
                Text(s.text, color = colour, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("c3:sheet:status"))
            }
        }
        if (bearer.isBlank() && vm.isNotBlank()) {
            Note(
                "The four container actions and the two service actions need an Authelia bearer — " +
                    "they sit behind forward_auth. This device has none stored. Import one via " +
                    "Configs → Profile → Config import, or paste it in the Dagu login; both write the " +
                    "same token. Open and Copy work without it.",
            )
        }

        stopping?.let { (what, go) ->
            AlertDialog(
                onDismissRequest = { stopping = null },
                title = { Text(stringResource(R.string.sheet_stop_confirm_title, what)) },
                text = { Text(stringResource(R.string.sheet_stop_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = { stopping = null; go() }, modifier = Modifier.testTag("c3:sheet:stop-yes")) {
                        Text(stringResource(R.string.sheet_stop_confirm_yes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { stopping = null }) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }
        logs?.let { body ->
            AlertDialog(
                onDismissRequest = { logs = null },
                title = { Text(stringResource(R.string.sheet_logs_title, name, vm)) },
                text = {
                    SelectionContainer {
                        Text(body, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                    }
                },
                confirmButton = { TextButton(onClick = { logs = null }) { Text(stringResource(android.R.string.ok)) } },
            )
        }
    }

    /**
     * A container action's REAL outcome. [FleetDown.act] makes the call and then reads the
     * container's state back from the API, so the line printed is what the box IS after the
     * tap — never the 2xx alone — and it is green only when that read-back shows what the
     * action promised.
     */
    private fun containerAction(
        ctx: Context, scope: CoroutineScope, report: (Status) -> Unit,
        vm: String, name: String, act: String, bearer: String,
    ) {
        report(Status(ctx.getString(R.string.sheet_working, act, name), null))
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                FleetDown.act(act, { OpsClient.container(vm, name, act, bearer) },
                              { FleetDown.state(vm, name, bearer) })
            }
            val call = when (val c = r.call) {
                is OpsClient.Outcome.Ok -> c.message.lineSequence().first()
                is OpsClient.Outcome.Failed -> "✗ ${c.kind}\n${c.message}"
            }
            val verdict = ctx.getString(
                if (r.confirmed) R.string.sheet_readback_confirmed else R.string.sheet_readback_unconfirmed,
                stateText(ctx, r.after))
            report(Status("$call\n$verdict", r.confirmed))
        }
    }

    /** One [FleetDown.State] as a caption — shared by the Home down list and the read-back. */
    fun stateText(ctx: Context, s: FleetDown.State): String = when (s) {
        is FleetDown.State.Docker -> s.word
        FleetDown.State.Missing -> ctx.getString(R.string.down_state_missing)
        is FleetDown.State.Unread -> ctx.getString(R.string.down_state_unread, s.why)
    }

    private fun dispatch(scope: CoroutineScope, report: (Status) -> Unit, call: () -> OpsClient.Outcome) {
        report(Status("… working", null))
        scope.launch {
            val r = withContext(Dispatchers.IO) { call() }
            report(when (r) {
                is OpsClient.Outcome.Ok -> Status(r.message, true)
                is OpsClient.Outcome.Failed -> Status("✗ ${r.kind}\n${r.message}", false)
            })
        }
    }

    // ── small composables ────────────────────────────────────────────────

    @Composable
    private fun Header(label: String, name: String, vm: String) {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, top = 4.dp, end = 18.dp, bottom = 8.dp)) {
            Text(label, color = p.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(if (vm.isBlank()) name else "$name · $vm", color = p.textSecondary, fontSize = 12.sp)
        }
    }

    @Composable
    private fun Block(title: String, content: @Composable ColumnScope.() -> Unit) {
        val p = LocalKitPalette.current
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 14.dp)
                .background(p.surfaceSelected, shape).border(1.dp, p.hairline, shape)
                .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 12.dp),
        ) {
            Text(title, color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }

    @Composable
    private fun Kv(k: String, v: String) {
        val p = LocalKitPalette.current
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(k, color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            SelectionContainer(Modifier.weight(1.4f)) {
                Text(v, color = p.textPrimary, fontSize = 13.sp, textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun ActionRow(label: String, detail: String, onTap: () -> Unit) {
        val p = LocalKitPalette.current
        Row(
            Modifier.fillMaxWidth().testTag("c3:sheet:action:$label").clickable { onTap() }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, color = p.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(detail, color = p.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, modifier = Modifier.weight(1.2f))
        }
    }

    @Composable
    private fun Note(text: String) {
        val p = LocalKitPalette.current
        Text(text, color = p.textSecondary, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 16.dp))
    }
}
