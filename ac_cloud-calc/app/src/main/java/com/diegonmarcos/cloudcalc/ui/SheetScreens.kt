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
import com.diegonmarcos.cloudcalc.sheet.Format
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

/** Name box (the selection; type A1:C5 to select a range) and the edit bar, which shows the RAW text: the formula. */
@Composable
private fun SheetEditBar(st: SheetState) {
    var box by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(st.anchorC, st.anchorR, st.endC, st.endR, st.rev, st.wb.active) {
        box = st.area.let { if (it.cols > 1000 || it.rows > 1000) "${it.rows}×${it.cols}" else it.label() }
        text = st.editText()
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = CalcMetrics.small), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            box, { box = it }, Modifier.width(110.dp).testTag(SheetTags.NAME_BOX), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { A1.parseArea(box)?.let { st.selectArea(it) } }),
        )
        OutlinedTextField(
            text, { text = it }, Modifier.weight(1f).testTag(SheetTags.EDIT_BAR), singleLine = true,
            placeholder = { Text(stringResource(R.string.sheet_edit_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { st.commit(text) }),
        )
        TextButton(onClick = { st.commit(text) }, modifier = Modifier.testTag(SheetTags.COMMIT)) { Text("✓") }
        TextButton(onClick = { text = st.editText() }) { Text("✗") }
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
                                GridCell(st, sheetName, c, r, w, h, sel, Modifier.offset { IntOffset(headerPx + xs[c], 0) })
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
            .clickable { st.selectCol(c) },
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
            .clickable { st.selectRow(r) },
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
private fun GridCell(st: SheetState, sheet: String, c: Int, r: Int, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, sel: Area, modifier: Modifier) {
    val v = st.wb.value(sheet, c, r)
    val text = st.wb.display(sheet, c, r)
    val inSel = sel.contains(c, r)
    val active = c == st.anchorC && r == st.anchorR
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.width(w).height(h)
            .background(if (inSel) scheme.primary.copy(alpha = 0.18f) else Color.Transparent)
            .border(if (active) 2.dp else CalcMetrics.hairline, if (active) scheme.primary else scheme.outline.copy(alpha = 0.5f))
            .combinedClickable(onClick = { st.select(c, r) }, onLongClick = { st.extend = true; st.select(c, r) })
            .padding(horizontal = CalcMetrics.small)
            .testTag(SheetTags.cell(c, r)),
        contentAlignment = if (v is Value.Num) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Clip,
            color = if (v is Value.Err) scheme.error else scheme.onSurface,
            textAlign = if (v is Value.Num) TextAlign.End else TextAlign.Start,
        )
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
