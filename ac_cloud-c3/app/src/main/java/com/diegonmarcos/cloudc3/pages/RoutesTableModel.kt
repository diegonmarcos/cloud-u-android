package com.diegonmarcos.cloudc3.pages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Containers > Table page's MODEL, pure Kotlin so the JVM suite drives sorting, filtering and the persisted
 * state without a screen. The rows are data/infra_slice.json::routes (derived by data/gen_infra_slice.py from the
 * edge auth tiers); nothing here names a container, a host or a VM.
 */
object RoutesTableModel {

    /** The seven columns, in the owner's order. [key] is the row field and the column's test tag. */
    enum class Column(val key: String) {
        CATEGORY("category"),
        CONTAINER("container"),
        PUBLIC_URL("public_url"),
        AUTH_TYPE("auth_type"),
        PRIVATE_URL("private_url"),
        VM_NAME("vm_name"),
        VM_ADDR("vm_addr"),
    }

    /** The auth tiers a row can be in. [id] is what the generator writes; [label] is what the filter prints. */
    enum class Auth(val id: String, val label: String) {
        BEARER("bearer", "Bearer"),
        WOAUTH("woauth", "WOAuth"),
        MESH("mesh", "Mesh-only"),
        OPEN("open", "Open"),
    }

    data class Row(
        val category: String,
        val container: String,
        val publicUrl: String,
        val authType: String,
        val privateUrl: String,
        val vmName: String,
        val vmAddr: String,
    ) {
        fun text(c: Column): String = when (c) {
            Column.CATEGORY -> category
            Column.CONTAINER -> container
            Column.PUBLIC_URL -> publicUrl
            Column.AUTH_TYPE -> Auth.entries.firstOrNull { it.id == authType }?.label ?: authType
            Column.PRIVATE_URL -> privateUrl
            Column.VM_NAME -> vmName
            Column.VM_ADDR -> vmAddr
        }
    }

    /**
     * What the user chose. An EMPTY filter set means "no filter on that axis" (every row), so a fresh install shows
     * everything and unticking the last chip does not blank the table.
     */
    data class State(
        val sort: Column = Column.CONTAINER,
        val ascending: Boolean = true,
        val auth: Set<String> = emptySet(),
        val vms: Set<String> = emptySet(),
    )

    /** Rows from the slice's `routes` array; a missing or malformed array is an empty table, never a crash. */
    fun parse(routes: Any?): List<Row> = (routes as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        Row(s("category"), s("container"), s("public_url"), s("auth_type"), s("private_url"), s("vm_name"), s("vm_addr"))
            .takeIf { it.container.isNotBlank() }
    }

    /** Filter, then sort. Blank cells sort LAST in both directions, so a column's gaps never lead the table. */
    fun view(rows: List<Row>, state: State): List<Row> {
        val kept = rows.filter { r ->
            (state.auth.isEmpty() || r.authType in state.auth) && (state.vms.isEmpty() || r.vmName in state.vms)
        }
        val byText = Comparator<Row> { a, b ->
            val x = a.text(state.sort)
            val y = b.text(state.sort)
            when {
                x.isBlank() && y.isBlank() -> 0
                x.isBlank() -> 1
                y.isBlank() -> -1
                else -> naturalCompare(x, y).let { if (state.ascending) it else -it }
            }
        }
        return kept.sortedWith(byText.thenBy(String.CASE_INSENSITIVE_ORDER) { it.container })
    }

    /** Tap on a header: the sorted column flips direction, any other column starts ascending. */
    fun toggleSort(state: State, column: Column): State =
        if (state.sort == column) state.copy(ascending = !state.ascending) else state.copy(sort = column, ascending = true)

    fun toggleAuth(state: State, id: String): State = state.copy(auth = state.auth.toggled(id))
    fun toggleVm(state: State, vm: String): State = state.copy(vms = state.vms.toggled(vm))

    /** Auth types worth a chip: the owner's three always, Open only when a row is in it. In tier order. */
    fun authOptions(rows: List<Row>): List<Auth> =
        Auth.entries.filter { it != Auth.OPEN || rows.any { r -> r.authType == it.id } }

    /** The VMs the rows name, sorted, for the VM chips. */
    fun vmOptions(rows: List<Row>): List<String> =
        rows.map { it.vmName }.filter { it.isNotBlank() }.distinct().sortedWith(::naturalCompare)

    /** "12 of 104 rows", or "104 rows" when nothing is filtered out. */
    fun countLine(shown: Int, total: Int): String = if (shown == total) "$total rows" else "$shown of $total rows"

    // ── persistence: one line of text, tolerant of anything ────────────────────────────────

    fun encode(s: State): String =
        listOf(s.sort.key, if (s.ascending) "asc" else "desc", s.auth.sorted().joinToString(","), s.vms.sorted().joinToString("|"))
            .joinToString(";")

    /** Anything unreadable is the default state: stale or hand-edited preferences cannot break the page. */
    fun decode(text: String?): State {
        val p = text?.split(';') ?: return State()
        if (p.size != 4) return State()
        val col = Column.entries.firstOrNull { it.key == p[0] } ?: return State()
        val asc = when (p[1]) { "asc" -> true; "desc" -> false; else -> return State() }
        return State(col, asc, p[2].split(',').filter { it.isNotBlank() }.toSet(), p[3].split('|').filter { it.isNotBlank() }.toSet())
    }

    private fun Set<String>.toggled(x: String): Set<String> = if (x in this) this - x else this + x

    /** Case-insensitive, digit runs compared as numbers: "oci-A1-f_0" < "oci-A1-f_10", "10.0.0.6:80" < "10.0.0.6:8010". */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var ie = i; while (ie < a.length && a[ie].isDigit()) ie++
                var je = j; while (je < b.length && b[je].isDigit()) je++
                val x = a.substring(i, ie).trimStart('0')
                val y = b.substring(j, je).trimStart('0')
                if (x.length != y.length) return x.length.compareTo(y.length)
                val c = x.compareTo(y)
                if (c != 0) return c
                i = ie; j = je
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
