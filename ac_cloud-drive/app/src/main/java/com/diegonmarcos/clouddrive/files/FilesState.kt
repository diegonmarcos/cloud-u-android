package com.diegonmarcos.clouddrive.files

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * #579 the Files tab's state, PURE Kotlin — no Android, no IO — so the JVM suite
 * (FilesStateTest) proves every reducer: two panes with their own tabs, sort,
 * filter, view mode, selection and tree expansion; back that walks an archive, a
 * folder, a tab; the tab cap; pane activation. Serializable so the whole state
 * survives process death through rememberSaveable (FilesUiState.encode / decode).
 */

/** Where a pane looks: a local folder, or a folder INSIDE a zip browsed as a folder. */
@Serializable
sealed class Location {
    /** The last path segment the crumbs and the tab chip print. */
    abstract val name: String
    /** A stable key for selection sets, listings and tree nodes. */
    abstract val key: String

    @Serializable
    data class Local(val path: String) : Location() {
        override val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
        override val key: String get() = "L:$path"
        fun parent(): Local? {
            val t = path.trimEnd('/')
            val i = t.lastIndexOf('/')
            return if (i <= 0) null else Local(t.substring(0, i))
        }
        fun child(childName: String): Local = Local(path.trimEnd('/') + "/" + childName)
    }

    /** [inner] is "" at the archive root, otherwise "a/b" with no leading or trailing slash. */
    @Serializable
    data class Archive(val zipPath: String, val inner: String = "") : Location() {
        override val name: String get() = if (inner.isEmpty()) zipPath.substringAfterLast('/') else inner.substringAfterLast('/')
        override val key: String get() = "A:$zipPath!/$inner"
        val isRoot: Boolean get() = inner.isEmpty()
        fun child(childName: String): Archive = Archive(zipPath, if (inner.isEmpty()) childName else "$inner/$childName")
        /** Up inside the archive, or out to the folder holding the zip. */
        fun parent(): Location = when {
            inner.isEmpty() -> Local(zipPath).parent() ?: Local(zipPath)
            !inner.contains('/') -> Archive(zipPath, "")
            else -> Archive(zipPath, inner.substringBeforeLast('/'))
        }
        /** The entry-name prefix inside the zip: "" at the root, "a/b/" below. */
        val prefix: String get() = if (inner.isEmpty()) "" else "$inner/"
    }

    val isArchive: Boolean get() = this is Archive

    fun parentOrNull(): Location? = when (this) { is Local -> parent(); is Archive -> parent() }

    fun child(childName: String): Location = when (this) { is Local -> child(childName); is Archive -> child(childName) }

    /** The crumbs from the volume root down: the local segments, then the archive's inner segments. */
    fun crumbs(): List<Location> = when (this) {
        is Local -> generateSequence(this) { it.parent() }.toList().asReversed()
        is Archive -> {
            // The zip file's own crumb becomes the archive root, then one crumb per inner segment.
            val outside = Local(zipPath).crumbs().dropLast(1)
            val inners = mutableListOf<Location>(Archive(zipPath, ""))
            var acc = ""
            inner.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                acc = if (acc.isEmpty()) seg else "$acc/$seg"
                inners += Archive(zipPath, acc)
            }
            outside + inners
        }
    }
}

enum class ViewMode { LIST, TREE }
enum class PaneId { A, B }

/** One open location of a pane, with the back stack of locations it has visited. */
@Serializable
data class PaneTab(val location: Location, val history: List<Location> = emptyList())

@Serializable
data class PaneState(
    val tabs: List<PaneTab>,
    val activeTab: Int = 0,
    val sort: String = "name",
    val descending: Boolean = false,
    val showHidden: Boolean = false,
    val filterId: String = "all",
    val viewMode: ViewMode = ViewMode.LIST,
    /** Entry keys ([Location.key]) selected in the current location. */
    val selection: Set<String> = emptySet(),
    /** Tree nodes (location keys) currently expanded. */
    val expanded: Set<String> = emptySet(),
) {
    val current: PaneTab get() = tabs[activeTab.coerceIn(0, tabs.lastIndex)]
    val location: Location get() = current.location
    val selecting: Boolean get() = selection.isNotEmpty()
}

@Serializable
data class FilesUiState(
    val a: PaneState,
    val b: PaneState,
    val active: PaneId = PaneId.A,
    val dualPane: Boolean = true,
) {
    fun pane(id: PaneId): PaneState = if (id == PaneId.A) a else b
    val activePane: PaneState get() = pane(active)
    val otherId: PaneId get() = if (active == PaneId.A) PaneId.B else PaneId.A
    val otherPane: PaneState get() = pane(otherId)
    fun with(id: PaneId, pane: PaneState): FilesUiState = if (id == PaneId.A) copy(a = pane) else copy(b = pane)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(text: String): FilesUiState? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
        fun initial(root: Location, second: Location, sort: String, showHidden: Boolean, dual: Boolean): FilesUiState = FilesUiState(
            a = PaneState(listOf(PaneTab(root)), sort = sort, showHidden = showHidden),
            b = PaneState(listOf(PaneTab(second)), sort = sort, showHidden = showHidden),
            dualPane = dual,
        )
    }
}

/** Every transition, as a pure function. The controller applies these and reloads what changed. */
object FilesReducer {

    /** Navigate the pane's current tab to [target], remembering where it was. */
    fun open(s: FilesUiState, id: PaneId, target: Location): FilesUiState {
        val p = s.pane(id)
        val tab = p.current
        val next = tab.copy(location = target, history = (tab.history + tab.location).takeLast(HISTORY_CAP))
        return s.with(id, p.copy(tabs = p.tabs.toMutableList().also { it[p.activeTab] = next }, selection = emptySet()))
    }

    fun up(s: FilesUiState, id: PaneId): FilesUiState? {
        val parent = s.pane(id).location.parentOrNull() ?: return null
        return open(s, id, parent)
    }

    /**
     * Back inside a pane: a selection is cleared first; then the tab's history is walked;
     * then a non-last tab is closed; null when the pane has nothing left to undo (the
     * shell then leaves the app).
     */
    fun back(s: FilesUiState, id: PaneId): FilesUiState? {
        val p = s.pane(id)
        if (p.selecting) return s.with(id, p.copy(selection = emptySet()))
        val tab = p.current
        if (tab.history.isNotEmpty()) {
            val prev = tab.history.last()
            val next = tab.copy(location = prev, history = tab.history.dropLast(1))
            return s.with(id, p.copy(tabs = p.tabs.toMutableList().also { it[p.activeTab] = next }))
        }
        if (p.tabs.size > 1) return closeTab(s, id, p.activeTab)
        return null
    }

    /** A new tab at [target]; at the cap the current tab is replaced instead. */
    fun openTab(s: FilesUiState, id: PaneId, target: Location, max: Int): FilesUiState {
        val p = s.pane(id)
        if (p.tabs.size >= max) return open(s, id, target)
        val tabs = p.tabs + PaneTab(target)
        return s.with(id, p.copy(tabs = tabs, activeTab = tabs.lastIndex, selection = emptySet()))
    }

    fun closeTab(s: FilesUiState, id: PaneId, index: Int): FilesUiState {
        val p = s.pane(id)
        if (p.tabs.size <= 1 || index !in p.tabs.indices) return s
        val tabs = p.tabs.toMutableList().also { it.removeAt(index) }
        val active = when {
            p.activeTab > index -> p.activeTab - 1
            p.activeTab == index -> (index - 1).coerceAtLeast(0)
            else -> p.activeTab
        }
        return s.with(id, p.copy(tabs = tabs, activeTab = active.coerceIn(0, tabs.lastIndex), selection = emptySet()))
    }

    fun selectTab(s: FilesUiState, id: PaneId, index: Int): FilesUiState {
        val p = s.pane(id)
        if (index !in p.tabs.indices) return s
        return s.with(id, p.copy(activeTab = index, selection = emptySet()))
    }

    fun activate(s: FilesUiState, id: PaneId): FilesUiState = s.copy(active = id)
    fun toggleDual(s: FilesUiState): FilesUiState = s.copy(dualPane = !s.dualPane, active = if (s.dualPane) PaneId.A else s.active)
    fun setDual(s: FilesUiState, dual: Boolean): FilesUiState = if (dual == s.dualPane) s else toggleDual(s)

    fun toggleSelect(s: FilesUiState, id: PaneId, key: String): FilesUiState {
        val p = s.pane(id)
        return s.with(id, p.copy(selection = if (key in p.selection) p.selection - key else p.selection + key))
    }
    fun select(s: FilesUiState, id: PaneId, keys: Collection<String>): FilesUiState = s.with(id, s.pane(id).copy(selection = s.pane(id).selection + keys))
    fun selectAll(s: FilesUiState, id: PaneId, keys: Collection<String>): FilesUiState = s.with(id, s.pane(id).copy(selection = keys.toSet()))
    fun invert(s: FilesUiState, id: PaneId, keys: Collection<String>): FilesUiState = s.with(id, s.pane(id).copy(selection = keys.toSet() - s.pane(id).selection))
    fun clearSelection(s: FilesUiState, id: PaneId): FilesUiState = s.with(id, s.pane(id).copy(selection = emptySet()))

    fun setSort(s: FilesUiState, id: PaneId, sort: String, descending: Boolean): FilesUiState = s.with(id, s.pane(id).copy(sort = sort, descending = descending))
    fun setFilter(s: FilesUiState, id: PaneId, filterId: String): FilesUiState = s.with(id, s.pane(id).copy(filterId = filterId))
    fun toggleHidden(s: FilesUiState, id: PaneId): FilesUiState = s.with(id, s.pane(id).copy(showHidden = !s.pane(id).showHidden))
    fun setViewMode(s: FilesUiState, id: PaneId, mode: ViewMode): FilesUiState = s.with(id, s.pane(id).copy(viewMode = mode, selection = emptySet()))
    fun toggleExpanded(s: FilesUiState, id: PaneId, key: String): FilesUiState {
        val p = s.pane(id)
        return s.with(id, p.copy(expanded = if (key in p.expanded) p.expanded - key else p.expanded + key))
    }

    /** Both panes' current locations, for a reload after a change under [changed]. */
    fun panesShowing(s: FilesUiState, changed: Location): List<PaneId> = PaneId.values().filter { s.pane(it).location == changed }

    const val HISTORY_CAP = 64
}
