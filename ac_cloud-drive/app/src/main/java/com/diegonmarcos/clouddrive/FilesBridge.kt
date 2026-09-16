package com.diegonmarcos.clouddrive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything the page can do to the phone's storage goes through here: browse, sort, make a
 * folder, rename, copy, move, delete, share, open-with, read a text file into the editor, write
 * it back, and mirror one folder onto another. The UI is HTML and holds no state of its own — it
 * asks for a directory and gets the whole listing back, so a change made here (or by any other
 * app) shows up on the next [list] rather than being tracked in two places that can disagree.
 *
 * Every path that crosses the bridge is resolved through [resolve], which refuses anything that
 * escapes the storage roots. The page is local HTML we ship, but a WebView JavascriptInterface is
 * still a boundary: a bug in the page must not be able to hand this class "../../../data/data".
 */
class FilesBridge(private val ctx: Context) {

    /**
     * Where browsing may go. Shared external storage plus the app's own directories, which stay
     * reachable even when the user has not granted all-files access. Kept as canonical paths
     * because that is what [resolve] compares against.
     */
    private val roots: List<File>
        get() = buildList {
            add(Environment.getExternalStorageDirectory())
            ctx.getExternalFilesDir(null)?.let { add(it) }
            addAll(ctx.getExternalFilesDirs(null).filterNotNull())
        }.map { it.canonicalFile }.distinct()

    // ── permission ───────────────────────────────────────────────────────────────

    /**
     * True when the app may actually read the whole of shared storage. Android 11+ needs the
     * all-files special access; below that the legacy read permission is granted at install time.
     * The page asks first and shows its own explanation, so a denied state reads as a prompt
     * instead of an empty folder that looks like a bug.
     */
    @JavascriptInterface
    fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Opens the system screen that grants all-files access. No-op below Android 11. */
    @JavascriptInterface
    fun requestStorageAccess(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return okErr(true, "")
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + ctx.packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            okErr(true, "")
        } catch (error: Exception) {
            okErr(false, error.message ?: "cannot open the all-files access screen")
        }
    }

    /**
     * The storage back ends the Apps tab lists, as the raw JSON array baked in at build time from
     * data/drive-connections.json. Handed over untouched: the page renders the fields it knows and
     * ignores the rest, so adding a field to the data file needs no change here.
     */
    @JavascriptInterface
    fun connections(): String =
        if (BuildConfig.CONNECTIONS_B64.isEmpty()) "[]"
        else try {
            String(android.util.Base64.decode(BuildConfig.CONNECTIONS_B64, android.util.Base64.DEFAULT))
        } catch (error: Exception) {
            "[]"
        }

    // ── browsing ─────────────────────────────────────────────────────────────────

    /** The directories the Files tab offers as starting points, newest-style names included. */
    @JavascriptInterface
    fun places(): String {
        val external = Environment.getExternalStorageDirectory()
        val named = listOf(
            "Internal storage" to external,
            "Downloads" to File(external, Environment.DIRECTORY_DOWNLOADS),
            "Documents" to File(external, Environment.DIRECTORY_DOCUMENTS),
            "Pictures" to File(external, Environment.DIRECTORY_PICTURES),
            "Camera" to File(external, "DCIM/Camera"),
            "Movies" to File(external, Environment.DIRECTORY_MOVIES),
            "Music" to File(external, Environment.DIRECTORY_MUSIC)
        )
        val array = JSONArray()
        // A place that does not exist is not offered: a tap leading to "folder not found" is
        // worse than the entry simply not being there on a phone that has no Music directory.
        named.filter { it.second.isDirectory }.forEach { (label, dir) ->
            array.put(JSONObject().put("label", label).put("path", dir.absolutePath))
        }
        return array.toString()
    }

    /**
     * One directory, whole. [sort] is name, size, modified or type; [descending] reverses it and
     * [showHidden] includes dot files. Folders always sort above files, because a listing that
     * mixes them is the thing every file manager learned not to do.
     */
    @JavascriptInterface
    fun list(path: String, sort: String, descending: Boolean, showHidden: Boolean): String {
        val dir = resolve(path) ?: return failure("that path is outside the storage roots")
        if (!dir.isDirectory) return failure("not a directory: " + dir.name)
        val children = dir.listFiles() ?: return failure("cannot read " + dir.name)

        val visible = children.filter { showHidden || !it.name.startsWith(".") }
        val comparator = when (sort) {
            "size" -> compareBy<File> { if (it.isDirectory) -1L else it.length() }
            "modified" -> compareBy<File> { it.lastModified() }
            "type" -> compareBy<File>({ it.extension.lowercase() }, { it.name.lowercase() })
            else -> compareBy<File> { it.name.lowercase() }
        }
        val ordered = visible.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .then(if (descending) comparator.reversed() else comparator)
        )

        val entries = JSONArray()
        ordered.forEach { entries.put(describe(it)) }
        return JSONObject()
            .put("ok", true)
            .put("path", dir.absolutePath)
            .put("parent", parentWithinRoots(dir)?.absolutePath ?: "")
            .put("entries", entries)
            .toString()
    }

    /** One entry's details, for the info sheet: size, when, what, and how many children. */
    @JavascriptInterface
    fun info(path: String): String {
        val file = resolve(path) ?: return failure("that path is outside the storage roots")
        if (!file.exists()) return failure("no longer there: " + file.name)
        val detail = describe(file)
            .put("readable", file.canRead())
            .put("writable", file.canWrite())
        if (file.isDirectory) detail.put("children", file.listFiles()?.size ?: 0)
        return detail.put("ok", true).toString()
    }

    /** Free and total bytes on the volume holding [path], for the Files tab's storage bar. */
    @JavascriptInterface
    fun usage(path: String): String {
        val dir = resolve(path) ?: return failure("that path is outside the storage roots")
        return try {
            val stat = StatFs(dir.absolutePath)
            JSONObject()
                .put("ok", true)
                .put("free", stat.availableBytes)
                .put("total", stat.totalBytes)
                .toString()
        } catch (error: Exception) {
            failure(error.message ?: "cannot measure that volume")
        }
    }

    // ── changing things ──────────────────────────────────────────────────────────

    @JavascriptInterface
    fun createFolder(parentPath: String, name: String): String {
        val parent = resolve(parentPath) ?: return failure("that path is outside the storage roots")
        val safe = sanitize(name) ?: return failure("a folder name cannot contain a path separator")
        val target = File(parent, safe)
        if (target.exists()) return failure(safe + " already exists")
        return if (target.mkdirs()) pathResult(target) else failure("could not create " + safe)
    }

    @JavascriptInterface
    fun rename(path: String, name: String): String {
        val file = resolve(path) ?: return failure("that path is outside the storage roots")
        val safe = sanitize(name) ?: return failure("a name cannot contain a path separator")
        val target = File(file.parentFile, safe)
        if (target.exists()) return failure(safe + " already exists")
        return if (file.renameTo(target)) pathResult(target) else failure("could not rename " + file.name)
    }

    /**
     * Copies or moves a whole selection into [destinationPath]. Both run through one entry point
     * because the UI treats them as the same gesture with a different verb, and because a move
     * across volumes is a copy followed by a delete — [renameTo] returns false there rather than
     * doing the work, and a file manager that silently loses a file on that boundary is the worst
     * bug this class could have.
     */
    @JavascriptInterface
    fun transfer(pathsJson: String, destinationPath: String, move: Boolean): String {
        val destination = resolve(destinationPath)
            ?: return failure("that destination is outside the storage roots")
        if (!destination.isDirectory) return failure("not a directory: " + destination.name)

        val results = JSONArray()
        var failed = 0
        eachPath(pathsJson) { source ->
            val target = uniqueIn(destination, source.name)
            val done = try {
                when {
                    // Inside one volume a rename is atomic and costs nothing, so try it first.
                    move && source.renameTo(target) -> true
                    else -> {
                        source.copyRecursively(target, overwrite = false)
                        if (move) source.deleteRecursively() else true
                    }
                }
            } catch (error: Exception) {
                false
            }
            if (!done) failed++
            results.put(JSONObject().put("name", source.name).put("ok", done))
        }
        return JSONObject()
            .put("ok", failed == 0)
            .put("failed", failed)
            .put("results", results)
            .toString()
    }

    /** Deletes a selection, directories and all. Reports per entry so a partial failure is visible. */
    @JavascriptInterface
    fun delete(pathsJson: String): String {
        val results = JSONArray()
        var failed = 0
        eachPath(pathsJson) { file ->
            val done = file.deleteRecursively()
            if (!done) failed++
            results.put(JSONObject().put("name", file.name).put("ok", done))
        }
        return JSONObject()
            .put("ok", failed == 0)
            .put("failed", failed)
            .put("results", results)
            .toString()
    }

    // ── handing a file to another app ────────────────────────────────────────────

    /** Opens one file in whatever app claims its type. */
    @JavascriptInterface
    fun open(path: String): String = send(path) { uri, mime ->
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Shares one file through the system sheet. */
    @JavascriptInterface
    fun share(path: String): String = send(path) { uri, mime ->
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null
        )
    }

    private fun send(path: String, build: (Uri, String) -> Intent): String {
        val file = resolve(path) ?: return failure("that path is outside the storage roots")
        if (!file.isFile) return failure("not a file: " + file.name)
        return try {
            // A file:// URI has been illegal to hand another app since Android 7, so everything
            // leaves through the provider declared in the manifest.
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            val intent = build(uri, mimeOf(file))
            // The bridge is reachable from a plain Context, so the activity flag has to be set
            // explicitly rather than relying on the caller being an Activity.
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            okErr(true, "")
        } catch (error: Exception) {
            failure(error.message ?: "nothing on this phone can open " + file.name)
        }
    }

    // ── the editor ───────────────────────────────────────────────────────────────

    /**
     * Hands the page a text file's whole content so it can be edited. The ceiling and the
     * binary check are the two ways an editor destroys a file rather than editing it:
     *
     *  - a WebView holds the text as one JavaScript string, so opening a multi-gigabyte log
     *    is an out-of-memory kill rather than a slow editor, and the file is untouched only
     *    because the process died before a save.
     *  - a file that is not text survives a round trip through a String only by accident.
     *    Bytes that are not valid UTF-8 come back as U+FFFD and are written back as U+FFFD,
     *    so "open, change nothing, save" silently corrupts a photo. Refusing to open it is
     *    the only honest answer.
     */
    @JavascriptInterface
    fun readText(path: String): String {
        val file = resolve(path) ?: return failure("that path is outside the storage roots")
        if (!file.isFile) return failure("not a file: " + file.name)
        if (file.length() > EDITABLE_BYTE_CEILING)
            return failure(file.name + " is too large to edit here (over " +
                (EDITABLE_BYTE_CEILING / 1024 / 1024) + " MB)")
        return try {
            val bytes = file.readBytes()
            // One NUL byte is the oldest and most reliable "this is not text" signal there
            // is, and it is what file(1) and git both use.
            if (bytes.any { it == 0.toByte() })
                return failure(file.name + " is not a text file")

            // THE EDITOR WILL NOT OPEN WHAT IT CANNOT WRITE BACK. Decoding is lossy in one
            // direction only: bytes that are not valid UTF-8 become U+FFFD and re-encode as
            // U+FFFD, so a Latin-1 file full of accented characters survives the NUL check,
            // opens looking almost right, and is rewritten damaged by a save that changed
            // nothing. Re-encoding and comparing is the exact question worth asking, and it
            // is the only one that catches it.
            val text = String(bytes, Charsets.UTF_8)
            if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
                return failure(file.name + " is not UTF-8 text, and saving it here would damage it")

            JSONObject()
                .put("ok", true)
                .put("path", file.absolutePath)
                .put("name", file.name)
                .put("text", text)
                .toString()
        } catch (error: Exception) {
            failure(error.message ?: "cannot read " + file.name)
        }
    }

    /**
     * Writes the editor's text back, as UTF-8. THIS IS THE SAVE PATH.
     *
     * The bytes go to a sibling scratch file first and only then take the target's name, so
     * a process death half way through a write leaves the original whole instead of
     * truncated. A scratch file in the same directory rather than in a cache directory
     * because the rename has to stay on one filesystem to be cheap, and the sibling is the
     * only placement guaranteed to be.
     */
    @JavascriptInterface
    fun writeText(path: String, text: String): String {
        val file = resolve(path) ?: return failure("that path is outside the storage roots")
        if (file.exists() && !file.isFile) return failure("not a file: " + file.name)
        val parent = file.parentFile ?: return failure("no folder to write into")
        val payload = text.toByteArray(Charsets.UTF_8)
        val scratch = File(parent, "." + file.name + ".clouddrive-save")
        return try {
            scratch.writeBytes(payload)
            // A short scratch file means the volume filled up mid-write. Stopping here is
            // what keeps the original intact.
            if (scratch.length() != payload.size.toLong()) {
                scratch.delete()
                return failure("the save did not finish — " + file.name + " is unchanged")
            }
            if (!scratch.renameTo(file)) {
                // Shared storage on a modern phone is a FUSE mount, and renaming onto a
                // name that already exists is one of the things it declines. A copy is
                // slower and is not atomic, which is exactly why it is the fallback.
                scratch.copyTo(file, overwrite = true)
                scratch.delete()
            }
            pathResult(file)
        } catch (error: Exception) {
            scratch.delete()
            failure(error.message ?: "could not save " + file.name)
        }
    }

    // ── mirroring, which is what Rsync means on a phone ──────────────────────────

    /**
     * The mirror jobs the Backups tab offers, as the raw JSON array baked in at build time
     * from data/drive-mirror-jobs.json. Same carrier and same contract as [connections].
     */
    @JavascriptInterface
    fun mirrorJobs(): String =
        if (BuildConfig.MIRROR_JOBS_B64.isEmpty()) "[]"
        else try {
            String(android.util.Base64.decode(BuildConfig.MIRROR_JOBS_B64, android.util.Base64.DEFAULT))
        } catch (error: Exception) {
            "[]"
        }

    /**
     * Copies everything under [sourcePath] into [destinationPath], skipping what is already
     * there and unchanged — rsync's quick check, which is size plus modification time, and
     * not a checksum. [deleteExtra] is rsync's --delete: entries in the destination that the
     * source no longer has are removed. It is declared per job rather than offered as a
     * toggle, because it is the one option here that loses data.
     *
     * Reports COUNTS, never names. The page shows what a pass did without putting the
     * owner's file names on a screen that anything else could read.
     */
    @JavascriptInterface
    fun mirror(sourcePath: String, destinationPath: String, deleteExtra: Boolean): String {
        val source = resolveDeclared(sourcePath)
            ?: return failure("that source is outside the storage roots")
        if (!source.isDirectory) return failure("not a folder: " + source.name)
        val destination = resolveDeclared(destinationPath)
            ?: return failure("that destination is outside the storage roots")

        // A destination inside its own source grows without end: every pass copies the
        // previous pass's copy. Refusing is the whole guard, and it has to happen before a
        // single byte moves.
        if (destination.path == source.path || destination.path.startsWith(source.path + File.separator))
            return failure("the destination sits inside the source, so each pass would copy the last one")

        val tally = MirrorTally()
        mirrorInto(source, destination, deleteExtra, tally, MIRROR_DEPTH_CEILING)
        return JSONObject()
            .put("ok", tally.failed == 0)
            .put("copied", tally.copied)
            .put("skipped", tally.skipped)
            .put("deleted", tally.deleted)
            .put("failed", tally.failed)
            .toString()
    }

    private class MirrorTally {
        var copied = 0
        var skipped = 0
        var deleted = 0
        var failed = 0
    }

    private fun mirrorInto(
        source: File,
        destination: File,
        deleteExtra: Boolean,
        tally: MirrorTally,
        depthLeft: Int
    ) {
        // ponytail: a plain depth ceiling rather than inode bookkeeping. Shared storage has
        // no symlinks worth speaking of, but one loop would otherwise recurse until the
        // stack ends, and a cheap ceiling is the difference between a wrong count and a
        // crash. Track visited canonical paths if real symlink farms ever appear here.
        if (depthLeft <= 0) { tally.failed++; return }
        if (!destination.isDirectory && !destination.mkdirs()) { tally.failed++; return }
        val children = source.listFiles() ?: run { tally.failed++; return }

        children.forEach { child ->
            val target = File(destination, child.name)
            if (child.isDirectory) {
                mirrorInto(child, target, deleteExtra, tally, depthLeft - 1)
            } else if (alreadyMirrored(child, target)) {
                tally.skipped++
            } else {
                try {
                    child.copyTo(target, overwrite = true)
                    // Without this the next pass sees a different timestamp and copies the
                    // same file again, for ever. ponytail: a filesystem that refuses to set
                    // it (some SD-card mounts) turns every pass into a full copy rather
                    // than failing — correct, just not cheap.
                    target.setLastModified(child.lastModified())
                    tally.copied++
                } catch (error: Exception) {
                    tally.failed++
                }
            }
        }

        if (!deleteExtra) return
        val kept = children.map { it.name }.toSet()
        destination.listFiles()?.filterNot { kept.contains(it.name) }?.forEach { extra ->
            if (extra.deleteRecursively()) tally.deleted++ else tally.failed++
        }
    }

    /**
     * rsync's quick check: same size and same modification time means "do not send it".
     * Compared at whole seconds because that is the coarsest resolution any of the
     * filesystems involved keeps, and comparing milliseconds against a volume that stores
     * seconds marks every file changed.
     */
    private fun alreadyMirrored(source: File, target: File): Boolean =
        target.isFile &&
            target.length() == source.length() &&
            target.lastModified() / 1000L == source.lastModified() / 1000L

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Turns a path from the page into a real File, or null when it points outside the roots.
     * Canonicalised first, so "Download/../../../data" is compared as what it actually resolves
     * to rather than as the string that was typed.
     */
    private fun resolve(path: String): File? {
        if (path.isBlank()) return null
        val candidate = try {
            File(path).canonicalFile
        } catch (error: Exception) {
            return null
        }
        val allowed = roots.any { root ->
            candidate == root || candidate.path.startsWith(root.path + File.separator)
        }
        return if (allowed) candidate else null
    }

    /**
     * The same as [resolve], except that a relative path is read against shared storage.
     * data/drive-mirror-jobs.json declares "DCIM/Camera" rather than
     * "/storage/emulated/0/DCIM/Camera" because the absolute form is a fact about one
     * device — the user id in it changes on a second profile — and a declarative list must
     * not carry it.
     */
    private fun resolveDeclared(path: String): File? {
        if (path.isBlank()) return null
        val absolute =
            if (path.startsWith(File.separator)) path
            else File(Environment.getExternalStorageDirectory(), path).path
        return resolve(absolute)
    }

    /** The parent, unless that would step above a root — where "up" has to stop. */
    private fun parentWithinRoots(dir: File): File? {
        if (roots.any { it == dir }) return null
        return dir.parentFile?.let { resolve(it.absolutePath) }
    }

    private fun describe(file: File): JSONObject = JSONObject()
        .put("name", file.name)
        .put("path", file.absolutePath)
        .put("directory", file.isDirectory)
        .put("hidden", file.name.startsWith("."))
        .put("size", if (file.isDirectory) 0L else file.length())
        .put("modified", file.lastModified())
        .put("mime", if (file.isDirectory) "inode/directory" else mimeOf(file))

    private fun mimeOf(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    /** A name that cannot climb out of its directory, or null if the page sent one that could. */
    private fun sanitize(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return null
        if (trimmed.contains(File.separatorChar)) return null
        return trimmed
    }

    /**
     * A free name in [destination]: "report.pdf" becomes "report (2).pdf" rather than overwriting
     * what is already there. Copying onto an existing file loses data with no undo, so the default
     * is never to.
     */
    private fun uniqueIn(destination: File, name: String): File {
        var candidate = File(destination, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var counter = 2
        while (candidate.exists()) {
            val suffix = if (extension.isEmpty()) "" else "." + extension
            candidate = File(destination, base + " (" + counter + ")" + suffix)
            counter++
        }
        return candidate
    }

    /** Runs [action] over every resolvable path in a JSON array, skipping the ones out of bounds. */
    private fun eachPath(pathsJson: String, action: (File) -> Unit) {
        val array = try {
            JSONArray(pathsJson)
        } catch (error: Exception) {
            return
        }
        for (index in 0 until array.length()) {
            resolve(array.optString(index))?.let(action)
        }
    }

    private fun pathResult(file: File): String =
        JSONObject().put("ok", true).put("path", file.absolutePath).put("name", file.name).toString()

    private fun failure(reason: String): String = okErr(false, reason)

    private fun okErr(ok: Boolean, error: String): String =
        JSONObject().put("ok", ok).put("error", error).toString()

    private companion object {
        /**
         * The largest file the editor will open. The page holds the whole text as one
         * JavaScript string, so this is a memory ceiling rather than a taste judgement:
         * every plain-text file a phone actually holds is far below it, and everything
         * above it is a log or a database that an editor has no business loading whole.
         */
        const val EDITABLE_BYTE_CEILING = 2L * 1024 * 1024

        /** How deep [mirror] will walk. See the ponytail note in mirrorInto. */
        const val MIRROR_DEPTH_CEILING = 32
    }
}
