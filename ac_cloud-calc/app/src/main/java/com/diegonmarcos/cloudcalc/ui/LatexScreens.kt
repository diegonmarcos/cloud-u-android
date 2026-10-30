@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudcalc.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.Latex
import com.diegonmarcos.cloudcalc.Logic
import com.diegonmarcos.cloudcalc.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The test tags of the LaTeX page. */
object LatexTags {
    const val SEARCH = "latex_search"
    const val INPUT = "latex_input"
    const val COPY = "latex_copy"
    const val EDIT = "latex_edit"
    const val SHARE = "latex_share"
    const val FAVOURITE = "latex_favourite"
    const val SAVE = "latex_save"
    const val EVALUATE = "latex_evaluate"
    const val EVAL_RESULT = "latex_eval_result"
    const val ERROR = "latex_error"
    fun chip(id: String) = "latex_chip_$id"
    fun symbol(label: String) = "latex_symbol_$label"
}

/**
 * The drawing surface for TeX: ONE WebView over the bundled KaTeX (build.json::latex.renderer), network loads blocked. Kotlin
 * talks to the page with [run] (evaluateJavascript), the page answers through the bridge below.
 */
class TexHandle {
    internal var web: WebView? = null
    private var loaded = false
    private val queue = mutableListOf<String>()

    /** Run [js] in the page; before the page has loaded it waits its turn. */
    fun run(js: String) {
        val w = web
        if (w != null && loaded) w.evaluateJavascript(js, null) else queue += js
    }

    internal fun pageLoaded() {
        loaded = true
        val w = web ?: return
        queue.toList().forEach { w.evaluateJavascript(it, null) }
        queue.clear()
    }

    /** The view as a bitmap drawn in the light share palette, or null when there is no view to draw. */
    fun snapshot(cfg: Latex.Config, current: String, onBitmap: (Bitmap?) -> Unit) {
        val w = web
        if (w == null || !loaded || w.width <= 0 || w.height <= 0) { onBitmap(null); return }
        w.evaluateJavascript("setTheme(${JSONObject.quote(cfg.shareTheme)})", null)
        w.postDelayed({
            val bmp = runCatching { Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888).also { w.draw(Canvas(it)) } }.getOrNull()
            w.evaluateJavascript("setTheme(${JSONObject.quote(current)})", null)
            onBitmap(bmp)
        }, 200)
    }
}

/** What the page tells Kotlin; its methods run on a WebView thread, so each hops to the main thread. */
private class TexBridge(
    val main: Handler,
    val onPick: (String) -> Unit,
    val onStatus: (Boolean, String) -> Unit,
    val onSize: (Int) -> Unit,
) {
    @JavascriptInterface fun pick(id: String) { main.post { onPick(id) } }
    @JavascriptInterface fun status(ok: Boolean, message: String) { main.post { onStatus(ok, message) } }
    @JavascriptInterface fun size(px: Int) { main.post { onSize(px) } }
}

private fun css(c: Color): String = String.format("#%06X", c.toArgb() and 0xFFFFFF)

@Composable
private fun themeJson(): String {
    val c = MaterialTheme.colorScheme
    return JSONObject().put("bg", css(c.surface)).put("fg", css(c.onSurface)).put("muted", css(c.onSurfaceVariant))
        .put("accent", css(c.primary)).put("err", css(c.error)).put("line", css(c.outlineVariant)).toString()
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
@Composable
fun TexView(
    handle: TexHandle,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit = {},
    onStatus: (Boolean, String) -> Unit = { _, _ -> },
    onSize: (Int) -> Unit = {},
) {
    val cfg = Latex.config
    val theme = themeJson()
    val bg = MaterialTheme.colorScheme.surface.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx: Context -> buildWebView(ctx, handle, bg, cfg, onPick, onStatus, onSize) },
        onRelease = { v -> if (v is WebView) { v.stopLoading(); v.destroy() }; if (handle.web === v) handle.web = null },
    )
    // The theme is pushed on every change; the page applies it before the content arrives.
    SideEffect { handle.run("setTheme(${JSONObject.quote(theme)})") }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun buildWebView(
    ctx: Context, handle: TexHandle, bg: Int, cfg: Latex.Config,
    onPick: (String) -> Unit, onStatus: (Boolean, String) -> Unit, onSize: (Int) -> Unit,
): android.view.View {
    // A device (or a test host) without a WebView draws nothing instead of crashing the page.
    return runCatching { configured(ctx, handle, bg, cfg, onPick, onStatus, onSize) }.getOrElse { android.view.View(ctx) }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun configured(
    ctx: Context, handle: TexHandle, bg: Int, cfg: Latex.Config,
    onPick: (String) -> Unit, onStatus: (Boolean, String) -> Unit, onSize: (Int) -> Unit,
): WebView {
    val made = WebView(ctx)
    made.setBackgroundColor(bg)
    made.settings.javaScriptEnabled = true
    // Offline by construction: nothing is fetched, the page and its fonts are the APK's assets.
    made.settings.blockNetworkLoads = true
    made.settings.allowFileAccess = true
    made.settings.allowContentAccess = false
    made.settings.allowFileAccessFromFileURLs = true
    made.settings.setSupportZoom(false)
    made.settings.textZoom = 100
    made.addJavascriptInterface(TexBridge(Handler(Looper.getMainLooper()), onPick, onStatus, onSize), cfg.bridge)
    made.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) { handle.pageLoaded() }
    }
    handle.web = made
    made.loadUrl("file:///android_asset/" + cfg.viewer)
    return made
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private fun catalogOf(ctx: Context): Latex.Catalog? = runCatching {
    Latex.parseCatalog(ctx.assets.open(Latex.config.catalog).bufferedReader().use { it.readText() })
}.getOrNull()

/** Share LaTeX [text] as plain text. */
private fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.latex_share_title)))
}

/** Write [bmp] to the share cache and hand it (and the source text) to the system share sheet. */
private fun shareImage(ctx: Context, bmp: Bitmap, text: String) {
    val cfg = Latex.config
    val dir = File(ctx.cacheDir, cfg.shareDir).apply { mkdirs() }
    val f = File(dir, cfg.shareName)
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND).setType(cfg.shareMime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.latex_share_title)))
}

// ── Library ────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
fun LatexLibraryMode(mode: Declarations.Mode) {
    val cfg = Latex.config
    val ctx = LocalContext.current
    val model = LocalCalcState.current.latex
    val catalog = remember { catalogOf(ctx) }
    if (catalog == null) {
        Text(stringResource(R.string.latex_catalog_missing), Modifier.padding(CalcMetrics.gutter))
        return
    }
    var query by rememberSaveable(mode.id) { mutableStateOf("") }
    var chip by rememberSaveable(mode.id) { mutableStateOf("") }
    var selectedId by rememberSaveable(mode.id) { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> (catalog.formulas + model.mine).firstOrNull { it.id == id } }
    BackHandler(enabled = selected != null) { selectedId = null }
    if (selected != null) {
        LatexDetail(catalog, selected) { selectedId = null }
        return
    }
    val shown = Latex.filter(catalog, model.mine.toList(), model.favourites.toSet(), chip, query, cfg)
    val label = (catalog.categories + cfg.mine).associate { it.id to it.label }
    val handle = remember { TexHandle() }
    val empty = stringResource(R.string.latex_empty)
    Column(Modifier.fillMaxSize().padding(horizontal = CalcMetrics.gutter)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text(stringResource(R.string.latex_search)) },
            modifier = Modifier.fillMaxWidth().testTag(LatexTags.SEARCH),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            items(Latex.chips(catalog, cfg), key = { it.id }) { c ->
                FilterChip(selected = chip == c.id, onClick = { chip = c.id }, label = { Text(c.label) }, modifier = Modifier.testTag(LatexTags.chip(c.id.ifEmpty { "all" })))
            }
        }
        // ONE page lists every formula: the list is pushed to it whenever the search or the chip changes it.
        LaunchedEffect(shown) {
            val rows = JSONArray()
            shown.forEach { f -> rows.put(JSONObject().put("id", f.id).put("name", f.heading).put("category", label[f.category].orEmpty()).put("tex", f.tex)) }
            handle.run("showList(${JSONObject.quote(rows.toString())}, ${JSONObject.quote(empty)})")
        }
        TexView(handle, Modifier.fillMaxWidth().weight(1f), onPick = { selectedId = it })
    }
}

@Composable
private fun LatexDetail(catalog: Latex.Catalog, f: Latex.Formula, onBack: () -> Unit) {
    val cfg = Latex.config
    val ctx = LocalContext.current
    val state = LocalCalcState.current
    val model = state.latex
    val clipboard = LocalClipboardManager.current
    val handle = remember { TexHandle() }
    var heightDp by remember { mutableIntStateOf(120) }
    var menu by remember { mutableStateOf(false) }
    val theme = themeJson()
    val editor = Declarations.modes.firstOrNull { it.kind == CalcState.KIND_LATEX_EDITOR }
    fun record(result: String = "") {
        if (state.history.firstOrNull()?.let { it.expr == f.tex && it.kind == cfg.historyKind } != true)
            state.remember(Latex.entry(editor?.id ?: "", f.tex, result, cfg), historyMax())
    }
    LaunchedEffect(f.id) { handle.run("showOne(${JSONObject.quote(f.tex)}, 24, ${JSONObject.quote(f.description)})") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter), verticalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.latex_back)) }
            Text(f.heading, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Text((catalog.categories.firstOrNull { it.id == f.category }?.label ?: cfg.mine.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TexView(handle, Modifier.fillMaxWidth().height(heightDp.dp), onSize = { heightDp = it.coerceAtLeast(60) + 8 })
        Text(stringResource(R.string.latex_source), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(f.tex, style = TextStyle(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface) }
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(f.tex)); record() }, modifier = Modifier.testTag(LatexTags.COPY)) { Text(stringResource(R.string.latex_copy)) }
            FilledTonalButton(onClick = { state.openLatexEditor(LatexModel.Draft(f.tex, if (f.mine) f.id else null, if (f.mine) f.title else "")) }, modifier = Modifier.testTag(LatexTags.EDIT)) { Text(stringResource(R.string.latex_edit)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.testTag(LatexTags.SHARE)) { Text(stringResource(R.string.latex_share)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.latex_share_image)) }, onClick = {
                    menu = false
                    handle.snapshot(cfg, theme) { bmp -> if (bmp != null) { shareImage(ctx, bmp, f.tex); record() } else shareText(ctx, f.tex) }
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.latex_share_text)) }, onClick = { menu = false; shareText(ctx, f.tex); record() })
            }
            OutlinedButton(onClick = { model.toggleFavourite(f.id) }, modifier = Modifier.testTag(LatexTags.FAVOURITE)) {
                Text(stringResource(if (model.isFavourite(f.id)) R.string.latex_unfavourite else R.string.latex_favourite))
            }
            if (f.mine) TextButton(onClick = { model.deleteMine(f.id); onBack() }) { Text(stringResource(R.string.delete)) }
        }
    }
}

// ── Editor ─────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
fun LatexEditorMode(mode: Declarations.Mode) {
    val cfg = Latex.config
    val ctx = LocalContext.current
    val api = LocalCalcApi.current
    val state = LocalCalcState.current
    val model = state.latex
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val handle = remember { TexHandle() }
    val theme = themeJson()
    var field by rememberSaveable(mode.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var mineId by rememberSaveable(mode.id) { mutableStateOf<String?>(null) }
    var title by rememberSaveable(mode.id) { mutableStateOf("") }
    var group by rememberSaveable(mode.id) { mutableStateOf(cfg.palette.firstOrNull()?.id.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var evalText by remember { mutableStateOf("") }
    var naming by remember { mutableStateOf(false) }
    var heightDp by remember { mutableIntStateOf(100) }

    // The Library's Edit (or a History tap) hands a draft over; the editor takes it once.
    LaunchedEffect(model.draft) {
        model.draft?.let { d ->
            field = TextFieldValue(d.tex, TextRange(d.tex.length)); mineId = d.mineId; title = d.title; evalText = ""
            model.draft = null
        }
    }
    // The live preview: re-rendered after typing pauses; a parse error is shown inline and never throws.
    LaunchedEffect(field.text) {
        delay(cfg.debounceMs)
        handle.run("showOne(${JSONObject.quote(field.text)}, 24)")
    }
    fun record(result: String = "") {
        val tex = field.text.trim()
        if (tex.isEmpty()) return
        val top = state.history.firstOrNull()
        if (top != null && top.kind == cfg.historyKind && top.expr == tex && top.result == result) return
        state.remember(Latex.entry(mode.id, tex, result, cfg), historyMax())
    }
    fun evaluate() {
        when (val c = Latex.toExpression(field.text, cfg.eval)) {
            is Latex.Conversion.Unsupported -> evalText = ctx.getString(R.string.latex_not_convertible, c.reason)
            is Latex.Conversion.Ok -> scope.launch {
                val r = Logic.result(io { api.eval(c.expr, mode.options) })
                evalText = if (r.ok) c.expr + " = " + Logic.shown(r.text) else ctx.getString(R.string.latex_eval_failed, r.error.ifBlank { r.messages.joinToString("; ") })
                if (r.ok) record(r.text)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter), verticalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        OutlinedTextField(
            value = field, onValueChange = { field = it },
            label = { Text(if (mineId != null && title.isNotBlank()) title else stringResource(R.string.latex_input)) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            minLines = 3, maxLines = 8,
            modifier = Modifier.fillMaxWidth().testTag(LatexTags.INPUT),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            items(cfg.palette, key = { it.id }) { g -> FilterChip(selected = group == g.id, onClick = { group = g.id }, label = { Text(g.label) }) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            items(cfg.palette.firstOrNull { it.id == group }?.symbols.orEmpty(), key = { it.label + it.insert }) { s ->
                AssistChip(
                    onClick = {
                        val e = Latex.insertAt(field.text, field.selection.start, field.selection.end, s.insert)
                        field = TextFieldValue(e.text, TextRange(e.cursor))
                    },
                    label = { Text(s.label, style = TextStyle(fontFamily = FontFamily.Monospace)) },
                    modifier = Modifier.testTag(LatexTags.symbol(s.label)),
                )
            }
        }
        TexView(
            handle, Modifier.fillMaxWidth().height(heightDp.dp),
            onStatus = { ok, msg -> error = if (ok) "" else msg },
            onSize = { heightDp = it.coerceAtLeast(60) + 8 },
        )
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(LatexTags.ERROR))
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(field.text)); record() }, modifier = Modifier.testTag(LatexTags.COPY)) { Text(stringResource(R.string.latex_copy)) }
            FilledTonalButton(onClick = {
                handle.snapshot(cfg, theme) { bmp -> if (bmp != null) shareImage(ctx, bmp, field.text) else shareText(ctx, field.text); record() }
            }, modifier = Modifier.testTag(LatexTags.SHARE)) { Text(stringResource(R.string.latex_share_image)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            FilledTonalButton(onClick = { if (field.text.isNotBlank()) naming = true }, modifier = Modifier.testTag(LatexTags.SAVE)) { Text(stringResource(R.string.latex_save)) }
            FilledTonalButton(onClick = { evaluate() }, modifier = Modifier.testTag(LatexTags.EVALUATE)) { Text(stringResource(R.string.latex_evaluate)) }
            TextButton(onClick = { field = TextFieldValue(""); mineId = null; title = ""; evalText = "" }) { Text(stringResource(R.string.clear)) }
        }
        if (evalText.isNotEmpty()) Text(evalText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(LatexTags.EVAL_RESULT))
    }
    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.latex_save_title, cfg.mine.label)) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text(stringResource(R.string.latex_name)) }) },
            confirmButton = {
                TextButton(onClick = {
                    val saved = model.saveMine(title, field.text, mineId)
                    mineId = saved.id; title = saved.title; naming = false; record()
                }) { Text(stringResource(R.string.latex_save)) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
