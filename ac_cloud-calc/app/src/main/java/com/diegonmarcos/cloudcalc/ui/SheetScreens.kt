@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudcalc.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.sheet.A1
import com.diegonmarcos.cloudcalc.sheet.Area
import com.diegonmarcos.cloudcalc.sheet.Fmt
import com.diegonmarcos.cloudcalc.sheet.Format
import com.diegonmarcos.cloudcalc.sheet.Refs
import com.diegonmarcos.cloudcalc.sheet.Value
import com.diegonmarcos.cloudcalc.sheetui.SheetHub
import com.diegonmarcos.cloudcalc.sheetui.SheetState
import kotlin.math.roundToInt

/** Test tags of the Sheet page. */
object SheetTags {
    const val ROOT = "sheet_root"
    const val GRID = "sheet_grid"
    const val NAME_BOX = "sheet_name_box"
    const val EDIT_BAR = "sheet_edit_bar"
    const val COMMIT = "sheet_commit"
    const val TABS = "sheet_tabs"
    const val ADD_TAB = "sheet_add_tab"
    const val UNDO = "sheet_undo"
    const val REDO = "sheet_redo"
    const val EXTEND = "sheet_extend"
    const val STATUS_BAR = "sheet_status_bar"
    fun stat(id: String) = "sheet_stat_$id"
    fun handle(kind: String) = "sheet_handle_$kind"
    fun tab(name: String) = "sheet_tab_$name"
    fun cell(c: Int, r: Int) = "sheet_cell_${A1.cell(c, r)}"
    fun action(id: String) = "sheet_action_$id"
}

/** The Sheet page: tool row, name box + edit bar, the grid, and the sheet tabs. */
@Composable
fun SheetMode(@Suppress("UNUSED_PARAMETER") mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val st = remember { SheetHub.get(ctx) }
    st.rev // subscribe: every change redraws the page
    // Autosave: a moment after the last change, off the main thread.
    LaunchedEffect(st.rev) {
        kotlinx.coroutines.delay(600)
        val json = st.wb.toJson(); val n = st.name
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { st.files.write(n, json); st.files.lastName = n } }
    }
    Column(Modifier.fillMaxSize().testTag(SheetTags.ROOT)) {
        SheetToolbar(st)
        SheetEditBar(st)
        Box(Modifier.weight(1f).fillMaxWidth()) { SheetGrid(st) }
        SheetTabs(st)
        // The status bar: stats of the selected numbers, while no formula is being edited.
        SheetStatusBar(st)
    }
}

@Composable
private fun Tool(id: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(SheetTags.action(id))) { Text(label, fontSize = 12.sp, maxLines = 1) }
}

@Composable
private fun SheetToolbar(st: SheetState) {
    st.rev // read: Undo/Redo enablement follows every change
    val ctx = LocalContext.current
    var fmtMenu by remember { mutableStateOf(false) }
    var fileMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }   // "saveas" | "open" | "new"
    val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()?.let { st.importCsv(it) }
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()?.let {
            st.message = ctx.getString(if (st.importJson(it)) R.string.sheet_opened else R.string.sheet_not_workbook)
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(st.exportCsv()) } }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(st.exportJson()) } }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { fileMenu = true }, modifier = Modifier.testTag(SheetTags.action("file"))) { Text(stringResource(R.string.sheet_file), fontSize = 12.sp) }
            DropdownMenu(expanded = fileMenu, onDismissRequest = { fileMenu = false }) {
                listOf(
                    "new" to R.string.sheet_new, "open" to R.string.sheet_open, "saveas" to R.string.sheet_save_as,
                    "icsv" to R.string.sheet_import_csv, "ecsv" to R.string.sheet_export_csv,
                    "ijson" to R.string.sheet_import_json, "ejson" to R.string.sheet_export_json,
                ).forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, modifier = Modifier.testTag(SheetTags.action(id)), onClick = {
                        fileMenu = false
                        when (id) {
                            "new", "open", "saveas" -> dialog = id
                            "icsv" -> importCsv.launch(arrayOf("*/*"))
                            "ijson" -> importJson.launch(arrayOf("*/*"))
                            "ecsv" -> exportCsv.launch(st.sheet + ".csv")
                            "ejson" -> exportJson.launch(st.name + ".cloudsheet.json")
                        }
                    })
                }
            }
        }
        TextButton(onClick = { st.undo() }, enabled = st.wb.canUndo, modifier = Modifier.testTag(SheetTags.UNDO)) { Text(stringResource(R.string.sheet_undo), fontSize = 12.sp) }
        TextButton(onClick = { st.redo() }, enabled = st.wb.canRedo, modifier = Modifier.testTag(SheetTags.REDO)) { Text(stringResource(R.string.sheet_redo), fontSize = 12.sp) }
        Tool("cut", stringResource(R.string.sheet_cut)) { st.cut(ctx) }
        Tool("copy", stringResource(R.string.sheet_copy)) { st.copy(ctx) }
        Tool("paste", stringResource(R.string.sheet_paste)) { st.paste(ctx) }
        Tool("clear", stringResource(R.string.sheet_clear)) { st.clearSelection() }
        Tool("insrow", stringResource(R.string.sheet_ins_row)) { st.insertRow() }
        Tool("delrow", stringResource(R.string.sheet_del_row)) { st.deleteRow() }
        Tool("inscol", stringResource(R.string.sheet_ins_col)) { st.insertCol() }
        Tool("delcol", stringResource(R.string.sheet_del_col)) { st.deleteCol() }
        TextButton(onClick = { st.extend = !st.extend }, modifier = Modifier.testTag(SheetTags.EXTEND)) {
            Text(stringResource(if (st.extend) R.string.sheet_range_on else R.string.sheet_range), fontSize = 12.sp,
                color = if (st.extend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        Box {
            TextButton(onClick = { fmtMenu = true }, modifier = Modifier.testTag(SheetTags.action("format"))) { Text(stringResource(R.string.sheet_format), fontSize = 12.sp) }
            DropdownMenu(expanded = fmtMenu, onDismissRequest = { fmtMenu = false }) {
                listOf(
                    "general" to (R.string.sheet_fmt_general to Format.GENERAL),
                    "n0" to (R.string.sheet_fmt_number0 to Format.number(0)),
                    "n2" to (R.string.sheet_fmt_number2 to Format.number(2)),
                    "pct" to (R.string.sheet_fmt_percent to Format.percent(0)),
                    "cur" to (R.string.sheet_fmt_currency to Format.currency("$", 2)),
                    "date" to (R.string.sheet_fmt_date to Format.DATE),
                ).forEach { (id, p) ->
                    DropdownMenuItem(text = { Text(stringResource(p.first)) }, modifier = Modifier.testTag(SheetTags.action("fmt_$id")), onClick = { fmtMenu = false; st.format(p.second) })
                }
            }
        }
    }
    if (st.message.isNotBlank()) Text(st.message, Modifier.padding(horizontal = CalcMetrics.gutter), style = MaterialTheme.typography.labelSmall)
    when (dialog) {
        "saveas", "new" -> NameDialog(
            title = stringResource(if (dialog == "new") R.string.sheet_new else R.string.sheet_save_as),
            initial = if (dialog == "new") "" else st.name, onDismiss = { dialog = "" },
        ) { n -> if (dialog == "new") st.newWorkbook(n) else st.saveAs(n); dialog = "" }
        "open" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.sheet_cancel)) } },
            title = { Text(stringResource(R.string.sheet_open)) },
            text = {
                Column {
                    val names = st.files.names()
                    if (names.isEmpty()) Text(stringResource(R.string.sheet_no_files))
                    names.forEach { n ->
                        Text(n, Modifier.fillMaxWidth().clickable { st.open(n); dialog = "" }.padding(CalcMetrics.gap).testTag(SheetTags.action("file_$n")))
                    }
                }
            },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.testTag(SheetTags.action("name_field"))) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onOk(text) }, modifier = Modifier.testTag(SheetTags.action("name_ok"))) { Text(stringResource(R.string.sheet_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sheet_cancel)) } },
    )
}

/** The colours of the references of a formula being edited: the n-th reference gets the n-th colour in the edit bar and in the grid. */
private val RefColors = listOf(
    Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFF34A853), Color(0xFFA142F4),
    Color(0xFFFF6D01), Color(0xFF00ACC1), Color(0xFFE91E63), Color(0xFF8D6E63),
)
private fun refColor(i: Int) = RefColors[i % RefColors.size]

/** The edit bar's text with each reference in its colour (the text itself is unchanged, so the caret maps one to one). */
private fun refTransform(tokens: List<Refs.Token>) = VisualTransformation { text ->
    val styled: AnnotatedString = buildAnnotatedString {
        append(text.text)
        tokens.forEachIndexed { i, t -> if (t.range.last < text.length) addStyle(SpanStyle(color = refColor(i)), t.range.first, t.range.last + 1) }
    }
    TransformedText(styled, OffsetMapping.Identity)
}

/** Name box (the selection; type A1:C5 to select a range) and the edit bar, which shows the RAW text: the formula. */
@Composable
private fun SheetEditBar(st: SheetState) {
    var box by remember { mutableStateOf("") }
    LaunchedEffect(st.anchorC, st.anchorR, st.endC, st.endR, st.rev, st.wb.active, st.editing) {
        box = st.area.let { if (it.cols > 1000 || it.rows > 1000) "${it.rows}×${it.cols}" else it.label() }
        st.syncDraft()
    }
    val transform = remember(st.draft.text) { refTransform(Refs.tokens(st.draft.text)) }
    Row(Modifier.fillMaxWidth().padding(horizontal = CalcMetrics.small), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            box, { box = it }, Modifier.width(110.dp).testTag(SheetTags.NAME_BOX), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { A1.parseArea(box)?.let { st.selectArea(it) } }),
        )
        OutlinedTextField(
            st.draft, { st.type(it) }, Modifier.weight(1f).testTag(SheetTags.EDIT_BAR).onFocusChanged { if (it.isFocused) st.beginEdit() }, singleLine = true,
            placeholder = { Text(stringResource(R.string.sheet_edit_hint)) },
            visualTransformation = transform,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { st.commitEdit() }),
        )
        TextButton(onClick = { st.commitEdit() }, modifier = Modifier.testTag(SheetTags.COMMIT)) { Text("✓") }
        TextButton(onClick = { st.cancelEdit() }) { Text("✗") }
    }
}

/**
 * The virtualised grid: rows are a LazyColumn (a million of them cost nothing), each row composes ONLY the
 * columns in view; the row-number column and the column-letter row are frozen by offsetting them against the
 * horizontal scroll. Column widths and row heights are the sheet's, in dp.
 */
@Composable
private fun SheetGrid(st: SheetState) {
    val rev = st.rev
    val sheet = st.wb.current
    val sheetName = sheet.name
    val density = LocalDensity.current
    val defW = CalcMetrics.cellWidth.value.roundToInt()
    val defH = CalcMetrics.cellHeight.value.roundToInt()
    val headerPx = with(density) { CalcMetrics.headerWidth.roundToPx() }
    val hScroll = rememberScrollState()
    val listState = rememberLazyListState()
    var viewport by remember { mutableIntStateOf(0) }
    var nCols by remember { mutableIntStateOf(52) }
    val dp = density.density
    val xs = remember(rev, sheetName, nCols, dp) {
        val a = IntArray(nCols + 1)
        for (c in 0 until nCols) a[c + 1] = a[c] + ((sheet.colWidths[c] ?: defW) * dp).roundToInt()
        a
    }
    val totalPx = xs[nCols] + headerPx
    val first by remember(xs) { derivedStateOf { colAt(xs, hScroll.value) } }
    val last by remember(xs) { derivedStateOf { colAt(xs, hScroll.value + viewport).coerceAtMost(nCols - 1) } }
    LaunchedEffect(viewport, xs) {
        snapshotFlow { hScroll.value }.collect { x ->
            if (x + viewport > totalPx - 2 * xs[1] && nCols < A1.MAX_COLS - 26) nCols += 26
        }
    }
    val totalDp = with(density) { totalPx.toDp() }
    val sel = st.area
    // While a formula is edited, each of its references is outlined in its colour (the same as in the edit bar).
    val refBoxes: List<Pair<Area, Color>> = if (!st.formulaEditing) emptyList() else
        Refs.tokens(st.draft.text).mapIndexedNotNull { i, t ->
            if ((t.sheet ?: st.editingSheet).equals(sheetName, ignoreCase = true)) t.area to refColor(i) else null
        }
    // A pointer position inside cell (c, r), in px from its top-left, back to the cell it is over (a drag leaves its first cell).
    val locate: (Int, Int, Float, Float) -> Pair<Int, Int> = remember(xs, rev, sheetName, dp, nCols) {
        { c, r, dx, dy ->
            val cc = colAt(xs, (xs[c] + dx).toInt().coerceIn(0, xs[nCols] - 1))
            fun hpx(i: Int) = ((sheet.rowHeights[i] ?: defH) * dp).roundToInt().coerceAtLeast(1)
            var rr = r; var y = dy
            while (y >= hpx(rr) && rr < A1.MAX_ROWS - 1) { y -= hpx(rr); rr++ }
            while (y < 0 && rr > 0) { rr--; y += hpx(rr) }
            cc to rr
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it.width }.testTag(SheetTags.GRID)) {
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            Column(Modifier.width(totalDp).fillMaxHeight()) {
                // frozen column letters
                Box(Modifier.width(totalDp).height(CalcMetrics.cellHeight).zIndex(2f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.offset { IntOffset(hScroll.value, 0) }.width(CalcMetrics.headerWidth).height(CalcMetrics.cellHeight)
                        .background(MaterialTheme.colorScheme.surfaceVariant).border(CalcMetrics.hairline, MaterialTheme.colorScheme.outline))
                    for (c in first..last) {
                        val w = with(density) { (xs[c + 1] - xs[c]).toDp() }
                        ColumnHeader(st, c, w, Modifier.offset { IntOffset(headerPx + xs[c], 0) })
                    }
                    Box(Modifier.offset { IntOffset(hScroll.value, 0) }.width(CalcMetrics.headerWidth).height(CalcMetrics.cellHeight)
                        .background(MaterialTheme.colorScheme.surfaceVariant).border(CalcMetrics.hairline, MaterialTheme.colorScheme.outline))
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    items(A1.MAX_ROWS) { r ->
                        val h = (sheet.rowHeights[r] ?: defH).dp
                        Box(Modifier.width(totalDp).height(h)) {
                            for (c in first..last) {
                                val w = with(density) { (xs[c + 1] - xs[c]).toDp() }
                                GridCell(st, sheetName, c, r, w, h, sel, refBoxes.lastOrNull { it.first.contains(c, r) }?.second, locate, Modifier.offset { IntOffset(headerPx + xs[c], 0) })
                            }
                            RowHeader(st, r, h, Modifier.offset { IntOffset(hScroll.value, 0) }.zIndex(1f))
                        }
                    }
                }
            }
        }
    }
}

/** The last column whose left edge is at or before [x]. */
private fun colAt(xs: IntArray, x: Int): Int {
    var lo = 0; var hi = xs.size - 2
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (xs[mid] <= x) lo = mid else hi = mid - 1
    }
    return lo
}

@Composable
private fun ColumnHeader(st: SheetState, c: Int, w: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val density = LocalDensity.current
    val on = c in st.area.c1..st.area.c2
    Box(
        modifier.width(w).height(CalcMetrics.cellHeight)
            .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(CalcMetrics.hairline, MaterialTheme.colorScheme.outline)
            .clickable { if (!st.formulaEditing) st.selectCol(c) },
        contentAlignment = Alignment.Center,
    ) {
        Text(A1.colName(c), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Box(
            Modifier.align(Alignment.CenterEnd).width(CalcMetrics.resizeHandle).fillMaxHeight().pointerInput(c) {
                var cur = w.value
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    cur += dx / density.density
                    st.resizeCol(c, cur.roundToInt())
                }
            },
        )
    }
}

@Composable
private fun RowHeader(st: SheetState, r: Int, h: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val density = LocalDensity.current
    val on = r in st.area.r1..st.area.r2
    Box(
        modifier.width(CalcMetrics.headerWidth).height(h)
            .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(CalcMetrics.hairline, MaterialTheme.colorScheme.outline)
            .clickable { if (!st.formulaEditing) st.selectRow(r) },
        contentAlignment = Alignment.Center,
    ) {
        Text((r + 1).toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(CalcMetrics.resizeHandle / 2).pointerInput(r) {
                var cur = h.value
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    cur += dy / density.density
                    st.resizeRow(r, cur.roundToInt())
                }
            },
        )
    }
}

@Composable
private fun GridCell(
    st: SheetState, sheet: String, c: Int, r: Int, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, sel: Area,
    refColor: Color?, locate: (Int, Int, Float, Float) -> Pair<Int, Int>, modifier: Modifier,
) {
    val v = st.wb.value(sheet, c, r)
    val text = st.wb.display(sheet, c, r)
    val inSel = sel.contains(c, r)
    val active = c == st.anchorC && r == st.anchorR
    val scheme = MaterialTheme.colorScheme
    val editingFormula = st.formulaEditing
    var startDrag by remember { mutableStateOf(false) }
    var endDrag by remember { mutableStateOf(false) }
    Box(
        modifier.width(w).height(h)
            .background(if (inSel) scheme.primary.copy(alpha = 0.18f) else Color.Transparent)
            .background(refColor?.copy(alpha = 0.14f) ?: Color.Transparent)
            .border(if (active || refColor != null) 2.dp else CalcMetrics.hairline, refColor ?: if (active) scheme.primary else scheme.outline.copy(alpha = 0.5f))
            .clickable { st.tapCell(c, r) }
            // Tap-and-hold, then drag: a range selection, or while a formula is edited a range reference (A1:B5).
            .pointerInput(c, r, locate) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { st.dragBegin(c, r) },
                    onDrag = { change, _ -> change.consume(); locate(c, r, change.position.x, change.position.y).let { (cc, rr) -> st.dragTo(cc, rr) } },
                    onDragEnd = { st.dragEnd() },
                    onDragCancel = { st.dragEnd() },
                )
            }
            .padding(horizontal = CalcMetrics.small)
            .testTag(SheetTags.cell(c, r)),
        contentAlignment = if (v is Value.Num) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Clip,
            color = if (v is Value.Err) scheme.error else scheme.onSurface,
            textAlign = if (v is Value.Num) TextAlign.End else TextAlign.Start,
        )
        // Selection handles (not while a formula is edited): drag one to grow or shrink the range. The cell a drag began in keeps its handle until release.
        if (!editingFormula) {
            if (active || startDrag) SelectionHandle(st, false, c, r, w, h, locate, Modifier.align(Alignment.TopStart)) { startDrag = it }
            if ((c == st.endC && r == st.endR) || endDrag) SelectionHandle(st, true, c, r, w, h, locate, Modifier.align(Alignment.BottomEnd)) { endDrag = it }
        }
    }
}

@Composable
private fun SelectionHandle(
    st: SheetState, end: Boolean, c: Int, r: Int, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp,
    locate: (Int, Int, Float, Float) -> Pair<Int, Int>, modifier: Modifier, dragging: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val size = CalcMetrics.resizeHandle
    // The handle sits at a corner of its cell: its own (0,0) is that far from the cell's top-left.
    val ox = if (end) with(density) { (w - size).toPx() } else 0f
    val oy = if (end) with(density) { (h - size).toPx() } else 0f
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            .testTag(SheetTags.handle(if (end) "end" else "start"))
            .pointerInput(c, r, end, locate) {
                detectDragGestures(
                    onDragStart = { dragging(true) },
                    onDrag = { change, _ ->
                        change.consume()
                        locate(c, r, change.position.x + ox, change.position.y + oy).let { (cc, rr) -> st.handleTo(end, cc, rr) }
                    },
                    onDragEnd = { dragging(false); st.dragEnd() },
                    onDragCancel = { dragging(false); st.dragEnd() },
                )
            },
    )
}

/**
 * The status bar: for the selected NUMBERS (text, empty cells and booleans are ignored; errors are counted, not
 * summed) Sum, Avg, Median, StDev (sample), Min, Max, the confidence intervals of the mean at 67%, 97.5% and 99%
 * as [low, high] and the count. The intervals use Student's t below 30 values and the normal z from 30 up, and the
 * bar says which. Numbers are shown in the number format of the first selected number. A tap copies a figure.
 */
@Composable
private fun SheetStatusBar(st: SheetState) {
    val s = st.selectionStats() ?: return
    val ctx = LocalContext.current
    val spread = if (s.format.kind == Format.Kind.DATE) Format.GENERAL else s.format   // a sum or deviation of dates is a count of days
    fun fmt(x: Double, f: Format = s.format) = Fmt.display(Value.Num(x), f)
    val pct = { level: Double -> Fmt.general(level * 100) }
    val items = buildList {
        add(Triple("sum", ctx.getString(R.string.sheet_stat_sum), fmt(s.sum, spread)))
        add(Triple("avg", ctx.getString(R.string.sheet_stat_avg), fmt(s.avg)))
        add(Triple("median", ctx.getString(R.string.sheet_stat_median), fmt(s.median)))
        s.stdev?.let { add(Triple("stdev", ctx.getString(R.string.sheet_stat_stdev), fmt(it, spread))) }
        add(Triple("min", ctx.getString(R.string.sheet_stat_min), fmt(s.min)))
        add(Triple("max", ctx.getString(R.string.sheet_stat_max), fmt(s.max)))
        s.ci.forEach { add(Triple("ci_${pct(it.level)}", ctx.getString(R.string.sheet_stat_ci, pct(it.level)), "[" + fmt(it.low) + ", " + fmt(it.high) + "]")) }
        if (s.ci.isNotEmpty()) add(Triple("dist", ctx.getString(R.string.sheet_stat_dist),
            ctx.getString(if (s.dist == "t") R.string.sheet_stat_dist_t else R.string.sheet_stat_dist_z, s.n)))
        add(Triple("count", ctx.getString(R.string.sheet_stat_count), s.n.toString()))
        if (s.errors > 0) add(Triple("errors", ctx.getString(R.string.sheet_stat_errors), s.errors.toString()))
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState())
            .padding(horizontal = CalcMetrics.small).testTag(SheetTags.STATUS_BAR),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(CalcMetrics.gap),
    ) {
        items.forEach { (id, label, value) ->
            Column(
                Modifier.clickable {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                    st.message = ctx.getString(R.string.sheet_stat_copied, value)
                }.padding(vertical = CalcMetrics.small).testTag(SheetTags.stat(id)),
            ) {
                Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(value, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** The sheet tabs at the bottom: one per sheet, + to add, the selected tab's menu renames, duplicates, deletes and moves it. */
@Composable
private fun SheetTabs(st: SheetState) {
    st.rev // read: the tabs follow add / rename / delete / reorder
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf("") }
    val names = st.wb.sheetNames()
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { st.addSheet() }, modifier = Modifier.testTag(SheetTags.ADD_TAB)) { Text("＋") }
        LazyRow(Modifier.weight(1f).testTag(SheetTags.TABS)) {
            itemsIndexed(names, key = { _, n -> n }) { i, n ->
                val on = i == st.wb.active
                Box {
                    Text(
                        n, maxLines = 1,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(CalcMetrics.small)
                            .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(CalcMetrics.gap))
                            .combinedClickable(onClick = { st.switchTo(i) }, onLongClick = { st.switchTo(i); menu = true })
                            .padding(horizontal = CalcMetrics.gutter, vertical = CalcMetrics.gap)
                            .testTag(SheetTags.tab(n)),
                    )
                }
            }
        }
        Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.testTag(SheetTags.action("tabmenu"))) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val cur = st.sheet
                listOf(
                    "rename" to R.string.sheet_rename, "duplicate" to R.string.sheet_duplicate, "delete" to R.string.sheet_delete,
                    "left" to R.string.sheet_move_left, "right" to R.string.sheet_move_right,
                ).forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, modifier = Modifier.testTag(SheetTags.action("tab_$id")), onClick = {
                        menu = false
                        when (id) {
                            "rename" -> renaming = cur
                            "duplicate" -> st.duplicate(cur)
                            "delete" -> st.delete(cur)
                            "left" -> st.move(cur, -1)
                            "right" -> st.move(cur, 1)
                        }
                    })
                }
            }
        }
    }
    if (renaming.isNotEmpty()) {
        val old = renaming
        NameDialog(stringResource(R.string.sheet_rename), old, { renaming = "" }) { n ->
            st.message = if (st.rename(old, n)) "" else st.wb.nameProblem(n, old).orEmpty()
            renaming = ""
        }
    }
}
