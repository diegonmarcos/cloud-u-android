package com.diegonmarcos.clouddrive.sync

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * #1288 THE PER-FOLDER COMMANDS' PURE RULES (build.json ui.sync.git.folder_actions), JVM-tested in FolderActionsTest.
 *
 *  - rename_to_repo: the folder takes its repository's name; when that name is taken the folder becomes
 *    `<name>___CONFLICT`, then `___CONFLICT2`, `___CONFLICT3` ... ([conflictName]). Nothing is overwritten or merged.
 *  - archive: the folder moves into `<store git>/<archive_root>/`; a name taken there gets `__<yyyyMMdd-HHmmss>`,
 *    then `-2`, `-3` ... ([archiveName]).
 *  - delete_local: only the local folder goes; [deleteGuard] says when the owner must type the name (uncommitted
 *    changes, unpushed commits, or nothing to compare with).
 */
object FolderActions {

    const val CONFLICT = "___CONFLICT"
    /** The bound of every suffix search: past it the command refuses rather than loop. */
    const val MAX_TRIES = 99

    /** The first free name among [base]___CONFLICT, [base]___CONFLICT2 ...; null when all [MAX_TRIES] are taken. */
    fun conflictName(base: String, taken: (String) -> Boolean): String? =
        (1..MAX_TRIES).asSequence().map { n -> base + CONFLICT + (if (n == 1) "" else n.toString()) }.firstOrNull { !taken(it) }

    /** Where rename_to_repo puts [repoName]: the name itself when free, else the first free conflict name; null = none free. */
    fun renameTarget(repoName: String, taken: (String) -> Boolean): String? =
        if (!taken(repoName)) repoName else conflictName(repoName, taken)

    fun stamp(nowMs: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMs))

    /** The archived folder's name: [name] when free under the archive root, else `name__<stamp>`, then `-2`, `-3` ...; null = none free. */
    fun archiveName(name: String, nowMs: Long, taken: (String) -> Boolean): String? {
        if (!taken(name)) return name
        val s = name + "__" + stamp(nowMs)
        return (1..MAX_TRIES).asSequence().map { n -> if (n == 1) s else "$s-$n" }.firstOrNull { !taken(it) }
    }

    /** `<gitRoot>/<archiveRoot>`; null when the declared root is unsafe (absolute, `..`, odd characters). */
    fun archiveDir(gitRoot: File, archiveRoot: String): File? = if (BareClones.validRoot(archiveRoot)) File(gitRoot, archiveRoot) else null

    /** A folder already inside the archive root is not archived again. */
    fun insideArchive(dir: File, archiveDir: File): Boolean =
        LocalRepos.repointPath(dir.absolutePath, archiveDir.absolutePath, archiveDir.absolutePath) != null

    /** A folder name a command may create: no separator, no dot name, not blank. */
    fun usableName(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name

    /** What Delete found in the clone. [unpushed] null = could not be compared (no upstream / unreadable). */
    data class Guard(val changed: List<String>, val unpushed: Int?, val readError: String? = null) {
        /** The owner must type the folder name: there is local work that exists nowhere else, or it cannot be ruled out. */
        val strict: Boolean get() = changed.isNotEmpty() || unpushed == null || unpushed > 0 || readError != null
        fun reasons(): List<String> = buildList {
            if (readError != null) add("could not read the clone: $readError")
            if (changed.isNotEmpty()) add("${changed.size} uncommitted change(s)")
            if (unpushed == null && readError == null) add("no upstream to compare with: commits may exist only here")
            if (unpushed != null && unpushed > 0) add("$unpushed unpushed commit(s)")
        }
    }

    /** The strict confirm passes only on the exact folder name. */
    fun typedOk(typed: String, folderName: String): Boolean = typed.trim() == folderName && folderName.isNotBlank()

    /** `git status --porcelain` lines and a `rev-list --count` answer ("" / non-number = not comparable) as a [Guard]. */
    fun guardOf(porcelain: String, unpushedCount: String): Guard =
        Guard(porcelain.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList(), unpushedCount.trim().toIntOrNull())

    /** The cld.termux script that reads a [Guard]: porcelain, a separator line, then the unpushed count (blank = no upstream). */
    fun guardScript(guestDir: String, q: (String) -> String): String =
        "cd ${q(guestDir)} || exit 2; git status --porcelain 2>/dev/null | head -n 200; echo '$SEP'; " +
            "git rev-parse -q --verify '@{u}' >/dev/null 2>&1 && git rev-list --count '@{u}..HEAD' 2>/dev/null; true"

    fun parseGuard(out: String): Guard {
        val i = out.indexOf(SEP)
        return if (i < 0) Guard(emptyList(), null, "no answer") else guardOf(out.substring(0, i), out.substring(i + SEP.length))
    }
    const val SEP = "--cld-guard--"

    /** The cld.termux move: refuses (exit 17) when the destination exists, so a race never merges two folders. */
    fun moveScript(from: String, to: String, q: (String) -> String): String =
        "set -e; [ -d ${q(from)} ] || exit 16; [ ! -e ${q(to)} ] || exit 17; mkdir -p \"\$(dirname ${q(to)})\"; mv -- ${q(from)} ${q(to)}"

    /** The cld.termux delete of one folder (never a glob, never the store root itself). */
    fun deleteScript(dir: String, q: (String) -> String): String = "set -e; [ -d ${q(dir)}/.git ] || [ -f ${q(dir)}/.git ] || exit 18; rm -rf -- ${q(dir)}"

    /** One row of a folder table (Parentless or Unlisted): the folder, its origin, and the repository name it should carry ("" = none). */
    data class Target(val path: String, val origin: String, val repoName: String) {
        val name: String get() = File(path).name
    }
}
