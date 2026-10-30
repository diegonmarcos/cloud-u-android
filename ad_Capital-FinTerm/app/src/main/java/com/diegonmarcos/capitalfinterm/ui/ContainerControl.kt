package com.diegonmarcos.capitalfinterm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.CState
import com.diegonmarcos.capitalfinterm.Control
import com.diegonmarcos.capitalfinterm.Decl
import com.diegonmarcos.capitalfinterm.Mesh
import com.diegonmarcos.capitalfinterm.R
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The container control above the primary source (data/control.json): Start / Stop / Restart of the Cloud-Fincept container through
 * c3-infra-api over the PRIVATE MESH ONLY, with its live state (running, stopped, unhealthy). Off the mesh every action is disabled and
 * the card says so; Stop and Restart ask first. The address is the baked fleet endpoint, no bearer is sent (WireGuard is the auth).
 */
@Composable
fun ContainerControl(sourceId: String) {
    val ctx = LocalContext.current
    val decl = remember { AssetData.control(ctx) }
    if (decl == null || decl.source != sourceId) return
    val p = LocalKitPalette.current
    val scope = rememberCoroutineScope()
    val base = Decl.fleetMesh[decl.fleetEndpoint].orEmpty()
    var check by remember { mutableIntStateOf(0) }
    var onMesh by remember { mutableStateOf<Boolean?>(null) }
    var state by remember { mutableStateOf(CState.UNKNOWN) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf("") }

    LaunchedEffect(check) {
        onMesh = null
        val (m, s) = withContext(Dispatchers.IO) {
            val m = Mesh.reachable(Net.probe, base, decl.healthPath)
            m to (if (m) Control.readState(decl, base, Net.control) else CState.UNKNOWN)
        }
        onMesh = m
        state = s
    }

    val stateWord = when {
        onMesh == null -> stringResource(R.string.ctl_status_checking)
        onMesh == false -> stringResource(R.string.ctl_status_off_mesh)
        state == CState.RUNNING -> stringResource(R.string.ctl_status_running)
        state == CState.STOPPED -> stringResource(R.string.ctl_status_stopped)
        state == CState.UNHEALTHY -> stringResource(R.string.ctl_status_unhealthy)
        else -> stringResource(R.string.ctl_status_unknown)
    }
    val stateKind = when {
        onMesh == null -> KitState.BUSY
        onMesh == false -> KitState.IDLE
        state == CState.RUNNING -> KitState.OK
        state == CState.STOPPED -> KitState.BAD
        state == CState.UNHEALTHY -> KitState.WARN
        else -> KitState.IDLE
    }
    fun run(actionId: String) {
        scope.launch {
            busy = true
            val (ok, st) = withContext(Dispatchers.IO) { Control.run(decl, base, Net.control, actionId) }
            state = st
            result = ctx.getString(if (ok) R.string.ctl_done else R.string.ctl_failed, actionId, st.name.lowercase())
            busy = false
        }
    }

    KitCard(Modifier.testTag(Tags.CONTROL)) {
        KitSettingsRow(title = decl.label, subtitle = decl.vm + " / " + decl.name, trailing = { KitStatePill(stateWord, stateKind) })
        if (onMesh == false) Text(stringResource(R.string.ctl_mesh_only), color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(Tags.MESH_ONLY))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            decl.actions.forEach { a ->
                KitOutlinedButton(
                    onClick = { if (Control.needsConfirm(decl, a.id)) pending = a.id else run(a.id) },
                    enabled = !busy && Control.enabled(decl, a.id, state, onMesh == true),
                    modifier = Modifier.testTag(Tags.action(a.id)),
                ) { Text(a.label) }
            }
            KitTextButton(onClick = { check += 1 }, enabled = !busy) { Text(stringResource(R.string.ctl_refresh)) }
        }
        if (busy) Text(stringResource(R.string.ctl_busy), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        else if (result.isNotEmpty()) Text(result, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
    }

    val ask = Control.action(decl, pending)
    if (ask != null) {
        KitConfirmDialog(
            title = stringResource(R.string.ctl_confirm_title, ask.label, decl.name),
            text = stringResource(R.string.ctl_confirm_text, ask.label, decl.name, decl.vm),
            confirmLabel = stringResource(R.string.ctl_confirm_yes, ask.label),
            dismissLabel = stringResource(R.string.ctl_cancel),
            onConfirm = { val id = pending; pending = ""; run(id) },
            onDismiss = { pending = "" },
        )
    }
}
