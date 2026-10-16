package com.diegonmarcos.cloudcalc.sheetui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudcalc.sheet.A1
import com.diegonmarcos.cloudcalc.sheet.Area
import com.diegonmarcos.cloudcalc.sheet.Clip
import com.diegonmarcos.cloudcalc.sheet.Format
import com.diegonmarcos.cloudcalc.sheet.Workbook
import java.io.File
import java.util.concurrent.CountDownLatch

/** Workbooks on disk: app-private storage, one JSON file per workbook. */
class SheetFiles(private val dir: File, private val prefs: android.content.SharedPreferences?) {
    init { dir.mkdirs() }

    fun names(): List<String> = (dir.listFiles { f -> f.name.endsWith(EXT) }?.map { it.name.removeSuffix(EXT) } ?: emptyList()).sorted()
    fun read(name: String): String? = File(dir, safe(name) + EXT).takeIf { it.isFile }?.readText()
    fun write(name: String, json: String) { File(dir, safe(name) + EXT).writeText(json) }
    fun delete(name: String) { File(dir, safe(name) + EXT).delete() }
    var lastName: String
        get() = prefs?.getString(LAST, null) ?: DEFAULT
        set(v) { prefs?.edit()?.putString(LAST, v)?.apply() }

    companion object {
        const val EXT = ".cloudsheet.json"
        const val DEFAULT = "Workbook"
        private const val LAST = "sheet_last"
        fun safe(n: String): String = n.trim().replace(Regex("[^A-Za-z0-9 _.()-]"), "_").ifBlank { DEFAULT }
    }
}

/** What the Sheet page and the AI tools share: the workbook, the selection, the clipboard and the files. */
class SheetState(val files: SheetFiles) {
    var wb = Workbook()
        private set
    /** Bumped on every change: composables read it so they redraw. */
    var rev by mutableIntStateOf(0)
        private set
    var name by mutableStateOf(files.lastName)
        private set
    var anchorC by mutableIntStateOf(0)
    var anchorR by mutableIntStateOf(0)
    var endC by mutableIntStateOf(0)
    var endR by mutableIntStateOf(0)
    /** While on, a tap extends the range instead of moving the cell. */
    var extend by mutableStateOf(false)
    var message by mutableStateOf("")
    private var clip: Clip? = null
    private var clipTsv: String = ""

    init {
        files.read(name)?.let { wb.load(it) }
    }

    val sheet: String get() = wb.current.name
    val area: Area get() = Area(minOf(anchorC, endC), minOf(anchorR, endR), maxOf(anchorC, endC), maxOf(anchorR, endR))

    fun bump() { rev++ }

    fun select(c: Int, r: Int) {
        if (extend) { endC = c; endR = r } else { anchorC = c; anchorR = r; endC = c; endR = r }
        bump()
    }

    fun selectArea(a: Area) { anchorC = a.c1; anchorR = a.r1; endC = a.c2; endR = a.r2; bump() }
    fun selectRow(r: Int) = if (extend) { endR = r; endC = A1.MAX_COLS - 1; bump() } else selectArea(Area(0, r, A1.MAX_COLS - 1, r))
    fun selectCol(c: Int) = if (extend) { endC = c; endR = A1.MAX_ROWS - 1; bump() } else selectArea(Area(c, 0, c, A1.MAX_ROWS - 1))

    /** The edit bar's content for the active cell: the raw text, formulas included. */
    fun editText(): String = wb.raw(sheet, anchorC, anchorR)

    /** Enter [text] in the active cell and step down, as Enter does in a spreadsheet. */
    fun commit(text: String) {
        wb.set(sheet, anchorC, anchorR, text)
        anchorR = minOf(anchorR + 1, A1.MAX_ROWS - 1); endC = anchorC; endR = anchorR
        bump()
    }

    fun undo() { if (wb.undo()) bump() }
    fun redo() { if (wb.redo()) bump() }
    fun clearSelection() { wb.clear(sheet, area); bump() }
    fun format(f: Format) { wb.setFormat(sheet, area, f); bump() }

    fun insertRow() { wb.insertRows(sheet, area.r1, area.rows); bump() }
    fun deleteRow() { wb.deleteRows(sheet, area.r1, area.rows); bump() }
    fun insertCol() { wb.insertCols(sheet, area.c1, area.cols); bump() }
    fun deleteCol() { wb.deleteCols(sheet, area.c1, area.cols); bump() }

    fun resizeCol(c: Int, dp: Int) { wb.setColWidth(sheet, c, dp); bump() }
    fun resizeRow(r: Int, dp: Int) { wb.setRowHeight(sheet, r, dp); bump() }

    // sheets
    fun switchTo(i: Int) { wb.select(i); anchorC = 0; anchorR = 0; endC = 0; endR = 0; bump() }
    fun addSheet() { wb.addSheet(); bump() }
    fun rename(old: String, new: String): Boolean = wb.renameSheet(old, new).also { bump() }
    fun duplicate(n: String) { wb.duplicateSheet(n); bump() }
    fun delete(n: String): Boolean = wb.deleteSheet(n).also { bump() }
    fun move(n: String, delta: Int) { val i = wb.sheetNames().indexOf(n); if (i >= 0) { wb.moveSheet(n, i + delta); bump() } }

    // clipboard
    private fun sys(ctx: Context) = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun copy(ctx: Context) {
        val a = area.let { Area(it.c1, it.r1, minOf(it.c2, usedCols()), minOf(it.r2, usedRows())) }
        clip = wb.copy(sheet, a)
        clipTsv = wb.toTsv(sheet, a)
        runCatching { sys(ctx).setPrimaryClip(ClipData.newPlainText("sheet", clipTsv)) }
    }

    fun cut(ctx: Context) { copy(ctx); clearSelection() }

    fun paste(ctx: Context) {
        val text = runCatching { sys(ctx).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
        val c = clip
        val a = if (c != null && (text == null || text == clipTsv)) wb.paste(sheet, area.c1, area.r1, c)
        else if (!text.isNullOrEmpty()) wb.pasteText(sheet, area.c1, area.r1, text) else return
        selectArea(a)
    }

    private fun usedCols() = maxOf(wb.extent(sheet).first, area.c1)
    private fun usedRows() = maxOf(wb.extent(sheet).second, area.r1)

    // files
    fun save() { files.write(name, wb.toJson()); files.lastName = name }
    fun saveAs(n: String) { name = SheetFiles.safe(n); save() }

    fun open(n: String): Boolean {
        val json = files.read(n) ?: return false
        val fresh = Workbook()
        if (!fresh.load(json)) return false
        save()
        wb = fresh; name = n; files.lastName = n
        anchorC = 0; anchorR = 0; endC = 0; endR = 0
        bump(); return true
    }

    fun newWorkbook(n: String) {
        save()
        wb = Workbook(); name = SheetFiles.safe(n); files.lastName = name
        anchorC = 0; anchorR = 0; endC = 0; endR = 0
        save(); bump()
    }

    fun exportCsv(): String = wb.exportCsv(sheet)
    fun importCsv(text: String) { wb.importCsv(sheet, text); bump() }
    fun exportJson(): String = wb.toJson()
    fun importJson(text: String): Boolean = wb.load(text).also { if (it) bump() }

    /** Run [block] on the main thread and wait for it (the AI tools call from a worker thread). */
    fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var out: Result<T>? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { out = runCatching(block); latch.countDown() }
        latch.await()
        return out!!.getOrThrow()
    }
}

/** The one SheetState of the process, so the Sheet page and the AI tools see the same workbook. */
object SheetHub {
    @Volatile private var state: SheetState? = null

    fun get(ctx: Context): SheetState = state ?: synchronized(this) {
        state ?: run {
            val app = ctx.applicationContext
            SheetState(SheetFiles(File(app.filesDir, "sheets"), app.getSharedPreferences("cloud_calc", Context.MODE_PRIVATE))).also { state = it }
        }
    }

    /** For tests: forget the process-wide state. */
    fun reset() { state = null }
}
