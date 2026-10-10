package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

/**
 * Store ▸ Phone ▸ Search: the whole catalogue, not only the declared apps. Two sources, both on by
 * default: Google Play through the anonymous play-anon session (Aurora's method) and the official
 * F-Droid repo (its signed index). A Play result is FOSS when its package is also in F-Droid, Private
 * otherwise; every F-Droid result is FOSS. Install goes through [ExternalInstall] with the rungs the
 * result was found on (F-Droid first), so the progress row and the shell channel behave exactly as for
 * a declared app. Compose, hosted in the View page by [view].
 */
object StoreSearchPage {
    class Result(val pkg: String, val title: String, val by: String, val fdroid: Boolean, val play: Boolean, val foss: Boolean)

    private enum class Licence(val label: String) { ALL("All"), FOSS("FOSS"), PRIVATE("Private") }

    fun view(ctx: Context, cfg: () -> SourceResolver.Config?): View = ComposeView(ctx).apply { setContent { MaterialTheme(colorScheme = darkColorScheme()) { Page(ctx, cfg) } } }

    /** Both sources, merged by package. Blocking: call off the main thread. */
    fun search(ctx: Context, c: SourceResolver.Config, query: String, play: Boolean, fdroid: Boolean): Pair<List<Result>, List<String>> {
        val notes = ArrayList<String>()
        val by = LinkedHashMap<String, Result>()
        if (fdroid) runCatching { FDroidIndex.search(ctx, c.fdroid, query) }
            .onSuccess { hits -> hits.forEach { by[it.pkg] = Result(it.pkg, it.name, it.license, fdroid = true, play = false, foss = true) } }
            .onFailure { notes += "F-Droid: ${it.message}" }
        val pa = c.playAnon
        if (play && pa == null) notes += "Play search needs resolver.play_anon"
        if (play && pa != null) {
            val fossSet = runCatching { FDroidIndex.packages(ctx, c.fdroid) }.getOrDefault(emptySet())
            runCatching { PlayAnonFetcher.search(ctx, pa, query) }
                .onSuccess { hits -> hits.forEach { h ->
                    val old = by[h.pkg]
                    by[h.pkg] = if (old != null) Result(old.pkg, old.title, old.by.ifBlank { h.creator }, true, true, true)
                                else Result(h.pkg, h.title, h.creator, fdroid = false, play = true, foss = h.pkg in fossSet)
                } }
                .onFailure { notes += if (it is PlayAnonFetcher.TokenUnavailable) "Play token unavailable" else "Play: ${it.message}" }
        }
        return by.values.toList() to notes
    }

    @Composable
    private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
        Text(label, color = Color.White, fontSize = StoreDensity.T_META.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(StoreDensity.dpValue(StoreDensity.S2).dp).background(if (on) Color(0xFF2F855A) else Color(0xFF3A3A44))
                .clickableNoRipple(onClick).padding(horizontal = StoreDensity.dpValue(StoreDensity.S8).dp, vertical = StoreDensity.dpValue(StoreDensity.S6).dp))
    }

    @Composable
    private fun Page(ctx: Context, cfg: () -> SourceResolver.Config?) {
        var query by remember { mutableStateOf("") }
        var usePlay by remember { mutableStateOf(true) }
        var useFdroid by remember { mutableStateOf(true) }
        var licence by remember { mutableStateOf(Licence.ALL) }
        var status by remember { mutableStateOf("") }
        val results = remember { mutableStateListOf<Result>() }
        val lines = remember { mutableStateMapOf<String, String>() }
        // Bumped on every declare/undeclare so the rows re-read DeclaredEdits.
        var declaredTick by remember { mutableStateOf(0) }

        fun run() {
            val q = query.trim(); if (q.isEmpty()) return
            val c = cfg() ?: run { status = "the store's source map is not loaded yet"; return }
            status = "searching…"; results.clear()
            thread(name = "store-search") {
                val (found, notes) = search(ctx, c, q, usePlay, useFdroid)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    results.addAll(found)
                    status = (if (found.isEmpty()) "no results" else "${found.size} results") + notes.joinToString("") { "  ·  $it" }
                }
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StoreDensity.dpValue(StoreDensity.S4).dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Search Play and F-Droid") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }))
                Pill("Search", true) { run() }
            }
            Row { Pill("Play (Aurora)", usePlay) { usePlay = !usePlay }; Pill("F-Droid", useFdroid) { useFdroid = !useFdroid } }
            Row { Licence.values().forEach { l -> Pill(l.label, licence == l) { licence = l } } }
            val shown = results.filter { when (licence) { Licence.ALL -> true; Licence.FOSS -> it.foss; Licence.PRIVATE -> !it.foss } }
            if (status.isNotEmpty()) Text(if (results.isEmpty()) status else "${shown.size} of ${results.size}  ·  $status",
                color = Color(0x99FFFFFF), fontSize = StoreDensity.T_CAPTION.sp)
            val pm = ctx.packageManager
            shown.forEach { r ->
                val installed = runCatching { pm.getPackageInfo(r.pkg, 0) }.isSuccess
                Column(Modifier.fillMaxWidth().padding(vertical = StoreDensity.dpValue(StoreDensity.S2).dp).background(Color(0xFF1C1C24)).padding(horizontal = StoreDensity.dpValue(StoreDensity.S12).dp, vertical = StoreDensity.dpValue(StoreDensity.S8).dp)) {
                    Text(r.title, color = Color.White, fontSize = StoreDensity.T_TITLE.sp)
                    val from = listOfNotNull("F-Droid".takeIf { r.fdroid }, "Play".takeIf { r.play }).joinToString(" + ")
                    Text(listOf(r.pkg, r.by, from, if (r.foss) "FOSS" else "Private").filter { it.isNotBlank() }.joinToString("  ·  "),
                        color = Color(0x99FFFFFF), fontSize = StoreDensity.T_CAPTION.sp)
                    val kinds = listOfNotNull(SourceResolver.KIND_FDROID.takeIf { r.fdroid }, SourceResolver.KIND_PLAY_ANON.takeIf { r.play })
                    val c0 = cfg()
                    val declared = declaredTick.let { c0 != null && DeclaredEdits.apply(ctx, c0, r.pkg, SourceResolver.resolve(c0, r.pkg)).declared }
                    fun install(declare: Boolean) {
                        val c = cfg() ?: return
                        if (declare) { DeclaredEdits.declare(ctx, r.pkg, r.title, kinds); declaredTick++ }
                        val app = SourceResolver.External(r.pkg, r.title, listOfNotNull(
                            SourceResolver.Source.FDroid.takeIf { r.fdroid }, SourceResolver.Source.PlayAnon.takeIf { r.play }), declared = declare)
                        lines[r.pkg] = "installing…"
                        thread(name = "store-search-install") {
                            val err = ExternalInstall.run(ctx, c, app)
                            android.os.Handler(android.os.Looper.getMainLooper()).post { lines[r.pkg] = err?.let { "✗ $it" } ?: "✓ install started" }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (installed) Text("✓ installed  ", color = Color(0xFF48BB78), fontSize = StoreDensity.T_CAPTION.sp)
                        else { Pill("Install", false) { install(false) }; if (!declared) Pill("Install + declare", false) { install(true) } }
                        Pill(if (declared) "− Undeclare" else "＋ Declare", false) {
                            if (declared) DeclaredEdits.undeclare(ctx, r.pkg) else DeclaredEdits.declare(ctx, r.pkg, r.title, kinds)
                            declaredTick++
                        }
                    }
                    lines[r.pkg]?.let { Text(it, color = Color(0xFF48BB78), fontSize = StoreDensity.T_CAPTION.sp) }
                }
            }
        }
    }
}
