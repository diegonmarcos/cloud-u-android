package com.diegonmarcos.cloudlib.disk

import android.content.Context
import android.os.Environment
import com.diegonmarcos.cloudlib.diskscan.CleanPlan
import com.diegonmarcos.cloudlib.diskscan.Duplicates
import com.diegonmarcos.cloudlib.diskscan.Entry
import com.diegonmarcos.cloudlib.diskscan.HugeFiles
import com.diegonmarcos.cloudlib.diskscan.StorageMap
import com.diegonmarcos.cloudlib.diskscan.Tree
import com.diegonmarcos.cloudlib.diskscan.Walk
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * #813 THE DISK MANAGEMENT ENGINE — one facade the page and /api/disk/* both call, so a number on
 * the screen and a number an agent reads off a locked phone come from the same code.
 *
 * [fleet] is the consuming app's list of fleet packages (cloud-drive passes its constellation
 * manifest), [owners] the caches whose rules belong to someone else ([CacheOwner]).
 *
 * The shared-storage walk is the slow part, so the last [Tree] is kept for [TREE_TTL_MS] and
 * every view (map, huge, duplicates, temp) reads it; [rescan] drops it.
 *
 * Every destructive call reports the bytes it reclaimed, and the clean has a dry run that is the
 * same [CleanPlan.Plan] the run executes.
 */
class DiskEngine(
    private val ctx: Context,
    private val fleet: () -> List<String>,
    private val owners: () -> List<CacheOwner> = { emptyList() },
    private val root: () -> File = { Environment.getExternalStorageDirectory() },
) {
    @Volatile private var cached: Pair<Long, Tree>? = null

    fun tree(): Tree {
        cached?.let { (at, t) -> if (System.currentTimeMillis() - at < TREE_TTL_MS) return t }
        val r = root()
        // Android/data and Android/obb are other apps' private trees; all-files access cannot read
        // them on 11+, and walking them only produces denials.
        val t = Walk.tree(r, skip = { it.absolutePath == File(r, "Android/data").absolutePath || it.absolutePath == File(r, "Android/obb").absolutePath })
        cached = System.currentTimeMillis() to t
        return t
    }

    fun rescan(): Tree { cached = null; return tree() }

    fun huge(thresholdBytes: Long = HugeFiles.DEFAULT_THRESHOLD, top: Int = HugeFiles.DEFAULT_TOP): List<Entry> =
        HugeFiles.find(tree().entries, thresholdBytes, top)

    fun duplicates(minBytes: Long = DEFAULT_DUP_MIN): List<Duplicates.Group> = Duplicates.groups(tree().entries, minBytes)

    /** Delete [paths] (a huge file, the other copies of a duplicate); answers the bytes really freed. */
    fun delete(paths: List<String>): CleanPlan.Result {
        val plan = CleanPlan.plan(paths.map { File(it) }.filter { it.isFile }.map { CleanPlan.Item(it.absolutePath, it.length(), "delete") })
        val res = CleanPlan.run(plan, ::sizeOf) { File(it).delete() }
        cached = null
        return res
    }

    // ── the clean ──────────────────────────────────────────────────────

    data class CleanPreview(val plan: CleanPlan.Plan, val owned: Map<String, Long>) {
        val bytes: Long get() = plan.bytes + owned.values.sum()
    }

    /** What a clean would free: this app's own caches, temp files in shared storage, and each owner's preview. */
    fun cleanPreview(): CleanPreview {
        val items = ArrayList<CleanPlan.Item>()
        for (d in listOfNotNull(ctx.cacheDir) + ctx.externalCacheDirs.filterNotNull()) {
            items += CleanPlan.everything(Walk.tree(d).entries, SOURCE_OWN_CACHE)
        }
        items += CleanPlan.temp(tree().entries, SOURCE_TEMP)
        val plan = CleanPlan.plan(items, protect = ::isProtected)
        return CleanPreview(plan, owners().associate { it.id to runCatching { it.preview() }.getOrDefault(0L) })
    }

    data class CleanResult(val reclaimed: Long, val bySource: Map<String, Long>, val skipped: List<String>)

    fun clean(preview: CleanPreview = cleanPreview()): CleanResult {
        val res = CleanPlan.run(preview.plan, ::sizeOf) { File(it).delete() }
        val deleted = res.deleted.toSet()
        val bySource = preview.plan.items.filter { it.path in deleted }.groupBy { it.source }.mapValues { (_, v) -> v.sumOf { it.bytes } }.toMutableMap()
        var total = res.reclaimed
        for (o in owners()) {
            if ((preview.owned[o.id] ?: 0L) <= 0L) continue
            val freed = runCatching { o.clear() }.getOrDefault(0L)
            bySource[o.id] = freed
            total += freed
        }
        cached = null
        return CleanResult(total, bySource, res.skipped)
    }

    /** A path no clean may touch: anything inside an APK cache (pending installs live there). */
    private fun isProtected(path: String): Boolean = PROTECTED_SEGMENTS.any { it in path }

    // ── JSON for /api/disk/* ───────────────────────────────────────────

    fun json(op: String, q: Map<String, String>): JSONObject? = when (op) {
        DiskContract.OP_VOLUMES -> ok().put("volumes", JSONArray(Volumes.list(ctx).map { v ->
            JSONObject().put("id", v.id).put("label", v.label).put("kind", v.kind).put("path", v.path ?: JSONObject.NULL)
                .put("total", v.totalBytes).put("free", v.freeBytes).put("used", v.usedBytes)
        }))
        DiskContract.OP_MAP -> treeJson(if (q["rescan"] == "1") rescan() else tree()) { t ->
            put("slices", JSONArray(StorageMap.slices(t).map { JSONObject().put("name", it.name).put("bytes", it.bytes).put("files", it.files) }))
        }
        DiskContract.OP_HUGE -> {
            val threshold = HugeFiles.parseThreshold(q["threshold"]) ?: HugeFiles.DEFAULT_THRESHOLD
            val top = q["top"]?.toIntOrNull()?.coerceIn(1, 1000) ?: HugeFiles.DEFAULT_TOP
            treeJson(tree()) { t ->
                put("threshold", threshold).put("top", top)
                put("files", JSONArray(HugeFiles.find(t.entries, threshold, top).map { JSONObject().put("path", it.path).put("bytes", it.bytes).put("modified", it.modified) }))
            }
        }
        DiskContract.OP_DUPLICATES -> {
            val min = HugeFiles.parseThreshold(q["min"]) ?: DEFAULT_DUP_MIN
            treeJson(tree()) { t ->
                val groups = Duplicates.groups(t.entries, min)
                put("min", min).put("reclaimable", groups.sumOf { it.reclaimable })
                put("groups", JSONArray(groups.take(q["top"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 100).map { g ->
                    JSONObject().put("hash", g.hash).put("bytes", g.bytes).put("reclaimable", g.reclaimable).put("paths", JSONArray(g.paths))
                }))
            }
        }
        DiskContract.OP_APPS -> {
            val pkgs = (q["pkgs"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: fleet()) + ctx.packageName
            ok().put("usage_access", AppSizes.hasUsageAccess(ctx)).put("apps", JSONArray(AppSizes.sizes(ctx, pkgs).map { s ->
                JSONObject().put("pkg", s.pkg).put("apk", s.apkBytes).put("data", s.dataBytes).put("cache", s.cacheBytes).put("total", s.totalBytes)
                    .put("error", s.error ?: JSONObject.NULL)
            }))
        }
        DiskContract.OP_CLEAN -> {
            val preview = cleanPreview()
            val o = ok().put("dry_run_bytes", preview.bytes).put("files", preview.plan.items.size)
                .put("by_source", JSONObject((preview.plan.bySource() + preview.owned).mapValues { it.value }))
            if (q["run"] == "1") {
                // A run over the API is the same confirm the page asks for, spelled out.
                if (q["confirm"] != "1") ok(false, "run=1 needs confirm=1: a clean deletes files")
                else clean(preview).let { r -> o.put("ran", true).put("reclaimed", r.reclaimed).put("skipped", r.skipped.size)
                    .put("reclaimed_by_source", JSONObject(r.bySource.mapValues { it.value })) }
            } else o.put("ran", false)
        }
        DiskContract.OP_MEMORY -> Memory.snapshot(ctx, fleet()).let { m ->
            ok().put("total", m.totalBytes).put("available", m.availableBytes).put("threshold", m.thresholdBytes).put("low", m.low)
                .put("processes", JSONArray(m.processes.map { p ->
                    JSONObject().put("pkg", p.pkg).put("process", p.process ?: JSONObject.NULL).put("pss_kb", p.pssKb ?: JSONObject.NULL).put("readable", p.readable)
                }))
        }
        else -> null
    }

    private fun ok(ok: Boolean = true, why: String? = null): JSONObject =
        JSONObject().put("ok", ok).put("contract", DiskContract.VERSION).also { if (why != null) it.put("why", why) }

    private fun treeJson(t: Tree, body: JSONObject.(Tree) -> Unit): JSONObject {
        if (!File(t.root).canRead()) return ok(false, "shared storage is not readable: all-files access is not granted")
        return ok().put("root", t.root).put("files_scanned", t.entries.size).put("bytes", t.bytes).put("truncated", t.truncated).apply { body(t) }
    }

    companion object {
        const val TREE_TTL_MS = 60_000L
        const val DEFAULT_DUP_MIN = 1024L * 1024
        const val SOURCE_OWN_CACHE = "own_cache"
        const val SOURCE_TEMP = "temp"
        /** An APK cache (libs:updater's no_backup/updater/…) and anything named a pending download. */
        val PROTECTED_SEGMENTS = listOf("/no_backup/", "/updater/", ".apk")

        fun sizeOf(path: String): Long? = File(path).takeIf { it.isFile }?.length()
    }
}
