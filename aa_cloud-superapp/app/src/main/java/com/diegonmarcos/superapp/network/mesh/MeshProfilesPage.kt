package com.diegonmarcos.superapp.network.mesh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.network.WireGuardProfiles
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * #877 Profiles: the declared profiles (ui.wireguard_profiles + the public-VPN templates) as rows, the
 * stored set they map onto, and the provider choice. Nothing here writes a private key anywhere: the text
 * a row copies or draws as a QR is the declared TEMPLATE, whose PrivateKey line is empty.
 */
@Composable
fun MeshProfilesPage(store: MeshStore, modifier: Modifier = Modifier) {
    val page = store.decl.page("profiles")
    Column(
        modifier.fillMaxSize().testTag(MeshTags.page("profiles")).verticalScroll(rememberScrollState())
            .padding(MeshDensity.dp(MeshDensity.S8)),
    ) {
        page?.control("provider")?.let { c ->
            ChoiceLine(
                declLabel("ctl", c.id, c.label),
                c.choices.map { it to declLabel("opt", it, it) },
                store.provider.ifBlank { c.default },
                { store.run(c.engine, it) },
                Modifier.testTag(MeshTags.control(c.id)),
            )
            Reason(stringResource(R.string.mesh_provider_text))
        }
        MHeader(stringResource(R.string.mesh_profiles_mesh))
        if (WireGuardProfiles.mesh.isEmpty()) MText(stringResource(R.string.mesh_profiles_none), size = MeshDensity.T_META, muted = true)
        WireGuardProfiles.mesh.forEach { ProfileRow(store, it) }
        MHeader(stringResource(R.string.mesh_profiles_external))
        WireGuardProfiles.external.forEach { ProfileRow(store, it) }
        MHeader(stringResource(R.string.mesh_profiles_io))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6))) {
            for (id in listOf("conf_import", "conf_export", "profile_export_folder")) {
                page?.control(id)?.let { c ->
                    MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine) }, Modifier.testTag(MeshTags.control(c.id)))
                }
            }
        }
        Reason(stringResource(R.string.mesh_export_text))
    }
    if (store.confirmCloud) {
        KitConfirmDialog(
            stringResource(R.string.mesh_cloud_title),
            stringResource(R.string.mesh_cloud_text),
            stringResource(R.string.mesh_cloud_use),
            stringResource(R.string.mesh_cloud_keep),
            onConfirm = { store.confirmCloudPreset() },
            onDismiss = { store.confirmCloud = false },
        )
    }
}

@Composable
private fun ProfileRow(store: MeshStore, profile: WireGuardProfiles.Profile) {
    val page = store.decl.page("profiles")
    val key = store.storedKey(store.profiles, profile.id)
    val active = store.snapshot?.cfg?.activeProfile?.substringAfterLast('/') == profile.fileName.removeSuffix(".conf")
    val state = stringResource(if (active) R.string.mesh_p_active else if (key != null) R.string.mesh_p_stored else R.string.mesh_p_template)
    val usable = store.hasKey
    Column(Modifier.fillMaxWidth().testTag("mesh:profile:${profile.id}").padding(vertical = MeshDensity.dp(MeshDensity.S4))) {
        Row(horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6)), verticalAlignment = Alignment.CenterVertically) {
            MText(if (active) "●" else "○", size = MeshDensity.T_BODY)
            MText(profile.label.ifBlank { profile.id }, Modifier.weight(1f), size = MeshDensity.T_BODY, bold = active, maxLines = 1)
            MText(state, size = MeshDensity.T_CAPTION, muted = !active, bold = active)
        }
        MText(profile.fileName + " · " + profile.address, size = MeshDensity.T_CAPTION, mono = true, muted = true, maxLines = 1)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = MeshDensity.dp(MeshDensity.S2)),
            horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S4)),
        ) {
            page?.control("profile_select")?.let { c ->
                MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine, profile.id) }, Modifier.testTag(MeshTags.control(c.id) + ":" + profile.id), enabled = usable && !active, emphasised = !active)
            }
            page?.control("profile_import_clipboard")?.let { c ->
                MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine, profile.id) }, Modifier.testTag(MeshTags.control(c.id) + ":" + profile.id))
            }
            page?.control("profile_import_vault")?.let { c ->
                MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine, profile.id) }, Modifier.testTag(MeshTags.control(c.id) + ":" + profile.id))
            }
            page?.control("profile_diff")?.let { c ->
                MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine, profile.id) }, Modifier.testTag(MeshTags.control(c.id) + ":" + profile.id), emphasised = store.diffFor == profile.id)
            }
            page?.control("profile_export_text")?.let { c ->
                MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine, profile.id) }, Modifier.testTag(MeshTags.control(c.id) + ":" + profile.id))
            }
            page?.control("profile_export_qr")?.let { c ->
                MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine, profile.id) }, Modifier.testTag(MeshTags.control(c.id) + ":" + profile.id), emphasised = store.qrFor == profile.id)
            }
        }
        if (!usable) Reason(stringResource(R.string.mesh_need_key))
        if (store.diffFor == profile.id) DiffPanel(store, profile.id, key?.let { store.profiles[it] })
        if (store.qrFor == profile.id) store.profileText(profile.id)?.let { MeshQr(qrPayload(it)) }
    }
}

@Composable
private fun DiffPanel(store: MeshStore, id: String, stored: String?) {
    val declared = store.profileText(id).orEmpty()
    val lines = remember(declared, stored) { ProfileDiff.diff(declared, stored) }
    Column(Modifier.fillMaxWidth().testTag("mesh:diff:$id").padding(top = MeshDensity.dp(MeshDensity.S4))) {
        if (ProfileDiff.clean(lines)) MText(stringResource(R.string.mesh_diff_clean), size = MeshDensity.T_META, bold = true)
        for (l in lines) {
            when (l.state) {
                ProfileDiff.State.SAME -> KvRow("=  " + l.field, l.declared)
                ProfileDiff.State.DIFFERENT -> KvRow("≠  " + l.field, stringResource(R.string.mesh_diff_pair, l.declared.ifBlank { "-" }, l.stored.ifBlank { "-" }))
                ProfileDiff.State.MISSING -> KvRow("−  " + l.field, stringResource(R.string.mesh_diff_missing))
                ProfileDiff.State.EXTRA -> KvRow("+  " + l.field, stringResource(R.string.mesh_diff_extra))
            }
        }
    }
}

/** The QR carries the template's directives only: comments are prose for a reader and cost QR capacity. */
internal fun qrPayload(text: String): String =
    text.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n").replace(Regex("\n{2,}"), "\n").trim()

/** [text] as a QR code drawn straight to the canvas (no bitmap), black on white so any scanner reads it. */
@Composable
fun MeshQr(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
        }.getOrNull()
    }
    if (matrix == null) { MText(stringResource(R.string.mesh_qr_too_big), size = MeshDensity.T_META, muted = true); return }
    Canvas(modifier.padding(top = MeshDensity.dp(MeshDensity.S6)).size(MeshDensity.dp(MeshDensity.QR)).background(Color.White).testTag("mesh:qr")) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
        }
    }
}
