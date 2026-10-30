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

    // ── #1317 registry names: an origin carrying an OLD name is the same repository (GitHub redirects it) ──

    /** The origin's last path segment (no `.git`, no trailing slash); "" when none or when the URL carries a query/fragment. */
    private fun originSeg(origin: String): String {
        val u = origin.trim()
        if (u.isBlank() || '?' in u || '#' in u) return ""
        val core = u.trimEnd('/').let { if (it.endsWith(".git", true)) it.dropLast(4) else it }
        return core.substring(maxOf(core.lastIndexOf('/'), core.lastIndexOf(':')) + 1)
    }

    /**
     * The origin with ONLY its repository name swapped for the current GitHub name (scheme, host, owner and the `.git` suffix kept:
     * https, ssh:// and scp-like alike). null = nothing to fix: the name is current, or it is NOT a `renamed_from` of a registry repository (never rewritten).
     */
    fun fixedOrigin(origin: String, names: RegistryNames): String? {
        val seg = originSeg(origin)
        if (seg.isBlank() || !names.isOld(seg)) return null
        val u = origin.trim().trimEnd('/')
        val hadGit = u.endsWith(".git", true)
        val core = if (hadGit) u.dropLast(4) else u
        val cut = maxOf(core.lastIndexOf('/'), core.lastIndexOf(':'))
        return core.substring(0, cut + 1) + names.githubName(seg) + (if (hadGit) ".git" else "")
    }

    /** What a row shows as its source: the origin, and for a retired name "(old name of <current>)" so it is never shown as if current. */
    fun originLabel(origin: String, names: RegistryNames): String {
        val seg = originSeg(origin)
        return if (seg.isNotBlank() && names.isOld(seg)) "$origin (old name of ${names.githubName(seg)})" else origin
    }

    /** The Activity Logs line of one fix: `origin cloud-u-linux → cloudy-u-linux`. */
    fun fixNote(origin: String, names: RegistryNames): String = "origin ${originSeg(origin)} \u2192 ${names.githubName(originSeg(origin))}"

    /** Every name [archiveName] may probe in the archive root. */
    fun archiveCandidates(name: String, nowMs: Long): List<String> = listOf(name, name + "__" + stamp(nowMs)) + (2..MAX_TRIES).map { n -> name + "__" + stamp(nowMs) + "-$n" }

    /** The folder name Rename gives a folder whose origin says [originName]: the CURRENT registry name, never a retired one. null = no usable current name. */
    fun renameName(originName: String, names: RegistryNames): String? {
        val n = names.currentName(originName)
        return if (!usableName(n) || names.isOld(n)) null else n
    }

    /** Folder already carries the registry name (or the GitHub name) of the repository it points at: Rename skips it. */
    fun alreadyNamed(folder: String, originName: String, names: RegistryNames): Boolean = names.carries(folder, originName)

    /** The cld.termux `remote set-url`: the clone's own origin only. */
    fun setOriginScript(guestDir: String, url: String, q: (String) -> String): String =
        "set -e; [ -d ${q(guestDir)} ] || exit 16; git -C ${q(guestDir)} remote get-url origin >/dev/null 2>&1 || exit 19; git -C ${q(guestDir)} remote set-url origin ${q(url)}"

    /** The cld.termux read of origins, one call for many clones: `<guest dir> TAB <url>` per line ("" url = none). */
    fun originsScript(guestDirs: List<String>, q: (String) -> String): String =
        "for d in ${guestDirs.joinToString(" ") { q(it) }}; do printf '%s\\t%s\\n' \"\$d\" \"\$(git -C \"\$d\" remote get-url origin 2>/dev/null)\"; done"

    fun parseOrigins(out: String): Map<String, String> =
        out.lineSequence().filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t').trim() }

    /** The cld.termux existence probe for rename targets: prints each candidate name that EXISTS under [guestParent] (one call, not one per name). */
    fun takenScript(guestParent: String, candidates: List<String>, q: (String) -> String): String =
        "for n in ${candidates.joinToString(" ") { q(it) }}; do [ -e ${q(guestParent)}/\"\$n\" ] && printf '%s\\n' \"\$n\"; done; true"

    /** Every name [renameTarget] may probe for [repoName]: the name and its ___CONFLICT variants. */
    fun renameCandidates(repoName: String): List<String> = listOf(repoName) + (1..MAX_TRIES).map { n -> repoName + CONFLICT + (if (n == 1) "" else n.toString()) }

    /**
     * #1317 whether java.io says the folder is gone. When the store is reached through cld.termux ([viaExec]) java.io cannot stat it,
     * so it is NEVER asked ([isDir] is not called): the cld.termux script decides (its exit 16 is the real "gone").
     */
    fun goneByIo(viaExec: Boolean, isDir: () -> Boolean): Boolean = !viaExec && !isDir()

    sealed interface RenamePlan {
        data class Go(val name: String) : RenamePlan
        data class Skip(val why: String) : RenamePlan
        data class Fail(val why: String) : RenamePlan
    }

    /**
     * #1317 the decision of rename_to_repo, pure. [originName] = the repository name the origin names (maybe a retired one); the
     * target is the CURRENT registry name, a folder that already carries it is skipped, and the free-name search asks
     * [takenExec] (ONE cld.termux probe of every candidate name; null = the probe failed) when [viaExec], else [takenIo] (java.io).
     */
    fun renamePlan(folder: String, originName: String, names: RegistryNames, viaExec: Boolean, takenExec: (List<String>) -> Set<String>?, takenIo: (String) -> Boolean): RenamePlan {
        if (!usableName(originName)) return RenamePlan.Skip("no usable repository name in its origin")
        if (alreadyNamed(folder, originName, names)) return RenamePlan.Skip("already named $folder")
        val want = renameName(originName, names) ?: return RenamePlan.Skip("no current registry name for $originName")
        val taken: (String) -> Boolean = if (viaExec) {
            val set = takenExec(renameCandidates(want)) ?: return RenamePlan.Fail("could not check the names in cld.termux; nothing changed")
            set::contains
        } else takenIo
        val name = renameTarget(want, taken) ?: return RenamePlan.Fail("every $want$CONFLICT name is taken; nothing changed")
        return RenamePlan.Go(name)
    }

    /** One row of a folder table (Parentless or Unlisted): the folder, its origin, and the repository name it should carry ("" = none). */
    data class Target(val path: String, val origin: String, val repoName: String) {
        val name: String get() = File(path).name
    }
}
