package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.sharedstore.StorePaths
import java.io.File

/**
 * rclone runs in its engine APK (its own uid), which cannot open the store in cld.termux's private
 * home. A job leg that names the store is therefore STAGED: the content is copied through
 * cld.termux (or plain File I/O when this app shares its uid) into [StorePaths.stagingRoot], the engine
 * works there, and a destination leg is copied back afterwards. Pure planning here; the copying is a
 * [Copier]. A leg outside the store is left exactly as written.
 */
class StoreLegs(private val paths: StorePaths, private val userId: Int, private val copier: Copier) {

    interface Copier {
        /** Copy [from] into [to] (directory contents merge); [replace] makes [to] an exact copy. Null = done, else the sentence why not. */
        fun copy(from: String, to: String, replace: Boolean): String?
        fun remove(path: String): String?
    }

    /** One job's staging: the args to run, what to copy in first, what to copy back on success. */
    class Plan(val args: List<String>, val pulls: List<Pair<String, String>>, val pushes: List<Triple<String, String, Boolean>>, val removeAfter: List<String>, val cleanup: List<String>)

    /** Null when no leg is in the store; else the plan. [refusal] covers the jobs staging cannot do safely. */
    fun plan(args: List<String>, tag: String): Plan? {
        val op = args.firstOrNull() ?: return null
        if (op !in LEG_OPS || args.size < 3) return null
        val src = args[1]; val dst = args[2]
        val s = paths.stagePath(src, userId, "$tag/src"); val d = paths.stagePath(dst, userId, "$tag/dst")
        if (s == null && d == null) return null
        val out = args.toMutableList(); s?.let { out[1] = it }; d?.let { out[2] = it }
        val pulls = buildList { if (s != null) add(src to s); if (d != null && op != "copyto") add(dst to d) }
        val pushes = if (d != null && op != "check") listOf(Triple(d, dst, op == "sync")) else emptyList()
        return Plan(out, pulls, pushes, if (s != null && op == "move") listOf(src) else emptyList(), listOfNotNull(s?.substringBeforeLast("/src"), d?.substringBeforeLast("/dst")).distinct())
    }

    /** The sentence that says why this job cannot run on a store leg, or null. */
    fun refusal(args: List<String>): String? {
        val op = args.firstOrNull() ?: return null
        val touches = args.drop(1).take(2).any { paths.inStore(it, userId) }
        return if (touches && op == "bisync") "bisync cannot run on a leg in the shared store (the engine cannot open it and two-way staging would lose edits): use copy or sync" else null
    }

    /** Copy the plan's inputs into staging. Null = ready, else why not. */
    fun pull(plan: Plan): String? {
        for ((from, to) in plan.pulls) {
            val missing = copier.copy(from, to, true)
            if (missing != null) return "staging $from failed: $missing"
        }
        return null
    }

    /** After the engine exited with [rc]: copy results back (rc 0 only), drop moved sources, always remove staging. */
    fun finish(plan: Plan, rc: Int): String? {
        var err: String? = null
        if (rc == 0) {
            for ((from, to, replace) in plan.pushes) { copier.copy(from, to, replace)?.let { err = "copy back to $to failed: $it" } }
            if (err == null) for (r in plan.removeAfter) copier.remove(r)?.let { err = "removing moved source $r failed: $it" }
        }
        for (c in plan.cleanup) copier.remove(c)
        return err
    }

    /** Plain File I/O, for when this app runs in the store's uid for the store side and the engine's side is shared storage. */
    class FileCopier : Copier {
        override fun copy(from: String, to: String, replace: Boolean): String? = runCatching {
            val f = File(from); val t = File(to)
            if (!f.exists()) return@runCatching null
            val dst = if (replace) File(to + ".incoming") else t
            // #1317 no stale <to>.incoming from an interrupted run, and none left behind by a failed one.
            if (replace) dst.deleteRecursively()
            try {
                if (f.isDirectory) { dst.mkdirs(); f.copyRecursively(dst, overwrite = true) } else { dst.parentFile?.mkdirs(); f.copyTo(dst, overwrite = true) }
                if (replace) { t.deleteRecursively(); check(dst.renameTo(t)) { "rename failed" } }
            } catch (e: Throwable) { if (replace) dst.deleteRecursively(); throw e }
            null
        }.getOrElse { it.message ?: it.toString() }
        override fun remove(path: String): String? = if (File(path).deleteRecursively() || !File(path).exists()) null else "could not delete $path"
    }

    companion object {
        val LEG_OPS = setOf("copy", "copyto", "sync", "move", "check", "bisync")
    }
}
