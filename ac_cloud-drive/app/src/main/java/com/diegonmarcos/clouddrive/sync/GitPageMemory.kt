package com.diegonmarcos.clouddrive.sync

import android.os.Bundle
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * #1356 WHAT THE GIT PAGE LOOKED LIKE when the owner left it: the open repo sheet and its sub-menu, the settings sheet, the
 * status window, Logs and its tab, the search, the Filters sheet and the list position. A bottom-nav switch disposes the page,
 * so its composable-local state cannot hold any of this; the page reads it back from [GitPageMemoryStore] on return.
 * (The filter selection itself lives in [GitSyncCoordinator.filterSelection], already persisted to the page's prefs.)
 */
@Serializable
data class GitPageMemory(
    val expanded: String = "",
    val panelRepo: String = "",
    val panel: String = "",
    val settingsFor: String = "",
    val statusFor: String = "",
    val showActivity: Boolean = false,
    val logsTab: String = "",
    val query: String = "",
    val showFilters: Boolean = false,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
) {
    /** The sub-menu the sheet of [repoId] reopens on: only the repo it was open for gets it back. */
    fun panelFor(repoId: String): String = if (repoId == panelRepo) panel else ""
}

/** One [GitPageMemory] per drawn Git page (Me table, Cloud table, Configs ▸ Git), keyed by [keyOf]. Outlives the page; encodes for saved state. */
class GitPageMemoryStore(initial: Map<String, GitPageMemory> = emptyMap()) {
    private val pages = initial.toMutableMap()

    fun get(key: String): GitPageMemory = synchronized(pages) { pages[key] } ?: GitPageMemory()
    fun put(key: String, memory: GitPageMemory) { synchronized(pages) { pages[key] = memory } }
    fun encode(): String = synchronized(pages) { json.encodeToString(MAP, pages.toMap()) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val MAP = MapSerializer(String.serializer(), GitPageMemory.serializer())
        fun keyOf(view: GitView, part: GitPart): String = "${view.name}/${part.name}"
        /** A store read back from [encode]'s text; unreadable or absent text is an empty store (the page opens fresh). */
        fun decode(text: String?): GitPageMemoryStore =
            GitPageMemoryStore(text?.let { runCatching { json.decodeFromString(MAP, it) }.getOrNull() }.orEmpty())
    }
}

/**
 * The activity-scoped owner of the store: it outlives every bottom-nav switch, and through [SavedStateHandle] it survives the
 * process being killed in the background. The store is written into the saved state only when the system asks for it.
 */
class GitPageViewModel(handle: SavedStateHandle) : ViewModel() {
    val store: GitPageMemoryStore = GitPageMemoryStore.decode(handle.get<Bundle>(KEY)?.getString(VALUE))

    init {
        handle.setSavedStateProvider(KEY) { Bundle().apply { putString(VALUE, store.encode()) } }
    }

    private companion object {
        const val KEY = "git_page_memory"
        const val VALUE = "v"
    }
}

/** The store the Git page reads; MainActivity provides the activity's, anything else (a test, a preview) gets a private one. */
val LocalGitPageMemory = staticCompositionLocalOf { GitPageMemoryStore() }
