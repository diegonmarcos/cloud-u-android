package com.diegonmarcos.clouddrive.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.StatFs
import android.util.LruCache
import android.webkit.MimeTypeMap
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #579 the Files tab's Android-side controller: holds the pure [FilesUiState],
 * loads listings on IO, runs the long jobs (copy, move, delete, zip, extract,
 * search, properties) as cancellable coroutines with live progress, and tells the
 * screen what to say. Compose observes the StateFlows; nothing here draws.
 */
class FilesController(
    private val ctx: Context,
    initial: FilesUiState,
    private val scope: CoroutineScope,
    val prefs: DrivePrefs,
) {
    val state = MutableStateFlow(initial)

    data class Listing(
        val entries: List<FileOps.Entry> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        /** free, total — only for a location where a storage bar makes sense. */
        val usage: Pair<Long, Long>? = null,
    )

    /** Listings by [Location.key]. A location that is not here has not been asked for yet. */
    val listings = MutableStateFlow<Map<String, Listing>>(emptyMap())

    data class TransferJob(
        val id: Long,
        val title: String,
        val detail: String = "",
        val fraction: Float? = null,
        val done: Boolean = false,
        val ok: Boolean? = null,
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
    )

    val jobs = MutableStateFlow<List<TransferJob>>(emptyList())

    /** One-line outcomes for the snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> get() = _messages

    val mimes = FileOps.MimeResolver { ext -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }
    private val jobIds = AtomicLong(0)
    private val thumbs = LruCache<String, Bitmap>(96)

    // ── state ───────────────────────────────────────────────────────────────

    fun update(f: (FilesUiState) -> FilesUiState) { state.update(f) }

    val s: FilesUiState get() = state.value

    fun open(id: PaneId, target: Location) { update { FilesReducer.open(it, id, target) }; ensureLoaded(target) }
    fun openTab(id: PaneId, target: Location) { update { FilesReducer.openTab(it, id, target, Declarations.files.tabsPerPaneMax) }; ensureLoaded(target) }
    fun up(id: PaneId): Boolean { val next = FilesReducer.up(s, id) ?: return false; state.value = next; ensureLoaded(next.pane(id).location); return true }
    /** True when the pane consumed the back press. */
    fun back(id: PaneId): Boolean { val next = FilesReducer.back(s, id) ?: return false; state.value = next; ensureLoaded(next.pane(id).location); return true }
    fun activate(id: PaneId) = update { FilesReducer.activate(it, id) }
    fun setDual(dual: Boolean) { update { FilesReducer.setDual(it, dual) }; prefs.setDualPane(dual) }

    /** Navigates the ACTIVE pane to the folder of [path] and selects the file — the engines' hand-off. */
    fun reveal(path: String) {
        val f = File(path)
        val folder = if (f.isDirectory) f else f.parentFile ?: return
        open(s.active, Location.Local(folder.absolutePath))
        if (f.isFile) update { FilesReducer.selectAll(it, it.active, listOf(Location.Local(f.absolutePath).key)) }
    }

    // ── listings ────────────────────────────────────────────────────────────

    fun ensureLoaded(loc: Location) { if (!listings.value.containsKey(loc.key)) reload(loc) }

    fun reload(loc: Location) {
        listings.update { it + (loc.key to (it[loc.key]?.copy(loading = true) ?: Listing())) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (loc) {
                        is Location.Local -> {
                            val dir = File(loc.path)
                            val entries = FileOps.list(dir, mimes, showHidden = true)
                            val usage = usageOf(dir)
                            Listing(entries, loading = false, usage = usage)
                        }
                        is Location.Archive -> Listing(ArchiveFs.list(loc, mimes), loading = false)
                    }
                }
            }
            listings.update { it + (loc.key to result.getOrElse { e -> Listing(loading = false, error = e.message ?: e.toString()) }) }
        }
    }

    /** Reload every pane whose current location is [loc] and drop stale children. */
    fun refreshShowing(loc: Location) { reload(loc); loc.parentOrNull()?.let { p -> if (listings.value.containsKey(p.key)) reload(p) } }

    fun refreshActive() { reload(s.activePane.location) }

    private fun usageOf(dir: File): Pair<Long, Long>? = runCatching { val st = StatFs(dir.absolutePath); st.availableBytes to st.totalBytes }.getOrNull()

    /** The rows a pane shows: hidden filter, the declared filter, then the pane's sort. Pure, so the JVM suite can hold it. */
    fun visible(pane: PaneState, listing: Listing): List<FileOps.Entry> = visibleEntries(pane, listing.entries, Declarations.files.filters)

    /** The entries a pane's selection refers to, in list order. */
    fun selectedEntries(id: PaneId): List<FileOps.Entry> {
        val p = s.pane(id)
        val listing = listings.value[p.location.key] ?: return emptyList()
        return visible(p, listing).filter { it.key in p.selection }
    }

    fun thumbnail(path: String): Bitmap? {
        thumbs.get(path)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= THUMB_PX) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        thumbs.put(path, decoded)
        return decoded
    }

    // ── jobs ────────────────────────────────────────────────────────────────

    private fun startJob(title: String, work: suspend (TransferJob, (String, Float?) -> Unit) -> Pair<Boolean, String>): Job {
        val job = TransferJob(jobIds.incrementAndGet(), title)
        jobs.update { it + job }
        fun set(f: (TransferJob) -> TransferJob) = jobs.update { list -> list.map { if (it.id == job.id) f(it) else it } }
        return scope.launch {
            val (ok, summary) = withContext(Dispatchers.IO) {
                runCatching { work(job) { detail, fraction -> set { it.copy(detail = detail, fraction = fraction) } } }
                    .getOrElse { e -> false to (e.message ?: e.toString()) }
            }
            val cancelled = job.cancelRequested.get()
            set { it.copy(done = true, ok = ok && !cancelled, detail = summary) }
            _messages.tryEmit(if (cancelled) ctx.getString(R.string.files_job_cancelled, title) else summary)
            kotlinx.coroutines.delay(RESULT_LINGER_MS)
            jobs.update { list -> list.filterNot { it.id == job.id } }
        }
    }

    fun cancel(jobId: Long) { jobs.value.firstOrNull { it.id == jobId }?.cancelRequested?.set(true) }

    @Suppress("UNUSED_PARAMETER")
    private fun progressOf(job: TransferJob, set: (String, Float?) -> Unit) = FileOps.Progress { done, total, current -> set(current, if (total > 0) (done.toDouble() / total).toFloat() else null) }

    /** Copy or move the active selection of [from] into [destination]; archive selections are extracted. */
    fun transferSelection(from: PaneId, destination: Location.Local, move: Boolean) {
        val entries = selectedEntries(from)
        if (entries.isEmpty()) return
        val dest = File(destination.path)
        val fromLoc = s.pane(from).location
        update { FilesReducer.clearSelection(it, from) }
        if (fromLoc is Location.Archive) {
            val zip = File(fromLoc.zipPath)
            val prefixes = entries.map { (it.location as Location.Archive).inner }.toSet()
            startJob(ctx.getString(R.string.files_job_extract, zip.name)) { job, set ->
                val n = FileOps.extract(zip, dest, progressOf(job, set), { job.cancelRequested.get() }, onlyUnder = prefixes)
                refreshShowing(destination)
                true to ctx.getString(R.string.files_job_done, ctx.getString(R.string.files_job_extract, zip.name) + " · $n")
            }
            return
        }
        val files = entries.mapNotNull { it.localFile }
        val title = ctx.getString(if (move) R.string.files_job_move else R.string.files_job_copy, files.size)
        startJob(title) { job, set ->
            val free = runCatching { StatFs(dest.absolutePath).availableBytes }.getOrDefault(0L)
            val outcome = try {
                FileOps.transfer(files, dest, move, free, progressOf(job, set)) { job.cancelRequested.get() }
            } catch (e: FileOps.NotEnoughSpace) {
                return@startJob false to ctx.getString(R.string.files_job_not_enough_space, FileOps.humanBytes(e.needed), FileOps.humanBytes(e.free))
            }
            refreshShowing(destination); if (move) refreshShowing(fromLoc)
            outcome.ok to (if (outcome.ok) ctx.getString(R.string.files_job_done, title) else ctx.getString(R.string.files_job_failed, title, outcome.failed.size))
        }
    }

    fun deleteSelection(id: PaneId) {
        val files = selectedEntries(id).mapNotNull { it.localFile }
        if (files.isEmpty()) return
        val loc = s.pane(id).location
        update { FilesReducer.clearSelection(it, id) }
        val title = ctx.getString(R.string.files_job_delete, files.size)
        startJob(title) { job, set ->
            val outcome = FileOps.delete(files, progressOf(job, set)) { job.cancelRequested.get() }
            refreshShowing(loc)
            outcome.ok to (if (outcome.ok) ctx.getString(R.string.files_job_done, title) else ctx.getString(R.string.files_job_failed, title, outcome.failed.size))
        }
    }

    fun zipSelection(id: PaneId, archiveName: String) {
        val files = selectedEntries(id).mapNotNull { it.localFile }
        val loc = s.pane(id).location as? Location.Local ?: return
        if (files.isEmpty()) return
        val safe = FileOps.sanitize(archiveName) ?: return
        val target = FileOps.uniqueIn(File(loc.path), if (safe.lowercase().endsWith(".zip")) safe else "$safe.zip")
        update { FilesReducer.clearSelection(it, id) }
        val title = ctx.getString(R.string.files_job_zip, files.size)
        startJob(title) { job, set ->
            val n = FileOps.archive(files, target, progressOf(job, set)) { job.cancelRequested.get() }
            refreshShowing(loc)
            true to ctx.getString(R.string.files_job_done, "$title · $n")
        }
    }

    fun extractHere(entry: FileOps.Entry) {
        val zip = entry.localFile ?: return
        val dest = FileOps.uniqueIn(zip.parentFile ?: return, zip.nameWithoutExtension).apply { mkdirs() }
        val title = ctx.getString(R.string.files_job_extract, zip.name)
        startJob(title) { job, set ->
            val n = FileOps.extract(zip, dest, progressOf(job, set), cancelled = { job.cancelRequested.get() })
            refreshShowing(Location.Local(zip.parentFile!!.absolutePath))
            true to ctx.getString(R.string.files_job_done, "$title · $n")
        }
    }

    /** #458/#577 PDF → txt/md/html/csv beside the file, through the ONE PdfConversion path the reader also uses. */
    fun convertPdf(entry: FileOps.Entry, target: String) {
        val f = entry.localFile ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { com.diegonmarcos.clouddrive.PdfConversion.convertPdf(ctx, f, target) }
            r.onSuccess { out -> _messages.tryEmit(ctx.getString(R.string.files_convert_saved, out.name)) }.onFailure { _messages.tryEmit(it.message ?: it.toString()) }
            refreshShowing(Location.Local(f.parentFile?.absolutePath ?: return@launch))
        }
    }

    fun createFolder(id: PaneId, name: String) = mutate(id) { FileOps.createFolder(File(it.path), name) }
    fun createFile(id: PaneId, name: String) = mutate(id) { FileOps.createFile(File(it.path), name) }
    fun rename(entry: FileOps.Entry, name: String) {
        val f = entry.localFile ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FileOps.rename(f, name) } }
            r.onFailure { _messages.tryEmit(it.message ?: it.toString()) }
            refreshShowing(Location.Local(f.parentFile?.absolutePath ?: return@launch))
        }
    }

    private fun mutate(id: PaneId, block: (Location.Local) -> File) {
        val loc = s.pane(id).location as? Location.Local ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block(loc) } }
            r.onFailure { _messages.tryEmit(it.message ?: it.toString()) }
            refreshShowing(loc)
        }
    }

    fun applyRenamePlan(plan: List<FileOps.RenameStep>, loc: Location) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FileOps.applyRenamePlan(plan) } }
            r.onSuccess { n -> _messages.tryEmit(ctx.getString(R.string.files_rename_apply, n)) }.onFailure { _messages.tryEmit(it.message ?: it.toString()) }
            update { FilesReducer.clearSelection(it, it.active) }
            refreshShowing(loc)
        }
    }

    // ── search ──────────────────────────────────────────────────────────────

    data class SearchState(val query: String = "", val running: Boolean = false, val hits: List<FileOps.Hit> = emptyList(), val scanned: Long = 0, val truncated: Boolean = false)

    val search = MutableStateFlow(SearchState())
    private var searchJob: Job? = null
    private val searchCancel = AtomicBoolean(false)

    fun startSearch(root: Location.Local, query: String, contentToo: Boolean) {
        cancelSearch()
        searchCancel.set(false)
        search.value = SearchState(query = query, running = true)
        searchJob = scope.launch(Dispatchers.IO) {
            val truncated = FileOps.search(File(root.path), query, contentToo, mimes, { searchCancel.get() },
                onScanned = { n -> search.update { it.copy(scanned = n) } },
                onHit = { hit -> search.update { it.copy(hits = it.hits + hit) } })
            search.update { it.copy(running = false, truncated = truncated) }
        }
    }

    fun cancelSearch() { searchCancel.set(true); searchJob?.cancel(); search.update { it.copy(running = false) } }
    fun clearSearch() { cancelSearch(); search.value = SearchState() }

    // ── duplicates ──────────────────────────────────────────────────────────

    /** null = not asked; empty list = running with nothing yet; the result replaces it. */
    val duplicates = MutableStateFlow<List<FileOps.DuplicateGroup>?>(null)
    var duplicatesRunning = MutableStateFlow(false)
    private val dupCancel = AtomicBoolean(false)

    fun findDuplicates(root: Location.Local) {
        dupCancel.set(false)
        duplicates.value = emptyList(); duplicatesRunning.value = true
        scope.launch(Dispatchers.IO) {
            val groups = FileOps.duplicates(File(root.path), { dupCancel.get() }) { }
            duplicates.value = groups; duplicatesRunning.value = false
        }
    }
    fun closeDuplicates() { dupCancel.set(true); duplicates.value = null; duplicatesRunning.value = false }

    // ── properties ──────────────────────────────────────────────────────────

    data class PropertiesState(val entry: FileOps.Entry, val result: FileOps.Properties? = null, val progress: String = "", val running: Boolean = true)

    val properties = MutableStateFlow<PropertiesState?>(null)
    private val propsCancel = AtomicBoolean(false)

    fun openProperties(entry: FileOps.Entry) {
        propsCancel.set(false)
        properties.value = PropertiesState(entry)
        val f = entry.localFile ?: run { properties.value = PropertiesState(entry, running = false); return }
        scope.launch(Dispatchers.IO) {
            val r = FileOps.properties(f, { propsCancel.get() }, FileOps.Progress { done, _, _ -> properties.update { it?.copy(progress = FileOps.humanBytes(done)) } })
            properties.update { it?.copy(result = r, running = false) }
        }
    }
    fun closeProperties() { propsCancel.set(true); properties.value = null }

    companion object {
        const val THUMB_PX = 96
        const val RESULT_LINGER_MS = 4000L

        /** Hidden filter, declared filter, then sort — the one place a pane's rows are decided. */
        fun visibleEntries(pane: PaneState, entries: List<FileOps.Entry>, filters: List<Declarations.FilterDecl>): List<FileOps.Entry> {
            val filter = filters.firstOrNull { it.id == pane.filterId }
            val shown = entries.filter { e -> (pane.showHidden || !e.hidden) && (filter == null || filter.matches(e.isDirectory, e.mime, e.extension)) }
            return FileOps.sortEntries(shown, pane.sort, pane.descending)
        }
    }
}
