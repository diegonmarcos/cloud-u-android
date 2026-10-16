package com.diegonmarcos.cloudcalc.sheet

import org.json.JSONArray
import org.json.JSONObject

data class Cell(val raw: String, val fmt: Format = Format.GENERAL)

/** One sheet's cells (sparse) and its column widths / row heights where they differ from the default. */
class Sheet(var name: String) {
    val cells = HashMap<Long, Cell>()
    val colWidths = HashMap<Int, Int>()
    val rowHeights = HashMap<Int, Int>()

    fun copyOf(newName: String = name): Sheet = Sheet(newName).also {
        it.cells.putAll(cells); it.colWidths.putAll(colWidths); it.rowHeights.putAll(rowHeights)
    }

    companion object {
        fun key(col: Int, row: Int): Long = (row.toLong() shl 14) or col.toLong()
        fun colOf(key: Long): Int = (key and 0x3FFF).toInt()
        fun rowOf(key: Long): Int = (key shr 14).toInt()
    }
}

/** A copied block: the cells with their formulas, and where they came from (relative references move on paste). */
class Clip(val rows: List<List<Cell?>>, val originCol: Int, val originRow: Int)

/** The workbook: sheets, the evaluated values, the dependency recalculation, structure edits, undo and redo. */
class Workbook(private val clock: () -> Double = { nowSerial() }) : Source {
    private data class CKey(val sheet: String, val k: Long)
    private class Parsed(val node: Node?, val refs: List<Pair<String, Area>>, val volatile: Boolean)
    private class Snap(val sheets: List<Sheet>, val active: Int)

    val sheets = ArrayList<Sheet>()
    var active = 0
    /** Bumped by every change, so a screen knows to redraw. */
    var version = 0
        private set

    private val values = HashMap<CKey, Value>()
    private val parsed = HashMap<CKey, Parsed?>()
    private val visiting = HashSet<CKey>()
    private val undoStack = ArrayList<Snap>()
    private val redoStack = ArrayList<Snap>()
    private var depth = 0
    private val changed = HashSet<CKey>()
    private var full = false

    init { sheets.add(Sheet("Sheet1")) }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // ── reading ─────────────────────────────────────────────────────────────────────────────

    fun find(name: String): Sheet? = sheets.firstOrNull { it.name == name }
    fun sheetNames(): List<String> = sheets.map { it.name }
    val current: Sheet get() = sheets[active.coerceIn(0, sheets.size - 1)]

    override fun hasSheet(sheet: String) = find(sheet) != null
    override fun now(): Double = clock()
    override fun cell(sheet: String, col: Int, row: Int): Value = valueOf(CKey(sheet, Sheet.key(col, row)))

    fun raw(sheet: String, col: Int, row: Int): String = find(sheet)?.cells?.get(Sheet.key(col, row))?.raw.orEmpty()
    fun format(sheet: String, col: Int, row: Int): Format = find(sheet)?.cells?.get(Sheet.key(col, row))?.fmt ?: Format.GENERAL
    fun value(sheet: String, col: Int, row: Int): Value = valueOf(CKey(sheet, Sheet.key(col, row)))
    fun display(sheet: String, col: Int, row: Int): String = Fmt.display(value(sheet, col, row), format(sheet, col, row))

    /** The last used column and row (0-based) of [sheet]; (-1, -1) when it is empty. */
    fun extent(sheet: String): Pair<Int, Int> {
        var c = -1; var r = -1
        find(sheet)?.cells?.forEach { (k, cell) ->
            if (cell.raw.isNotEmpty()) { c = maxOf(c, Sheet.colOf(k)); r = maxOf(r, Sheet.rowOf(k)) }
        }
        return c to r
    }

    private fun valueOf(key: CKey): Value {
        values[key]?.let { return it }
        val sh = find(key.sheet) ?: return Value.Err(Value.REF)
        val cell = sh.cells[key.k] ?: return Value.Blank
        val raw = cell.raw
        if (raw.isEmpty()) return Value.Blank
        if (!raw.startsWith("=") || raw.length == 1) {
            return Fmt.entry(raw).first.also { values[key] = it }
        }
        if (!visiting.add(key)) return Value.Err(Value.CIRC)
        try {
            val p = parsedOf(key, raw)
            val v = if (p.node == null) Value.Err(Value.VALUE) else Evaluator(this, key.sheet).eval(p.node)
            values[key] = v
            return v
        } finally {
            visiting.remove(key)
        }
    }

    private fun parsedOf(key: CKey, raw: String): Parsed = parsed.getOrPut(key) {
        val node = Formula.parseOrNull(raw)
        Parsed(node, node?.let { n -> Formula.refs(n).map { (s, a) -> (s ?: key.sheet) to a } }.orEmpty(), node?.let { Formula.volatile(it) } ?: false)
    }!!

    // ── the dependency graph ────────────────────────────────────────────────────────────────

    private fun recalcAll() {
        values.clear(); parsed.clear()
    }

    /** Drop the values that depend (transitively) on [roots]; they are re-evaluated on next read. */
    private fun recalc(roots: Set<CKey>) {
        val dirty = HashSet<CKey>(roots)
        var frontier: Set<CKey> = roots
        val formulas = ArrayList<Pair<CKey, Parsed>>()
        for (sh in sheets) for ((k, c) in sh.cells) {
            if (c.raw.length > 1 && c.raw.startsWith("=")) {
                val key = CKey(sh.name, k)
                formulas.add(key to parsedOf(key, c.raw))
            }
        }
        for ((key, p) in formulas) if (p.volatile) dirty.add(key)
        while (frontier.isNotEmpty()) {
            val next = HashSet<CKey>()
            for ((key, p) in formulas) {
                if (key in dirty) continue
                if (p.refs.any { (s, a) -> frontier.any { f -> f.sheet == s && a.contains(Sheet.colOf(f.k), Sheet.rowOf(f.k)) } }) next.add(key)
            }
            dirty.addAll(next)
            frontier = next
        }
        for (k in dirty) { values.remove(k) }
    }

    // ── editing ─────────────────────────────────────────────────────────────────────────────

    private fun snapshot() = Snap(sheets.map { it.copyOf() }, active)

    private fun restore(s: Snap) {
        sheets.clear(); sheets.addAll(s.sheets.map { it.copyOf() })
        active = s.active.coerceIn(0, sheets.size - 1)
        recalcAll()
        version++
    }

    private fun <T> mutate(block: () -> T): T {
        val outer = depth == 0
        if (outer) {
            undoStack.add(snapshot())
            if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
            redoStack.clear(); changed.clear(); full = false
        }
        depth++
        try {
            return block()
        } finally {
            depth--
            if (outer) {
                if (full) recalcAll() else recalc(HashSet(changed))
                version++
            }
        }
    }

    /** Several edits as ONE undo step. */
    fun <T> batch(block: () -> T): T = mutate(block)

    private fun touch(sheet: String, k: Long) {
        val key = CKey(sheet, k)
        changed.add(key); parsed.remove(key); values.remove(key)
    }

    private fun put(sh: Sheet, col: Int, row: Int, cell: Cell?) {
        val k = Sheet.key(col, row)
        if (cell == null || (cell.raw.isEmpty() && cell.fmt == Format.GENERAL)) sh.cells.remove(k) else sh.cells[k] = cell
        touch(sh.name, k)
    }

    /** Enter [input] as a user would type it: "=…" a formula, a number / percent / ISO date / TRUE / FALSE as such, anything else text. */
    fun set(sheet: String, col: Int, row: Int, input: String) {
        val sh = find(sheet) ?: return
        if (col !in 0 until A1.MAX_COLS || row !in 0 until A1.MAX_ROWS) return
        mutate {
            val old = sh.cells[Sheet.key(col, row)]
            var fmt = old?.fmt ?: Format.GENERAL
            if (!input.startsWith("=") && fmt == Format.GENERAL) Fmt.entry(input).second?.let { fmt = it }
            put(sh, col, row, Cell(input, fmt))
        }
    }

    fun clear(sheet: String, area: Area) {
        val sh = find(sheet) ?: return
        mutate {
            for (k in sh.cells.keys.toList()) if (area.contains(Sheet.colOf(k), Sheet.rowOf(k))) {
                val c = sh.cells[k]!!
                put(sh, Sheet.colOf(k), Sheet.rowOf(k), if (c.fmt == Format.GENERAL) null else Cell("", c.fmt))
            }
        }
    }

    fun setFormat(sheet: String, area: Area, fmt: Format) {
        val sh = find(sheet) ?: return
        val (ec, er) = extent(sheet)
        val a = Area(area.c1, area.r1, minOf(area.c2, maxOf(ec, area.c1 + 255)), minOf(area.r2, maxOf(er, area.r1 + 999)))
        mutate {
            for (r in a.r1..a.r2) for (c in a.c1..a.c2) {
                val old = sh.cells[Sheet.key(c, r)]
                if (old == null && fmt == Format.GENERAL) continue
                put(sh, c, r, Cell(old?.raw.orEmpty(), fmt))
            }
        }
    }

    fun setColWidth(sheet: String, col: Int, px: Int) { find(sheet)?.let { it.colWidths[col] = px.coerceIn(24, 1200); version++ } }
    fun setRowHeight(sheet: String, row: Int, px: Int) { find(sheet)?.let { it.rowHeights[row] = px.coerceIn(16, 600); version++ } }

    // ── structure ───────────────────────────────────────────────────────────────────────────

    fun insertRows(sheet: String, at: Int, count: Int = 1) = structure(sheet, false, at, count.coerceAtLeast(1))
    fun deleteRows(sheet: String, at: Int, count: Int = 1) = structure(sheet, false, at, -count.coerceAtLeast(1))
    fun insertCols(sheet: String, at: Int, count: Int = 1) = structure(sheet, true, at, count.coerceAtLeast(1))
    fun deleteCols(sheet: String, at: Int, count: Int = 1) = structure(sheet, true, at, -count.coerceAtLeast(1))

    private fun structure(sheetName: String, cols: Boolean, at: Int, count: Int) {
        val sh = find(sheetName) ?: return
        if (at < 0) return
        mutate {
            full = true
            fun shiftIndex(i: Int): Int? =
                if (count > 0) (if (i >= at) i + count else i)
                else if (i < at) i else if (i >= at - count) i + count else null
            val limit = if (cols) A1.MAX_COLS else A1.MAX_ROWS
            val moved = HashMap<Long, Cell>()
            for ((k, c) in sh.cells) {
                val col = Sheet.colOf(k); val row = Sheet.rowOf(k)
                val ni = shiftIndex(if (cols) col else row) ?: continue
                if (ni >= limit) continue
                moved[if (cols) Sheet.key(ni, row) else Sheet.key(col, ni)] = c
            }
            sh.cells.clear(); sh.cells.putAll(moved)
            val sizes = if (cols) sh.colWidths else sh.rowHeights
            val movedSizes = HashMap<Int, Int>()
            for ((i, v) in sizes) shiftIndex(i)?.let { movedSizes[it] = v }
            sizes.clear(); sizes.putAll(movedSizes)
            for (s in sheets) for ((k, c) in s.cells.entries.toList()) {
                if (c.raw.startsWith("=")) {
                    val t = Formula.adjust(c.raw, s.name, sheetName, cols, at, count)
                    if (t != c.raw) s.cells[k] = c.copy(raw = t)
                }
            }
        }
    }

    // ── sheets ──────────────────────────────────────────────────────────────────────────────

    fun nameProblem(name: String, except: String? = null): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "A sheet needs a name"
            n.length > 31 -> "At most 31 characters"
            n.any { it in "[]:*?/\\" } -> "A sheet name cannot contain [ ] : * ? / \\"
            sheets.any { it.name.equals(n, true) && !it.name.equals(except, true) } -> "There is already a sheet called $n"
            else -> null
        }
    }

    private fun freshName(base: String): String {
        if (nameProblem(base) == null) return base
        var i = 2
        while (nameProblem("$base ($i)") != null) i++
        return "$base ($i)"
    }

    fun addSheet(name: String? = null): String {
        var added = ""
        mutate {
            full = true
            added = if (name != null && nameProblem(name) == null) name.trim() else freshName("Sheet" + (sheets.size + 1))
            sheets.add(Sheet(added))
            active = sheets.size - 1
        }
        return added
    }

    fun renameSheet(old: String, new: String): Boolean {
        val sh = find(old) ?: return false
        val n = new.trim()
        if (nameProblem(n, old) != null) return false
        if (n == old) return true
        mutate {
            full = true
            sh.name = n
            for (s in sheets) for ((k, c) in s.cells.entries.toList()) {
                if (c.raw.startsWith("=")) {
                    val t = Formula.renameSheet(c.raw, old, n)
                    if (t != c.raw) s.cells[k] = c.copy(raw = t)
                }
            }
        }
        return true
    }

    fun duplicateSheet(name: String): String? {
        val sh = find(name) ?: return null
        var made = ""
        mutate {
            full = true
            made = freshName("$name (copy)")
            sheets.add(sheets.indexOf(sh) + 1, sh.copyOf(made))
            active = sheets.indexOfFirst { it.name == made }
        }
        return made
    }

    fun deleteSheet(name: String): Boolean {
        val sh = find(name) ?: return false
        if (sheets.size <= 1) return false
        mutate {
            full = true
            val i = sheets.indexOf(sh)
            sheets.remove(sh)
            for (s in sheets) for ((k, c) in s.cells.entries.toList()) {
                if (c.raw.startsWith("=")) {
                    val t = Formula.dropSheet(c.raw, name)
                    if (t != c.raw) s.cells[k] = c.copy(raw = t)
                }
            }
            active = minOf(i, sheets.size - 1)
        }
        return true
    }

    fun moveSheet(name: String, toIndex: Int) {
        val sh = find(name) ?: return
        mutate {
            full = true
            val cur = current
            sheets.remove(sh)
            sheets.add(toIndex.coerceIn(0, sheets.size), sh)
            active = sheets.indexOf(cur)
        }
    }

    fun select(index: Int) { active = index.coerceIn(0, sheets.size - 1); version++ }

    // ── clipboard ───────────────────────────────────────────────────────────────────────────

    fun copy(sheet: String, area: Area): Clip {
        val sh = find(sheet)
        val rows = (area.r1..area.r2).map { r -> (area.c1..area.c2).map { c -> sh?.cells?.get(Sheet.key(c, r)) } }
        return Clip(rows, area.c1, area.r1)
    }

    /** Paste [clip] with its top-left at ([col], [row]); formulas' relative references move with it. Returns the pasted area. */
    fun paste(sheet: String, col: Int, row: Int, clip: Clip): Area {
        val sh = find(sheet) ?: return Area(col, row, col, row)
        val dc = col - clip.originCol; val dr = row - clip.originRow
        mutate {
            clip.rows.forEachIndexed { ri, line ->
                line.forEachIndexed { ci, cell ->
                    val c = col + ci; val r = row + ri
                    if (c < A1.MAX_COLS && r < A1.MAX_ROWS) {
                        put(sh, c, r, cell?.let { it.copy(raw = Formula.shift(it.raw, dc, dr)) })
                    }
                }
            }
        }
        return Area(col, row, minOf(A1.MAX_COLS - 1, col + (clip.rows.firstOrNull()?.size ?: 1) - 1), minOf(A1.MAX_ROWS - 1, row + clip.rows.size - 1))
    }

    /** Text from a spreadsheet or a terminal (tab-separated, rows by newline) typed into cells from ([col], [row]). */
    fun pasteText(sheet: String, col: Int, row: Int, text: String): Area {
        val grid = Csv.parse(text, '\t')
        if (grid.isEmpty()) return Area(col, row, col, row)
        batch {
            grid.forEachIndexed { ri, line ->
                line.forEachIndexed { ci, v ->
                    if (col + ci < A1.MAX_COLS && row + ri < A1.MAX_ROWS) set(sheet, col + ci, row + ri, v)
                }
            }
        }
        return Area(col, row, minOf(A1.MAX_COLS - 1, col + grid.maxOf { it.size } - 1), minOf(A1.MAX_ROWS - 1, row + grid.size - 1))
    }

    /** [area] as tab-separated text: what is shown, or the formulas when [formulas]. */
    fun toTsv(sheet: String, area: Area, formulas: Boolean = false): String =
        Csv.write((area.r1..area.r2).map { r -> (area.c1..area.c2).map { c -> if (formulas) raw(sheet, c, r) else display(sheet, c, r) } }, '\t')

    // ── CSV ─────────────────────────────────────────────────────────────────────────────────

    /** The whole used range of [sheet] as CSV, values as shown. */
    fun exportCsv(sheet: String): String {
        val (ec, er) = extent(sheet)
        if (ec < 0) return ""
        return Csv.write((0..er).map { r -> (0..ec).map { c -> display(sheet, c, r) } })
    }

    /** Replace the content of [sheet] with the CSV [text]. */
    fun importCsv(sheet: String, text: String, delimiter: Char = ',') {
        val sh = find(sheet) ?: return
        val grid = Csv.parse(text, delimiter)
        mutate {
            for (k in sh.cells.keys.toList()) put(sh, Sheet.colOf(k), Sheet.rowOf(k), null)
            grid.forEachIndexed { r, line ->
                line.forEachIndexed { c, v ->
                    if (v.isNotEmpty() && c < A1.MAX_COLS && r < A1.MAX_ROWS) {
                        val fmt = if (v.startsWith("=")) Format.GENERAL else Fmt.entry(v).second ?: Format.GENERAL
                        put(sh, c, r, Cell(v, fmt))
                    }
                }
            }
        }
    }

    // ── undo / redo ─────────────────────────────────────────────────────────────────────────

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.add(snapshot())
        restore(undoStack.removeAt(undoStack.size - 1))
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.add(snapshot())
        restore(redoStack.removeAt(redoStack.size - 1))
        return true
    }

    // ── the file format ─────────────────────────────────────────────────────────────────────

    fun toJson(): String {
        val root = JSONObject().put("format", "cloud-calc-workbook").put("v", 1).put("active", active)
        val arr = JSONArray()
        for (sh in sheets) {
            val o = JSONObject().put("name", sh.name)
            o.put("cols", JSONObject().also { j -> sh.colWidths.forEach { (k, v) -> j.put(k.toString(), v) } })
            o.put("rows", JSONObject().also { j -> sh.rowHeights.forEach { (k, v) -> j.put(k.toString(), v) } })
            val cells = JSONArray()
            for ((k, c) in sh.cells.entries.sortedBy { it.key }) {
                val j = JSONObject().put("c", Sheet.colOf(k)).put("r", Sheet.rowOf(k)).put("v", c.raw)
                if (c.fmt != Format.GENERAL) j.put("k", c.fmt.kind.name).put("d", c.fmt.decimals).put("u", c.fmt.currency)
                cells.put(j)
            }
            arr.put(o.put("cells", cells))
        }
        return root.put("sheets", arr).toString()
    }

    /** Replace everything with the workbook in [json]; false (nothing changed) when it is not one. */
    fun load(json: String): Boolean {
        val loaded = (try { read(json) } catch (e: Exception) { null }) ?: return false
        sheets.clear(); sheets.addAll(loaded.first)
        active = loaded.second.coerceIn(0, sheets.size - 1)
        undoStack.clear(); redoStack.clear()
        recalcAll()
        version++
        return true
    }

    private fun read(json: String): Pair<List<Sheet>, Int>? {
        val root = JSONObject(json)
        if (root.optString("format") != "cloud-calc-workbook") return null
        val arr = root.getJSONArray("sheets")
        val out = ArrayList<Sheet>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sh = Sheet(o.getString("name"))
            o.optJSONObject("cols")?.let { j -> j.keys().forEach { sh.colWidths[it.toInt()] = j.getInt(it) } }
            o.optJSONObject("rows")?.let { j -> j.keys().forEach { sh.rowHeights[it.toInt()] = j.getInt(it) } }
            val cells = o.getJSONArray("cells")
            for (n in 0 until cells.length()) {
                val j = cells.getJSONObject(n)
                val fmt = if (j.has("k")) Format(Format.Kind.valueOf(j.getString("k")), j.optInt("d", 2), j.optString("u", "$")) else Format.GENERAL
                sh.cells[Sheet.key(j.getInt("c"), j.getInt("r"))] = Cell(j.getString("v"), fmt)
            }
            out.add(sh)
        }
        if (out.isEmpty()) return null
        return out to root.optInt("active", 0)
    }

    companion object {
        const val MAX_UNDO = 100

        fun nowSerial(): Double {
            val t = java.time.LocalDateTime.now()
            return Fmt.serial(t.toLocalDate()) + t.toLocalTime().toSecondOfDay() / 86400.0
        }

        fun fromJson(json: String): Workbook? = Workbook().takeIf { it.load(json) }
    }
}
