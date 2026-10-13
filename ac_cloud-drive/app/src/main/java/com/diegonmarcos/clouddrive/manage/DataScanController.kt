package com.diegonmarcos.clouddrive.manage

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import com.diegonmarcos.clouddrive.SharedStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manage ▸ Data's scan, off the main thread: the two roots are shared storage
 * (/storage/emulated/0, all-files access) and the shared store (storage.shared_root), which the
 * scanner lists under itself. The per-directory cache lives in this app's cache dir, so a second
 * visit (and the next launch) re-stats only the folders that changed. One process-wide instance,
 * so leaving the tab neither cancels nor repeats a scan.
 */
class DataScanController private constructor(private val ctx: Context) {
    data class State(val items: List<DataScanner.Item> = emptyList(), val progress: DataScanner.Progress? = null, val running: Boolean = false, val complete: Boolean = false, val scannedAt: Long = 0L)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state
    private var job: Job? = null
    private val cacheFile get() = File(ctx.cacheDir, "manage-data-scan.txt")

    fun roots(): List<File> = listOfNotNull(Environment.getExternalStorageDirectory(), runCatching { SharedStore.root() }.getOrNull())

    fun scan(scope: CoroutineScope, full: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(running = true, progress = null)
            val cache = if (full) emptyMap() else runCatching { DataScanner.decodeCache(cacheFile.readText()) }.getOrDefault(emptyMap())
            val mime = MimeTypeMap.getSingleton()
            var last = 0L
            val res = DataScanner().scan(
                roots(), cache,
                cancelled = { !isActive },
                progress = { p -> val now = System.currentTimeMillis(); if (now - last > PROGRESS_MS) { last = now; _state.value = _state.value.copy(progress = p) } },
                mimeOf = { name -> mime.getMimeTypeFromExtension(FileClassifier.extensionOf(name)) },
            )
            if (res.complete) runCatching { cacheFile.writeText(DataScanner.encodeCache(res.cache)) }
            _state.value = State(items = if (res.complete || _state.value.items.isEmpty()) res.items else _state.value.items, running = false, complete = res.complete, scannedAt = System.currentTimeMillis())
        }
    }

    fun cancel() { job?.cancel() }

    /** A row the user deleted leaves the list now, without waiting for the next scan. */
    fun forget(path: String) { _state.value = _state.value.copy(items = _state.value.items.filterNot { it.path == path || it.path.startsWith("$path/") }) }

    companion object {
        private const val PROGRESS_MS = 150L
        @Volatile private var instance: DataScanController? = null
        fun get(ctx: Context): DataScanController = instance ?: synchronized(this) { instance ?: DataScanController(ctx.applicationContext).also { instance = it } }
    }
}
