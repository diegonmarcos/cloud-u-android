package com.diegonmarcos.clouddrive.files

import android.content.Context
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import com.diegonmarcos.cloudlib.sharedstore.StoreExec
import com.diegonmarcos.cloudlib.sharedstore.StoreExec.Companion.q
import java.io.File
import java.io.IOException

/**
 * Changing the terminal's filesystem when this app is NOT in its uid (StoreAccess EXEC, or a dir the
 * shared uid does not open): mkdir, new file, rename, copy, move and delete run INSIDE cld.termux
 * through CLOUD_EXEC (host side, its own uid), the twin of [ExecListing]. The scripts are pure
 * strings so the JVM suite runs them against a temp dir with a real sh.
 */
object ExecMutation {

    /** The shell template uses a section sign for the dollar so Kotlin does not template it. */
    private fun sh(t: String) = t.replace('\u00a7', '$')

    private fun uniqueTarget(dirVar: String, nameVar: String) =
        "t=\"\u00a7$dirVar/\u00a7$nameVar\"; n=1; while [ -e \"\u00a7t\" ] || [ -L \"\u00a7t\" ]; do t=\"\u00a7$dirVar/\u00a7$nameVar (\u00a7n)\"; n=\u00a7((n+1)); done"

    fun mkdirScript(parent: String, name: String): String = sh(
        "D=${q("$parent/$name")}; [ -e \"§D\" ] || [ -L \"§D\" ] && { echo ${q("$name already exists")} >&2; exit 17; }; mkdir -p -- \"§D\"")

    fun touchScript(parent: String, name: String): String = sh(
        "D=${q("$parent/$name")}; [ -e \"§D\" ] || [ -L \"§D\" ] && { echo ${q("$name already exists")} >&2; exit 17; }; : > \"§D\"")

    fun renameScript(path: String, newName: String): String {
        val target = File(File(path).parent ?: "/", newName).path
        return sh("S=${q(path)}; T=${q(target)}; [ -e \"§T\" ] || [ -L \"§T\" ] && { echo ${q("$newName already exists")} >&2; exit 17; }; mv -- \"§S\" \"§T\"")
    }

    fun deleteScript(paths: List<String>): String =
        sh("rc=0; for s in ${paths.joinToString(" ") { q(it) }}; do rm -rf -- \"§s\" || rc=1; done; exit \u00a7rc")

    /** Copy or move [sources] into [dest], each under a unique name (`a (1)`), refusing a folder into itself. */
    fun transferScript(sources: List<String>, dest: String, move: Boolean): String = sh(
        "D=${q(dest)}; rc=0; for s in ${sources.joinToString(" ") { q(it) }}; do b=§{s##*/}; " +
            "case \"§D/\" in \"§s\"/*) echo \"cannot put §b into itself\" >&2; rc=1; continue;; esac; " +
            uniqueTarget("D", "b") + "; " +
            (if (move) "mv -- \"§s\" \"§t\"" else "cp -a -- \"§s\" \"§t\"") + " || rc=1; done; exit §rc")

    /** Names that would walk out of their folder, or break the script line. */
    fun safeName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\u0000' || it == '\n' }

    fun safePath(p: String): Boolean = p.startsWith("/") && p.none { it == '\u0000' || it == '\n' } && p.split('/').none { it == ".." }

    @Throws(IOException::class)
    private fun run(ctx: Context, script: String) {
        val r = StoreExec(ctx).runHost(script, timeoutMs = 110_000)
        if (!r.ok) throw IOException("the terminal could not do it: ${r.err.ifBlank { r.out }.take(160)}")
    }

    @Throws(IOException::class)
    fun mkdir(ctx: Context, parent: File, name: String) {
        if (!safeName(name) || !safePath(parent.path)) throw IOException("a folder name cannot contain a path separator")
        run(ctx, mkdirScript(parent.path, name))
    }

    @Throws(IOException::class)
    fun touch(ctx: Context, parent: File, name: String) {
        if (!safeName(name) || !safePath(parent.path)) throw IOException("a file name cannot contain a path separator")
        run(ctx, touchScript(parent.path, name))
    }

    @Throws(IOException::class)
    fun rename(ctx: Context, file: File, name: String) {
        if (!safeName(name) || !safePath(file.path)) throw IOException("a name cannot contain a path separator")
        run(ctx, renameScript(file.path, name))
    }

    @Throws(IOException::class)
    fun delete(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        if (files.any { !safePath(it.path) || !StoreAccess.paths.inOwnerData(it.path, StoreAccess.userId()) }) throw IOException("refusing to delete outside the terminal's data")
        run(ctx, deleteScript(files.map { it.path }))
    }

    /** At least one side must be the terminal's data; the other may be shared storage the terminal can reach. */
    @Throws(IOException::class)
    fun transfer(ctx: Context, files: List<File>, dest: File, move: Boolean) {
        if (files.isEmpty()) return
        val uid = StoreAccess.userId()
        val inData = files.all { StoreAccess.paths.inOwnerData(it.path, uid) } || StoreAccess.paths.inOwnerData(dest.path, uid)
        if (!inData || !safePath(dest.path) || files.any { !safePath(it.path) }) throw IOException("refusing a transfer that does not touch the terminal's data")
        run(ctx, transferScript(files.map { it.path }, dest.path, move))
    }
}
