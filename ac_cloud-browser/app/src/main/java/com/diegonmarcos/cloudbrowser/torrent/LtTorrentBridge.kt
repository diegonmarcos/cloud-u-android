package com.diegonmarcos.cloudbrowser.torrent

import android.content.Context
import com.diegonmarcos.superapp.browser.download.DlJob
import com.diegonmarcos.superapp.browser.download.DlLive
import com.diegonmarcos.superapp.browser.download.DlPolicy
import com.diegonmarcos.superapp.browser.download.DlPublisher
import com.diegonmarcos.superapp.browser.download.DlQueue
import com.diegonmarcos.superapp.browser.download.DlSeedRule
import com.diegonmarcos.superapp.browser.download.DlState
import com.diegonmarcos.superapp.browser.download.DlTorrentBridge
import com.diegonmarcos.superapp.browser.download.DlTorrentFile
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.StateUpdateAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * cloud-browser's BitTorrent engine: libtorrent (libtorrent4j, MIT) behind libs:browser's
 * [DlTorrentBridge] seam, so torrents live in the same queue, the same dataSync foreground
 * service and the same Downloads page as HTTP downloads.
 *
 * Session: DHT, PeX (libtorrent's default ut_pex plugin), LSD and uTP on; the queue's speed limit
 * and the torrent upload limit applied session-wide. Per torrent: sequential order (media preview),
 * file selection, pause / resume, seeding with ratio / time limits. Resume data
 * (save_resume_data, with the info dict) is written next to each torrent's files every minute,
 * on metadata, on pause and on finish, so a torrent survives process death and reboot: the
 * queue re-adds it from that file.
 *
 * Named by the manifest's TORRENT_BRIDGE meta-data; [DlTorrentBridge.load] builds it.
 */
class LtTorrentBridge(context: Context) : DlTorrentBridge {
    private val app = context.applicationContext
    private val q get() = DlQueue.get(app)
    /** Built on first use: the native library loads only when a torrent actually runs. */
    private val sm by lazy { SessionManager(false) }
    private val handles = ConcurrentHashMap<String, TorrentHandle>()   // job id -> handle
    private val pending = ConcurrentHashMap.newKeySet<String>()          // asked to add, add_torrent_alert not seen yet
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "torrent-tick").apply { isDaemon = true } }
    @Volatile private var started = false
    @Volatile private var policy = DlPolicy()

    private val listener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.ADD_TORRENT.swig(), AlertType.STATE_UPDATE.swig(), AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_FINISHED.swig(), AlertType.SAVE_RESUME_DATA.swig(), AlertType.TORRENT_ERROR.swig())

        override fun alert(a: Alert<*>) {
            runCatching {
                when (a) {
                    is AddTorrentAlert -> onAdded(a)
                    is StateUpdateAlert -> a.status().forEach { onStatus(it) }
                    is MetadataReceivedAlert -> idOf(a.handle())?.let { id -> onMetadata(id, a.handle()) }
                    is TorrentFinishedAlert -> idOf(a.handle())?.let { id -> onFinished(id, a.handle()) }
                    is SaveResumeDataAlert -> idOf(a.handle())?.let { id ->
                        TorrentFiles.writeAtomic(TorrentFiles.resume(jobDir(id)), AddTorrentParams.writeResumeDataBuf(a.params()))
                    }
                    is TorrentErrorAlert -> idOf(a.handle())?.let { id ->
                        q.torrentUpdate(id, { it.copy(state = DlState.FAILED, error = a.message()) }, null)
                    }
                    else -> Unit
                }
            }
        }
    }

    // ── session ──

    @Synchronized private fun ensureStarted() {
        if (started) return
        policy = q.policy
        val sp = TorrentSettings.pack(policy)
        sm.addListener(listener)
        sm.start(org.libtorrent4j.SessionParams(sp))
        started = true
        ticker.scheduleWithFixedDelay({ runCatching { sm.postTorrentUpdates() } }, 1, 1, TimeUnit.SECONDS)
        ticker.scheduleWithFixedDelay({ runCatching { saveAll(onlyIfNeeded = true) } }, 60, 60, TimeUnit.SECONDS)
        // Nothing is re-added here: after a process death the queue holds every unfinished torrent as QUEUED,
        // and its pump() calls resume(), which re-adds each one from its resume data within the parallel limit.
    }

    override fun applyPolicy(p: DlPolicy) {
        policy = p
        if (started) runCatching { sm.applySettings(TorrentSettings.pack(p)) }
    }

    // ── DlTorrentBridge ──

    /** Keep the .torrent; the queue's pump() starts it (resume) when a slot and an allowed network are free. */
    override fun add(job: DlJob, torrentBytes: ByteArray?) {
        torrentBytes?.let { TorrentFiles.writeAtomic(TorrentFiles.meta(jobDir(job.id)), it) }
    }

    private fun addToSession(job: DlJob, torrentBytes: ByteArray?, paused: Boolean) {
        if (job.id in handles || !pending.add(job.id)) return
        val dir = jobDir(job.id)
        val p: AddTorrentParams = TorrentFiles.params(dir, torrentBytes, job.url) ?: run {
            pending.remove(job.id)
            q.torrentUpdate(job.id, { it.copy(state = DlState.FAILED, error = "Not a valid magnet link or .torrent") }, null)
            return
        }
        p.savePath = File(job.file).path
        var flags = p.flags
        if (job.sequential) flags = flags.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        flags = if (paused) flags.or_(TorrentFlags.PAUSED).and_(TorrentFlags.AUTO_MANAGED.inv())
                else flags.and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.AUTO_MANAGED.inv())
        p.flags = flags
        SessionHandle(sm.swig()).asyncAddTorrent(p)
    }

    override fun pause(id: String) {
        handles[id]?.let { h -> h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.pause(); h.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
    }

    override fun resume(id: String) {
        ensureStarted()
        if (sm.isPaused) sm.resume()
        val h = handles[id]
        if (h == null || !h.isValid) { q.byId(id)?.let { addToSession(it, null, paused = false) }; return }
        h.resume()
    }

    override fun remove(id: String, deleteFiles: Boolean) {
        val h = handles.remove(id) ?: return
        runCatching {
            if (deleteFiles) sm.remove(h, SessionHandle.DELETE_FILES) else sm.remove(h)
        }
        TorrentFiles.resume(jobDir(id)).delete()
    }

    override fun suspend() {
        if (!started) return
        saveAll(onlyIfNeeded = false)
        runCatching { sm.pause() }
    }

    override fun seeding(): Boolean = handles.keys.any { id -> q.byId(id)?.let { it.state == DlState.RUNNING && it.seed && it.total > 0 && it.downloaded >= it.total } == true }

    override fun files(id: String): List<DlTorrentFile> {
        val h = handles[id] ?: return emptyList()
        val ti = runCatching { h.torrentFile() }.getOrNull() ?: return emptyList()
        val fs = ti.files()
        val prog = runCatching { h.fileProgress(TorrentHandle.PIECE_GRANULARITY) }.getOrNull()
        return (0 until fs.numFiles()).map { i ->
            val size = fs.fileSize(i)
            DlTorrentFile(i, fs.filePath(i), size,
                if (size > 0 && prog != null && i < prog.size) (prog[i].toFloat() / size).coerceIn(0f, 1f) else 0f,
                runCatching { h.filePriority(i) != Priority.IGNORE }.getOrDefault(true))
        }
    }

    override fun setFiles(id: String, selected: Set<Int>) {
        val h = handles[id] ?: return
        val n = runCatching { h.torrentFile().numFiles() }.getOrDefault(0)
        if (n == 0) return
        h.prioritizeFiles(Array(n) { if (it in selected) Priority.DEFAULT else Priority.IGNORE })
        q.torrentUpdate(id, { it.copy(selectedFiles = selected.sorted()) }, null)
    }

    override fun setSequential(id: String, on: Boolean) {
        handles[id]?.let { h -> if (on) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
        q.torrentUpdate(id, { it.copy(sequential = on) }, null)
    }

    override fun setSeed(id: String, on: Boolean) {
        q.torrentUpdate(id, { it.copy(seed = on) }, null)
        val j = q.byId(id) ?: return
        if (!on && j.total > 0 && j.downloaded >= j.total) stopSeeding(id)
    }

    override fun shutdown() {
        if (!started) return
        saveAll(onlyIfNeeded = false)
        Thread.sleep(500)   // let the resume-data alerts land
        runCatching { sm.stop() }
        started = false
    }

    // ── alerts ──

    private fun onAdded(a: AddTorrentAlert) {
        val id = File(a.params().savePath ?: return).parentFile?.name ?: return
        pending.remove(id)
        if (a.error().isError) {
            if (id !in handles) q.torrentUpdate(id, { it.copy(state = DlState.FAILED, error = "Could not add the torrent") }, null)
            return
        }
        val h = a.handle()
        val j = q.byId(id)
        if (j == null || j.state == DlState.CANCELLED) { runCatching { sm.remove(h, SessionHandle.DELETE_FILES) }; return }
        handles[id] = h
        if (j.state == DlState.PAUSED) pause(id)
        j.selectedFiles?.let { sel -> runCatching { setFiles(id, sel.toSet()) } }
        q.torrentUpdate(id, { it.copy(infoHash = runCatching { h.infoHash().toHex() }.getOrNull() ?: it.infoHash) }, null)
    }

    private fun onMetadata(id: String, h: TorrentHandle) {
        val name = runCatching { h.name }.getOrNull()
        q.torrentUpdate(id, { j -> j.copy(fileName = name?.takeIf { it.isNotBlank() } ?: j.fileName) }, null)
        q.byId(id)?.selectedFiles?.let { setFiles(id, it.toSet()) }
        h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
    }

    private fun onStatus(s: TorrentStatus) {
        val id = handles.entries.firstOrNull { (_, h) -> runCatching { h.infoHash() == s.infoHashes.getBest() || h.infoHash() == s.infoHashes.v1 }.getOrDefault(false) }?.key
            ?: return
        val live = DlLive(bytesPerSec = s.downloadPayloadRate().toLong(),
            etaSec = if (s.downloadPayloadRate() > 0) (s.totalWanted() - s.totalWantedDone()) / s.downloadPayloadRate() else -1,
            peers = s.numPeers(), seeds = s.numSeeds(), upPerSec = s.uploadPayloadRate().toLong())
        q.torrentUpdate(id, { j ->
            val n = j.copy(total = s.totalWanted().coerceAtLeast(0), downloaded = s.totalWantedDone().coerceAtLeast(0),
                fileName = if (j.fileName == "Torrent" && s.name().isNotBlank()) s.name() else j.fileName)
            if (n.state == DlState.RUNNING && s.isFinished && n.seed &&
                DlSeedRule.reached(policy, s.allTimeUpload(), s.totalWanted(), s.seedingDuration())) {
                ticker.execute { stopSeeding(id) }
            }
            n
        }, live)
    }

    private fun onFinished(id: String, h: TorrentHandle) {
        h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
        val j = q.byId(id) ?: return
        if (!j.seed) ticker.execute { stopSeeding(id) }
    }

    /** Done downloading and not (or no longer) seeding: publish the files to Download/ and leave the session. */
    private fun stopSeeding(id: String) {
        val h = handles.remove(id) ?: return
        runCatching { h.pause(); h.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
        runCatching { sm.remove(h) }   // leave the session first: libtorrent must not hold the files being moved
        val j = q.byId(id) ?: return
        val published = TorrentFiles.publish(app, j)
        q.torrentUpdate(id, { it.copy(state = DlState.COMPLETED, finishedAt = System.currentTimeMillis(),
            publishedUri = published ?: it.publishedUri, error = if (published == null) "Kept in app storage" else null) }, DlLive())
        q.pump()
    }

    private fun saveAll(onlyIfNeeded: Boolean) {
        handles.values.forEach { h -> runCatching { if (!onlyIfNeeded || h.needSaveResumeData()) h.saveResumeData(TorrentHandle.SAVE_INFO_DICT) } }
    }

    private fun idOf(h: TorrentHandle): String? = handles.entries.firstOrNull { it.value == h }?.key

    private fun jobDir(id: String): File = File(q.byId(id)?.file ?: File(File(q.root(), id), "torrent").path).parentFile!!
}

/** The files a torrent keeps next to its data: resume data and the .torrent it came from. */
internal object TorrentFiles {
    fun resume(dir: File) = File(dir, "resume.dat")
    fun meta(dir: File) = File(dir, "meta.torrent")

    fun writeAtomic(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Resume data first (it holds the progress and, once known, the info dict), then the .torrent, then the magnet. */
    fun params(dir: File, torrentBytes: ByteArray?, url: String): AddTorrentParams? {
        val ec = org.libtorrent4j.swig.error_code()
        resume(dir).takeIf { it.isFile }?.readBytes()?.let { b ->
            val p = org.libtorrent4j.swig.libtorrent.read_resume_data_ex(org.libtorrent4j.Vectors.bytes2byte_vector(b), ec)
            if (ec.value() == 0) return AddTorrentParams(p)
        }
        (torrentBytes ?: meta(dir).takeIf { it.isFile }?.readBytes())?.let { b ->
            val e2 = org.libtorrent4j.swig.error_code()
            val p = org.libtorrent4j.swig.add_torrent_params.load_torrent_buffer(org.libtorrent4j.Vectors.bytes2byte_vector(b), e2)
            if (e2.value() == 0) return AddTorrentParams(p)
        }
        if (url.startsWith("magnet:", ignoreCase = true)) return runCatching { AddTorrentParams.parseMagnetUri(url) }.getOrNull()
        return null
    }

    /** Copy every finished, selected file to Download/<dir>/<torrent name>/; null when nothing was published. */
    fun publish(ctx: Context, j: DlJob): String? {
        val root = File(j.file)
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var first: String? = null
        for (f in files) {
            val rel = f.relativeTo(root).parent.orEmpty()
            val sub = listOf(j.dir.trim('/'), rel).filter { it.isNotBlank() }.joinToString("/")
            val uri = DlPublisher.publish(ctx, j.copy(id = "-", file = f.path, fileName = f.name, mime = "", dir = sub, saveToSharedStore = j.saveToSharedStore))
            if (first == null) first = uri
        }
        return first
    }
}

/** The session's settings: DHT, LSD, uTP (in and out) on, PeX by libtorrent's default plugin; the queue's limits. */
internal object TorrentSettings {
    fun pack(p: DlPolicy): org.libtorrent4j.SettingsPack {
        val sp = org.libtorrent4j.SettingsPack()
        sp.setEnableDht(true)
        sp.setEnableLsd(true)
        sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
        sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
        sp.downloadRateLimit(p.speedLimitKbps * 1024)
        sp.uploadRateLimit(p.uploadKbps * 1024)
        sp.activeDownloads(p.parallel)
        sp.activeSeeds(p.parallel)
        return sp
    }
}
