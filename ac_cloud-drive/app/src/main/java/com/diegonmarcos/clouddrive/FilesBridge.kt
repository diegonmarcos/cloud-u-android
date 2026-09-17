package com.diegonmarcos.clouddrive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.ContactsContract.Intents.Insert
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.diegonmarcos.superapp.image.mlkit.BarcodePayload
import com.diegonmarcos.superapp.image.mlkit.ImageScanEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything the page can do to the phone's storage goes through here: browse, sort, make a
 * folder, rename, copy, move, delete, share, open-with, read a text file into the editor, write
 * it back, mirror one folder onto another, and launch the data apps the Apps tab links out to.
 * The UI is HTML and holds no state of its own — it asks for a directory and gets the whole
 * listing back, so a change made here (or by any other app) shows up on the next [list] rather
 * than being tracked in two places that can disagree.
 *
 * Every path that crosses the bridge is resolved through [resolve], which refuses anything that
 * escapes the storage roots. The page is local HTML we ship, but a WebView JavascriptInterface is
 * still a boundary: a bug in the page must not be able to hand this class "../../../data/data".
 */
class FilesBridge(
    private val ctx: Context,
    /** The Activity-owned launcher behind the SAF tree grant, injected so the bridge can fire
     *  ACTION_OPEN_DOCUMENT_TREE without owning an Activity. The Activity's result callback
     *  hands the chosen tree URI back through [persistTreeGrant]. */
    private val launchTreeGrant: () -> Unit = { }
) {
    /** Where the persisted SAF tree grant lives. SharedPreferences rather than a file because
     *  the system stores the persistable permission itself; this is only the remembered URI and
     *  the display name that came with it, so the app can say "SD card (granted)" after a restart
     *  without forcing the user through the picker again. */
    private val grantStore: SharedPreferences =
        ctx.getSharedPreferences("cloud-drive-storage-grants", Context.MODE_PRIVATE)

    /** The ONE shared scan engine; see libs:ml-l-image-mlkit (task #459/#460). */
    private val scanEngine = ImageScanEngine(ctx)

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
     * The storage back ends the Sync tab's Rclone subpage lists, as the raw JSON array baked in at
     * build time from data/drive-connections.json. Handed over untouched: the page renders the
     * fields it knows and ignores the rest, so adding a field to the data file needs no change here.
     */
    @JavascriptInterface
    fun connections(): String =
        if (BuildConfig.CONNECTIONS_B64.isEmpty()) "[]"
        else try {
            String(android.util.Base64.decode(BuildConfig.CONNECTIONS_B64, android.util.Base64.DEFAULT))
        } catch (error: Exception) {
            "[]"
        }

    // ── the Apps tab grid ─────────────────────────────────────────────────────────

    /**
     * The icon-link grid the Apps tab renders, as the JSON array baked in at build time.
     * data/drive-apps.json declares the selection and the tile presentation; the build resolves
     * every fleet entry's package from constellation-fleet.json, so this list carries identity
     * with no second declaration to keep in step (the #170/#380/#381 defect shape).
     */
    @JavascriptInterface
    fun apps(): String =
        if (BuildConfig.UI_APPS_B64.isEmpty()) "[]"
        else try {
            String(android.util.Base64.decode(BuildConfig.UI_APPS_B64, android.util.Base64.DEFAULT))
        } catch (error: Exception) {
            "[]"
        }

    /**
     * Launches the application named by the JSON payload the Apps tab hands over — the resolved
     * entry { package, fallback_url } — through the system launcher. The manifest declares a
     * broad launcher query rather than naming packages, so which apps are reachable is decided by
     * what Android can launch, not by a second list in this file. If nothing is installed and the
     * entry carries a fallback URL, that URL opens in the system browser instead.
     */
    @JavascriptInterface
    fun openApp(appJson: String): String {
        val spec = try {
            JSONObject(appJson)
        } catch (error: Exception) {
            return failure("the app tile handed the bridge an unreadable payload")
        }
        val packageId = spec.optString("package")
        if (packageId.isBlank()) return failure("the app tile declares no package")
        val launch = try {
            ctx.packageManager.getLaunchIntentForPackage(packageId)
        } catch (error: Exception) {
            null
        }
        if (launch != null) {
            return try {
                // The bridge is reachable from a plain Context, so the activity flag has to be set
                // explicitly rather than relying on the caller being an Activity.
                if (ctx !is Activity) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch)
                okErr(true, "")
            } catch (error: Exception) {
                failure(error.message ?: "cannot open " + packageId)
            }
        }
        val fallback = spec.optString("fallback_url")
        if (fallback.isNotBlank()) {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fallback))
                if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                okErr(true, "")
            } catch (error: Exception) {
                failure(error.message ?: "cannot open the fallback for " + packageId)
            }
        }
        return failure("the app is not installed: " + packageId)
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

        // Removable volumes. Two answers, because Android itself has two answers:
        //  - the app's own directory on every mounted volume is reachable WITHOUT the
        //    all-files special access — getExternalFilesDirs() grants it by construction — so
        //    those are always offered when a volume is mounted.
        //  - the volume ROOT needs MANAGE_EXTERNAL_STORAGE beyond the app's slice; when that
        //    grant is live, the root is offered as the whole drive. When it is not live, a SAF
        //    tree grant is the only lawful route the OS gives a file manager, and the persisted
        //    grant (grantStore) is offered as a place that reopens the system picker at the
        //    granted tree rather than pretending the drive is path-addressable.
        val appDirs = ctx.getExternalFilesDirs(null).filterNotNull()
        appDirs.drop(1).forEach { dir ->
            if (!dir.isDirectory) return@forEach
            array.put(JSONObject()
                .put("label", volumeLabelOf(dir) + " — app files")
                .put("path", dir.absolutePath)
                .put("kind", "path"))
        }
        if (hasStorageAccess()) {
            File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" }
                ?.forEach { volume ->
                    array.put(JSONObject()
                        .put("label", volumeLabelOf(volume))
                        .put("path", volume.absolutePath)
                        .put("kind", "path"))
                }
        }
        readTreeGrant()?.let { (uri, name) ->
            array.put(JSONObject()
                .put("label", name)
                .put("uri", uri.toString())
                .put("kind", "tree"))
        }
        // The connect affordance is offered whenever a removable volume might exist but no
        // path to one is visible: the user who just plugged a card into a slot sees a chip
        // instead of silence.
        val hasRemovablePath = array.length() > named.size || appDirs.size > 1
        if (!hasRemovablePath) {
            array.put(JSONObject()
                .put("label", "Connect a storage drive…")
                .put("kind", "connect"))
        }
        return array.toString()
    }

    /** A readable name for the volume an entry sits on. The path between "/storage/" and the
     *  next slash is the volume id — "emulated" for the internal card, an id or friendly name
     *  for a removable one — while the entry itself may be deep below it (an app-dir path ends
     *  in .../files, which would name the folder, not the card it sits on). */
    private fun volumeLabelOf(dir: File): String {
        val volume = dir.absolutePath
            .substringAfter("/storage/", "")
            .substringBefore("/", "")
        return when {
            volume.isEmpty() || volume == "emulated" -> "Removable storage"
            volume.lowercase() == "usb" -> "USB drive"
            volume.contains("-") -> "SD card"
            else -> volume
        }
    }

    /** Asks the user to grant a whole volume through the system picker, then keeps the grant. */
    @JavascriptInterface
    fun requestTreeGrant(): String {
        return try {
            launchTreeGrant()
            okErr(true, "")
        } catch (error: Exception) {
            failure(error.message ?: "cannot open the system folder picker")
        }
    }

    /** The persisted tree grant, as { granted, uri, name } so the page can show and forget it. */
    @JavascriptInterface
    fun treeGrantInfo(): String =
        readTreeGrant()?.let { (uri, name) ->
            JSONObject().put("ok", true).put("granted", true)
                .put("uri", uri.toString()).put("name", name).toString()
        } ?: JSONObject().put("ok", true).put("granted", false).toString()

    /** Drops the remembered grant. The OS permission itself is released by the system. */
    @JavascriptInterface
    fun forgetTreeGrant(): String {
        grantStore.edit().clear().apply()
        return okErr(true, "")
    }

    /** Called by MainActivity when the SAF picker returns. Persists the OS-level grant and the
     *  URI, exactly the two things a future session needs to know the volume was chosen. */
    fun persistTreeGrant(uri: Uri?) {
        if (uri == null) return
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (error: Exception) {
            // A grant that cannot be persisted is still usable today; it just will not outlive
            // the process. Not worth failing the picker over.
        }
        val name = try {
            DocumentsContract.getTreeDocumentId(uri)
                .substringBeforeLast(':')
        } catch (error: Exception) {
            uri.lastPathSegment ?: "Storage drive"
        }
        grantStore.edit().putString(KEY_TREE_GRANT_URI, uri.toString())
            .putString(KEY_TREE_GRANT_NAME, name).apply()
    }

    private fun readTreeGrant(): Pair<Uri, String>? {
        val uri = grantStore.getString(KEY_TREE_GRANT_URI, null) ?: return null
        val name = grantStore.getString(KEY_TREE_GRANT_NAME, null) ?: "Storage drive"
        return runCatching { Uri.parse(uri) }.getOrNull()?.let { it to name }
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
    fun createFile(parentPath: String, name: String): String {
        val parent = resolve(parentPath) ?: return failure("that path is outside the storage roots")
        val safe = sanitize(name) ?: return failure("a file name cannot contain a path separator")
        val target = File(parent, safe)
        if (target.exists()) return failure(safe + " already exists")
        return try {
            if (target.createNewFile()) pathResult(target)
            else failure("could not create " + safe)
        } catch (error: Exception) {
            failure(error.message ?: "could not create " + safe)
        }
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

        // A paste that cannot fit is refused BEFORE a single byte moves, with the actual
        // numbers. Shared-storage FUSE mounts can fail a copy half way at exactly the moment
        // the free space runs out, leaving a partial file that looks like a finished one; a
        // refusal up front cannot do that.
        val needed = measureTotalSize(pathsJson) ?: 0L
        val free = try {
            StatFs(destination.absolutePath).availableBytes
        } catch (error: Exception) {
            0L
        }
        if (needed > free) {
            return failure("not enough space: this " + (if (move) "move" else "copy") +
                " needs " + humanBytes(needed) + " but only " + humanBytes(free) + " is free " +
                "on " + destination.name)
        }

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

    // ── the file manager's own tools ─────────────────────────────────────────

    /* Search, create-file, zip, properties, bulk-rename and duplicate-finding all share a
     * job runner: each heavy one starts on a background thread, returns a token immediately,
     * and reports progress through a status call the page polls. The page stays touchable
     * while a camera roll is hashed, and a Cancel button actually cancels — which a
     * synchronous bridge call could not say. */
    private val jobSessions = ConcurrentHashMap<String, BackgroundJob>()

    private val jobIds = AtomicInteger(0)

    private fun startJob(work: (BackgroundJob, AtomicBoolean) -> Unit): String {
        val id = "job-" + jobIds.incrementAndGet()
        val job = BackgroundJob()
        jobSessions[id] = job
        JOB_EXECUTOR.execute { job.run { work(this, cancelRequested) } }
        return JSONObject().put("ok", true).put("token", id).toString()
    }

    private fun statusOf(token: String, features: JSONObject): String {
        val job = jobSessions[token] ?: return failure("no such job: " + token)
        job.snapshot?.let { return it.toString() }
        features.put("ok", true)
            .put("done", job.done)
            .put("cancelled", job.cancelled.get() || job.cancelRequested.get())
            .put("scanned", job.scanned)
            .put("bytesProgress", job.bytesProgress)
        return features.toString()
    }

    @JavascriptInterface
    fun cancelJob(token: String): String {
        jobSessions[token]?.cancelRequested?.set(true)
        return okErr(true, "")
    }

    /**
     * Name search, and — when [contentToo] — text-content search under [root]. Name matching
     * is always on and matches anywhere in the name; content matching reads text files up to
     * [SEARCH_TEXT_BYTE_CEILING] and stops at [SEARCH_RESULT_CEILING] results, because a
     * "found 40 000 matches" inbox is a denial-of-service for the very page that asked.
     * Runs on the job runner so the page can cancel a walk over a whole card.
     */
    @JavascriptInterface
    fun search(root: String, query: String, contentToo: Boolean): String {
        val dir = resolve(root) ?: return failure("that path is outside the storage roots")
        if (!dir.isDirectory) return failure("not a folder: " + dir.name)
        val needle = query.trim()
        if (needle.isEmpty()) return failure("a search needs a query")
        val lowercaseNeedle = needle.lowercase()

        return startJob { job, cancelled ->
            val found = JSONArray()
            val truncated = walkSearch(dir, lowercaseNeedle, contentToo, job, cancelled, found)
            job.finish(JSONObject()
                .put("truncated", truncated)
                .put("entries", found))
        }
    }

    @JavascriptInterface
    fun searchStatus(token: String): String = statusOf(token, JSONObject())

    private fun walkSearch(
        dir: File,
        needle: String,
        contentToo: Boolean,
        job: BackgroundJob,
        cancelled: AtomicBoolean,
        found: JSONArray
    ): Boolean {
        if (cancelled.get()) return false
        val children = dir.listFiles() ?: return false
        for (child in children) {
            if (cancelled.get()) return false
            job.scanned++
            val nameMatch = child.name.lowercase().contains(needle)
            if (nameMatch || (contentToo && matchesText(child, needle))) {
                found.put(describe(child).put("matchedBy", if (nameMatch) "name" else "content"))
                if (found.length() >= SEARCH_RESULT_CEILING) return true
            }
            if (child.isDirectory && walkSearch(child, needle, contentToo, job, cancelled, found)) return true
        }
        return false
    }

    /** True when [file]'s text bytes contain [needle], and only asked for files small enough
     *  that reading them whole is not an out-of-memory kill (the editor's own ceiling). */
    private fun matchesText(file: File, needle: String): Boolean {
        if (!file.isFile) return false
        if (file.length() > SEARCH_TEXT_BYTE_CEILING) return false
        if (!mimeOf(file).startsWith("text/")) return false
        return runCatching {
            val text = String(file.readBytes(), Charsets.UTF_8)
            // A binary file with no NUL byte and a text/ MIME (a JAR, a class file) is still
            // searchable safely: finding a byte sequence in binary string is harmless.
            text.lowercase().contains(needle)
        }.getOrDefault(false)
    }

    /**
     * Zips the selection into a new archive at [destinationZipPath]: folders become paths
     * inside the archive, entry names stay inside their own folder name, and a failed pass
     * deletes the partial archive so a half-written zip is never left behind to be opened.
     */
    @JavascriptInterface
    fun archive(pathsJson: String, destinationZipPath: String): String {
        val destination = resolve(destinationZipPath)
            ?: return failure("that archive path is outside the storage roots")
        if (destination.exists()) return failure(destination.name + " already exists")
        val sources = mutableListOf<File>()
        eachPath(pathsJson) { sources.add(it) }
        if (sources.isEmpty()) return failure("nothing to archive")

        return try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
                val usedNames = mutableSetOf<String>()
                sources.forEach { source ->
                    if (source.isDirectory) {
                        val base = uniqueZipName(usedNames, source.name, true)
                        zipFolderInto(source, base, zip, ARCHIVE_DEPTH_CEILING)
                    } else {
                        val base = uniqueZipName(usedNames, source.name, false)
                        zip.putNextEntry(ZipEntry(base))
                        FileInputStream(source).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            JSONObject().put("ok", true).put("path", destination.absolutePath)
                .put("entries", sources.size).toString()
        } catch (error: Exception) {
            destination.delete()
            failure(error.message ?: "the archive did not finish")
        }
    }

    private fun zipFolderInto(
        source: File,
        prefix: String,
        zip: ZipOutputStream,
        depthLeft: Int
    ) {
        if (depthLeft <= 0) return
        val children = source.listFiles() ?: return
        children.forEach { child ->
            if (child.isDirectory) {
                zipFolderInto(child, prefix + child.name + "/", zip, depthLeft - 1)
            } else {
                zip.putNextEntry(ZipEntry(prefix + child.name))
                FileInputStream(child).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun uniqueZipName(used: MutableSet<String>, name: String, isFolder: Boolean): String {
        val stem = if (isFolder) name else name.substringBeforeLast('.', name)
        val tail = if (isFolder) "" else "." + name.substringAfterLast('.', "")
        val slash = if (isFolder) "/" else ""
        var candidate = name + slash
        var counter = 2
        while (candidate in used) {
            candidate = stem + " (" + counter + ")" + tail + slash
            counter++
        }
        used.add(candidate)
        return candidate
    }

    /**
     * Unzips [zipPath] into [destinationDir]. The Zip Slip guard ([zipEntryTarget]) runs over
     * EVERY entry name BEFORE the first byte is written: an archive containing a path that
     * would escape the destination is refused wholesale, so a hostile archive cannot plant a
     * file in the folder next door by naming an entry "../../Downloads/shell.sh".
     */
    @JavascriptInterface
    fun extract(zipPath: String, destinationDir: String): String {
        val zipFile = resolve(zipPath) ?: return failure("that zip is outside the storage roots")
        if (!zipFile.isFile) return failure("not a zip: " + zipFile.name)
        val destination = resolve(destinationDir)
            ?: return failure("that destination is outside the storage roots")
        if (!destination.isDirectory) return failure("not a folder: " + destination.name)

        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (zipEntryTarget(destination, entry.name) == null)
                        return failure("the archive tries to write outside the destination: " + entry.name)
                    input.closeEntry()
                }
            }
        } catch (error: Exception) {
            return failure(error.message ?: "cannot read " + zipFile.name)
        }

        // Second pass: every pre-validated file entry lands. A failure deletes what was
        // written by this pass, so a crash leaves the destination as it was, not half-filled.
        val written = mutableListOf<File>()
        return try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = zipEntryTarget(destination, entry.name) ?: continue
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                    written.add(target)
                    input.closeEntry()
                }
            }
            JSONObject().put("ok", true).put("count", written.size).toString()
        } catch (error: Exception) {
            written.reversed().forEach { it.delete() }
            failure(error.message ?: "the extraction did not finish")
        }
    }

    /**
     * Size on disk, recursive file and folder counts, readable/writable/executable bits, and
     * MD5 + SHA-256 for a single file. Hashing (and the recursive walk for a folder) runs on
     * the job runner with byte progress, because a multi-gigabyte video is the whole point of
     * this call and the page must be cancellable while it churns.
     */
    @JavascriptInterface
    fun properties(path: String): String {
        val file = resolve(path) ?: return failure("that path is outside the storage roots")
        if (!file.exists()) return failure("no longer there: " + file.name)
        return startJob { job, cancelled ->
            var bytes = 0L
            var files = 0
            var folders = 0
            if (file.isDirectory) {
                walkProperties(file, job, cancelled) { entryBytes, entryIsFolder, entryIsFile ->
                    bytes += entryBytes
                    if (entryIsFolder) folders++ else if (entryIsFile) files++
                }
            } else {
                bytes = file.length()
                files = 1
            }
            val perms = JSONObject()
                .put("readable", file.canRead())
                .put("writable", file.canWrite())
                .put("executable", file.canExecute())
            var md5: String? = null
            var sha256: String? = null
            if (file.isFile && !cancelled.get()) {
                val digests = digestFile(file, job, cancelled)
                md5 = digests["md5"]
                sha256 = digests["sha256"]
            }
            job.finish(JSONObject()
                .put("size", bytes)
                .put("files", files)
                .put("folders", folders)
                .put("directory", file.isDirectory)
                .put("permissions", perms)
                .put("md5", md5 ?: JSONObject.NULL)
                .put("sha256", sha256 ?: JSONObject.NULL)
                .put("cancelled", cancelled.get()))
        }
    }

    @JavascriptInterface
    fun propertiesStatus(token: String): String = statusOf(token, JSONObject())

    private fun walkProperties(
        dir: File,
        job: BackgroundJob,
        cancelled: AtomicBoolean,
        tally: (Long, Boolean, Boolean) -> Unit
    ) {
        if (cancelled.get()) return
        val children = dir.listFiles() ?: return
        children.forEach { child ->
            if (cancelled.get()) return@forEach
            job.scanned++
            if (child.isDirectory) {
                tally(0L, true, false)
                walkProperties(child, job, cancelled, tally)
            } else {
                tally(child.length(), false, true)
            }
        }
    }

    private fun digestFile(file: File, job: BackgroundJob, cancelled: AtomicBoolean): Map<String, String> {
        val md5 = MessageDigest.getInstance("MD5")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DIGEST_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (cancelled.get()) return emptyMap()
                md5.update(buffer, 0, read)
                sha256.update(buffer, 0, read)
                job.addProgress(read.toLong())
            }
        }
        return mapOf(
            "md5" to toHex(md5.digest()),
            "sha256" to toHex(sha256.digest())
        )
    }

    private fun toHex(digest: ByteArray): String =
        digest.map { byte -> String.format("%02x", byte.toInt() and 0xFF) }.joinToString("")

    /**
     * Previews a bulk rename without renaming anything: every selected path is expanded
     * against [pattern] and every target name is validated up front. The page shows the
     * plan first; [bulkRename] then applies the exact same validated plan, and rolls the
     * renames back if one fails — so a pattern that collides with itself is never half-applied.
     */
    @JavascriptInterface
    fun bulkRenamePreview(pathsJson: String, pattern: String): String {
        val plan = buildRenamePlan(pathsJson, pattern) ?: return failure("no names to rename")
        val out = JSONArray()
        plan.forEach { (path, oldName, newName, reason) ->
            out.put(JSONObject()
                .put("path", path)
                .put("oldName", oldName)
                .put("newName", newName)
                .put("reason", reason ?: JSONObject.NULL))
        }
        return JSONObject().put("ok", true).put("plan", out).toString()
    }

    /**
     * Applies a validated bulk rename. No half-apply: the targets came from
     * [bulkRenamePreview]'s validation, and a failed rename rolls every earlier one back.
     */
    @JavascriptInterface
    fun bulkRename(pathsJson: String, pattern: String): String {
        val plan = buildRenamePlan(pathsJson, pattern) ?: return failure("no names to rename")
        val blocked = plan.any { it.reason != null && it.oldName != it.newName }
        if (blocked) {
            return failure("cannot rename: the preview found name conflicts")
        }

        val applied = mutableListOf<Pair<File, String>>() // file to its ORIGINAL name
        for (step in plan) {
            // A name that does not change is a no-op, not an error; the preview says so.
            if (step.oldName == step.newName) continue
            val old = File(step.path)
            val target = File(old.parentFile, step.newName)
            if (!old.renameTo(target)) {
                // Roll back what was already done so the selection is not left half renamed.
                applied.reversed().forEach { (renamed, original) ->
                    renamed.renameTo(File(renamed.parentFile, original))
                }
                return failure("could not rename " + old.name + " — every change has been rolled back")
            }
            applied.add(old to step.oldName)
        }
        return JSONObject().put("ok", true).put("renamed", applied.size).toString()
    }

    /** Expands every path against [pattern] and validates every target name; null when the
     *  selection is empty. The returned records carry the ORIGINAL path for the apply pass. */
    private fun buildRenamePlan(pathsJson: String, pattern: String):
        MutableList<RenamePlan>? {
        val files = mutableListOf<File>()
        eachPath(pathsJson) { files.add(it) }
        if (files.isEmpty()) return null

        val usedTargets = mutableSetOf<String>()
        val plan = mutableListOf<RenamePlan>()
        for (index in files.indices) {
            val file = files[index]
            val number = index + 1
            val name = file.nameWithoutExtension
            val extension = file.extension
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(file.lastModified()))
            val newName = pattern
                .replace("{name}", name)
                .replace("{n}", number.toString())
                .replace("{ext}", extension)
                .replace("{date}", date)

            val reason = sanitize(newName)?.let { candidate ->
                val target = File(file.parentFile, candidate)
                when {
                    candidate == file.name -> "the name does not change"
                    target.exists() -> "that name already exists on disk"
                    candidate in usedTargets -> "two files would get the same name"
                    else -> null
                }
            } ?: "the pattern produced an empty name"

            // A rename onto its own name is not a conflict; it is a no-op that is simply skipped.
            if (newName == file.name) {
                plan.add(RenamePlan(file.absolutePath, file.name, newName, "the name does not change"))
            } else {
                usedTargets.add(newName)
                plan.add(RenamePlan(file.absolutePath, file.name, newName, reason))
            }
        }
        return plan
    }

    private data class RenamePlan(val path: String, val oldName: String, val newName: String, val reason: String?)

    /**
     * Finds duplicate files under [root] by hashing only inside size buckets of two or more —
     * the cheap check first, so a camera roll with a few thousand unique pictures never hashes
     * every one of them. Reports groups of paths whose bytes are identical.
     */
    @JavascriptInterface
    fun duplicates(root: String): String {
        val dir = resolve(root) ?: return failure("that path is outside the storage roots")
        if (!dir.isDirectory) return failure("not a folder: " + dir.name)
        return startJob { job, cancelled ->
            // Bucket by size. Only the 2+ buckets are hashed.
            val bySize = HashMap<Long, MutableList<File>>()
            walkDuplicates(dir, job, cancelled) { file ->
                bySize.getOrPut(file.length()) { mutableListOf() }.add(file)
            }
            val groups = JSONArray()
            bySize.values
                .filter { it.size > 1 }
                .forEach { bucket ->
                    if (cancelled.get()) return@forEach
                    val byHash = HashMap<String, MutableList<String>>()
                    bucket.forEach { file ->
                        if (cancelled.get()) return@forEach
                        val digest = sha256Of(file)
                        byHash.getOrPut(digest) { mutableListOf() }.add(file.absolutePath)
                        job.scanned++
                    }
                    byHash.values
                        .filter { it.size > 1 }
                        .forEach { paths ->
                            val pathArray = JSONArray()
                            paths.forEach { pathArray.put(it) }
                            groups.put(JSONObject()
                                .put("size", bucket.first().length())
                                .put("count", paths.size)
                                .put("paths", pathArray))
                        }
                }
            job.finish(JSONObject().put("groups", groups).put("cancelled", cancelled.get()))
        }
    }

    @JavascriptInterface
    fun duplicatesStatus(token: String): String = statusOf(token, JSONObject())

    private fun walkDuplicates(dir: File, job: BackgroundJob, cancelled: AtomicBoolean, take: (File) -> Unit) {
        if (cancelled.get()) return
        val children = dir.listFiles() ?: return
        children.forEach { child ->
            if (cancelled.get()) return@forEach
            job.scanned++
            if (child.isDirectory) walkDuplicates(child, job, cancelled, take)
            else take(child)
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DIGEST_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
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

    // ── the Sync tab's declarative lists (task #457c) ────────────────────────
    // The Rclone subpage's remotes and transfer jobs, the Mounted subpage's
    // fleet mesh mounts, and the Git subpage's repository family. Same carrier
    // and same contract as [connections] and [mirrorJobs]: the arrays are baked
    // in at build time from data/*.json and handed over untouched, so adding a
    // field to a data file needs no change here. None of these files carries a
    // credential, and the page renders whatever the carrier says — an empty
    // list is rendered with its specific reason, never as a silent blank.

    @JavascriptInterface
    fun rcloneRemotes(): String =
        if (BuildConfig.RCLONE_REMOTES_B64.isEmpty()) "[]"
        else try {
            String(android.util.Base64.decode(BuildConfig.RCLONE_REMOTES_B64, android.util.Base64.DEFAULT))
        } catch (error: Exception) {
            "[]"
        }

    @JavascriptInterface
    fun rcloneJobs(): String =
        if (BuildConfig.RCLONE_JOBS_B64.isEmpty()) "[]"
        else try {
            String(android.util.Base64.decode(BuildConfig.RCLONE_JOBS_B64, android.util.Base64.DEFAULT))
        } catch (error: Exception) {
            "[]"
        }

    @JavascriptInterface
    fun driveMounts(): String =
        if (BuildConfig.DRIVE_MOUNTS_B64.isEmpty()) "[]"
        else try {
            String(android.util.Base64.decode(BuildConfig.DRIVE_MOUNTS_B64, android.util.Base64.DEFAULT))
        } catch (error: Exception) {
            "[]"
        }

    @JavascriptInterface
    fun gitRepos(): String =
        if (BuildConfig.GIT_REPOS_B64.isEmpty()) "{}"
        else try {
            String(android.util.Base64.decode(BuildConfig.GIT_REPOS_B64, android.util.Base64.DEFAULT))
        } catch (error: Exception) {
            "{}"
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

    private class BackgroundJob {
        val cancelRequested = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        @Volatile var done = false
        @Volatile var scanned = 0L
        @Volatile var bytesProgress = 0L

        /** Adds progress and reports whether the caller should stop. */
        fun shouldStop(): Boolean {
            if (!cancelRequested.get()) return false
            cancelled.set(true)
            return true
        }

        fun addProgress(bytes: Long) { bytesProgress += bytes }

        fun finish(result: JSONObject) {
            snapshot = JSONObject(result.toString())
                .put("ok", true)
                .put("done", true)
                .put("cancelled", cancelRequested.get() || cancelled.get())
                .put("scanned", scanned)
            done = true
        }

        @Volatile var snapshot: JSONObject? = null
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

    // ── the typed scan actions (task #459/#460) ─────────────────────────────

    /** The browser for a decoded URL. Refuses anything that is not http(s). */
    @JavascriptInterface
    fun openUrl(url: String): String {
        val parsed = try {
            Uri.parse(url.trim())
        } catch (error: Exception) {
            return failure("that payload is not a usable address")
        }
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            return failure("only web addresses open here")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, parsed)
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            okErr(true, "")
        } catch (error: Exception) {
            failure("no browser opened: " + (error.message ?: ""))
        }
    }

    /**
     * Joins a WiFi network from a WIFI: payload. Android 10+ refuses to let an
     * app add a network silently, so this adds a SUGGESTION and the user
     * approves it in the system panel Android presents — the same interaction
     * every other scanner app has. Below Android 10 the legacy addNetwork path
     * still works and joins directly.
     */
    @JavascriptInterface
    fun joinWifi(ssid: String, password: String): String {
        if (ssid.isBlank()) return failure("the WiFi payload carries no network name")
        return try {
            val manager = ctx.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val builder = android.net.wifi.WifiNetworkSuggestion.Builder().setSsid(ssid)
                if (password.isNotEmpty()) builder.setWpa2Passphrase(password)
                val status = manager.addNetworkSuggestions(listOf(builder.build()))
                when (status) {
                    android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
                        okErr(true, "Android is asking you to approve the network")
                    android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
                        okErr(true, "the network is already suggested to Android")
                    else -> okErr(false, "Android declined the WiFi suggestion")
                }
            } else {
                val configuration = android.net.wifi.WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (password.isEmpty()) {
                        allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                    } else {
                        allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                        preSharedKey = "\"$password\""
                    }
                }
                val networkId = manager.addNetwork(configuration)
                if (networkId == -1) {
                    okErr(false, "Android could not add the network")
                } else {
                    manager.enableNetwork(networkId, true)
                    okErr(true, "Connected to " + ssid)
                }
            }
        } catch (error: Exception) {
            failure("cannot join the network: " + (error.message ?: error.javaClass.simpleName))
        }
    }

    /** Opens the system contact-insert screen with the vCard's name (and number when it has one). */
    @JavascriptInterface
    fun addContact(vcard: String, name: String): String {
        // Insert is a Java constant-holder class, not an object — it can only be a
        // qualifier, never a value. Binding it to a `val` made every field below
        // unresolvable and took the whole module's compile down with it.
        val intent = Intent(Insert.ACTION)
        if (name.isNotBlank()) intent.putExtra(Insert.NAME, name)
        val telephone = vcard.lineSequence()
            .firstOrNull { it.trim().startsWith("TEL", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        if (!telephone.isNullOrEmpty()) intent.putExtra(Insert.PHONE, telephone)
        return try {
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            okErr(true, "")
        } catch (error: Exception) {
            failure("no contacts app opened: " + (error.message ?: ""))
        }
    }

    /** Opens the system calendar's new-event screen pre-filled from a VEVENT payload. */
    @JavascriptInterface
    fun addCalendarEvent(summary: String, location: String, start: Long, end: Long): String {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(android.provider.CalendarContract.Events.CONTENT_URI)
        if (summary.isNotBlank()) {
            intent.putExtra(android.provider.CalendarContract.Events.TITLE, summary)
        }
        if (location.isNotBlank()) {
            intent.putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
        }
        if (start > 0) {
            intent.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            if (end > start) {
                intent.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end)
            }
        }
        return try {
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            okErr(true, "")
        } catch (error: Exception) {
            failure("no calendar app opened: " + (error.message ?: ""))
        }
    }

    /** The dialer, pre-filled with a tel: payload. */
    @JavascriptInterface
    fun dial(number: String): String {
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            okErr(true, "")
        } catch (error: Exception) {
            failure("no dialer opened: " + (error.message ?: ""))
        }
    }

    /** The mail app, pre-filled from a mailto: payload. */
    @JavascriptInterface
    fun sendEmail(address: String, subject: String, body: String): String {
        return try {
            val intent = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("mailto:" + Uri.encode(address))
            )
            if (subject.isNotBlank()) intent.putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT, body)
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            okErr(true, "")
        } catch (error: Exception) {
            failure("no mail app opened: " + (error.message ?: ""))
        }
    }

    /** The map app, centred on a geo: payload's coordinates. */
    @JavascriptInterface
    fun openGeo(latitude: Double, longitude: Double): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude"))
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            okErr(true, "")
        } catch (error: Exception) {
            failure("no map app opened: " + (error.message ?: ""))
        }
    }

    /** Copies text into the system clipboard. Native, so it works from every WebView context. */
    @JavascriptInterface
    fun copyText(text: String): String {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cloud-drive", text))
        return okErr(true, "")
    }

    /** Shares plain text (an OCR result) through the system sheet. */
    @JavascriptInterface
    fun shareText(title: String, text: String): String {
        return try {
            val intent = Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, title)
                    .putExtra(Intent.EXTRA_TEXT, text),
                null
            )
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            okErr(true, "")
        } catch (error: Exception) {
            failure("nothing to share into: " + (error.message ?: ""))
        }
    }

    /**
     * Saves an OCR result as .txt or .md NEXT TO the image it came from. The
     * name is the image's base name plus the extension; a clash becomes
     * "name (2).md" rather than overwriting what is there.
     */
    @JavascriptInterface
    fun saveTextBeside(imagePath: String, text: String, extension: String): String {
        val image = resolve(imagePath) ?: return failure("that path is outside the storage roots")
        if (!image.isFile) return failure("not a file: " + image.name)
        val safeExtension = if (extension == "md") "md" else "txt"
        val base = image.name.substringBeforeLast('.', image.name)
        val parent = image.parentFile ?: return failure("no folder to write into")
        val target = uniqueIn(parent, base + "." + safeExtension)
        return try {
            target.writeText(text, Charsets.UTF_8)
            pathResult(target)
        } catch (error: Exception) {
            failure("could not save " + target.name + ": " + (error.message ?: ""))
        }
    }

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

    // ── the image viewer and the shared scan engine (task #459/#460) ────────────

    /**
     * A small JPEG thumbnail as a data: URL for an image row in the Files tab.
     * The page renders the row first and fills the <img> lazily, so a slow disk
     * must never stall the list. Returns null for anything that is not an image
     * the platform can decode; the page then shows the kind glyph as before.
     */
    @JavascriptInterface
    fun imageThumb(path: String, maxDimension: Int): String? {
        val file = resolve(path) ?: return null
        val max = maxDimension.coerceIn(64, 1024)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= max) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        try {
            val scale = max.toFloat() / maxOf(decoded.width, decoded.height)
            if (scale < 1f) {
                val scaled = android.graphics.Bitmap.createScaledBitmap(
                    decoded,
                    maxOf(1, (decoded.width * scale).toInt()),
                    maxOf(1, (decoded.height * scale).toInt()),
                    true
                )
                if (scaled !== decoded) decoded.recycle()
                return jpegDataUrl(scaled)
            }
            return jpegDataUrl(decoded)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    /**
     * The EXIF panel's facts for one image: dimensions, capture date, camera
     * make/model and GPS when the file carries them. Read from the platform
     * ExifInterface, so no extra dependency rides the APK for a viewer.
     */
    @JavascriptInterface
    fun imageInfo(path: String): String {
        val file = resolve(path) ?: return failure("the image is outside the storage roots")
        val json = JSONObject().put("ok", true)
        val exif = try {
            android.media.ExifInterface(file.absolutePath)
        } catch (error: Exception) {
            return json.put("error", "no readable EXIF in this file").toString()
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth > 0) {
            json.put("width", bounds.outWidth).put("height", bounds.outHeight)
        }
        exif.getAttribute(android.media.ExifInterface.TAG_DATETIME)?.takeIf { it.isNotBlank() }?.let { json.put("date", it) }
        exif.getAttribute(android.media.ExifInterface.TAG_MAKE)?.takeIf { it.isNotBlank() }?.let { json.put("make", it) }
        exif.getAttribute(android.media.ExifInterface.TAG_MODEL)?.takeIf { it.isNotBlank() }?.let { json.put("model", it) }
        val latitude = exifLatitude(exif)
        val longitude = exifLongitude(exif)
        if (latitude != null && longitude != null) {
            json.put("latitude", latitude).put("longitude", longitude)
        }
        json.put("size", file.length())
        json.put("mime", android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "image/*")
        return json.toString()
    }

    /**
     * Rotates the image 90 degrees clockwise by rewriting its EXIF orientation
     * tag. The pixels are left untouched — re-encoding a photo loses quality
     * and strips metadata, while every Android decoder already applies the tag.
     */
    @JavascriptInterface
    fun rotateImage(path: String): String {
        val file = resolve(path) ?: return failure("the image is outside the storage roots")
        return try {
            val exif = android.media.ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val next = when (orientation) {
                android.media.ExifInterface.ORIENTATION_NORMAL -> android.media.ExifInterface.ORIENTATION_ROTATE_90
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> android.media.ExifInterface.ORIENTATION_NORMAL
                else -> android.media.ExifInterface.ORIENTATION_NORMAL
            }
            exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, next.toString())
            exif.saveAttributes()
            okErr(true, "")
        } catch (error: Exception) {
            failure("cannot rotate: " + (error.message ?: error.javaClass.simpleName))
        }
    }

    /**
     * Decodes the first QR/barcode in an image and returns it as a TYPED
     * payload ({type, raw, ...fields}) so the page can render actions
     * (open URL, join WiFi, add contact, add calendar event) instead of a raw
     * string dump. Both consumer apps call the SAME shared engine
     * (libs:ml-l-image-mlkit); this method is cloud-drive's surface over it.
     */
    @JavascriptInterface
    fun decodeBarcode(path: String): String {
        val file = resolve(path) ?: return failure("the image is outside the storage roots")
        val scan = scanEngine.decodeBarcode(file)
            ?: return okErr(false, "no barcode found in this image")
        val json = JSONObject()
            .put("ok", true)
            .put("format", scan.format)
            .put("raw", scan.rawValue)
        putPayload(json, scan.payload)
        return json.toString()
    }

    /**
     * OCRs an image through the shared engine and returns {text, segments[..]}.
     * The page offers select/copy/share and save-as-.txt/.md beside the image;
     * this bridge returns the text, the page composes the file name.
     */
    @JavascriptInterface
    fun recognizeText(path: String): String {
        val file = resolve(path) ?: return failure("the image is outside the storage roots")
        val result = scanEngine.recognizeText(file)
        // Bound to a local: `result.error` is public API in another module, so Kotlin
        // will not smart-cast it to non-null across the guard.
        val scanError = result.error
        if (scanError != null) return okErr(false, scanError)
        val segments = JSONArray()
        result.segments.forEach { segment ->
            val item = JSONObject().put("text", segment.text)
            segment.confidence?.let { item.put("confidence", it.toDouble()) }
            segments.put(item)
        }
        return JSONObject()
            .put("ok", true)
            .put("text", result.text)
            .put("segments", segments)
            .toString()
    }

    /** Puts the typed payload's fields on [json] under one `payload` object. */
    private fun putPayload(json: JSONObject, payload: BarcodePayload) {
        val payloadJson = JSONObject()
        when (payload) {
            is BarcodePayload.Url -> payloadJson.put("type", "url").put("url", payload.url)
            is BarcodePayload.Wifi -> payloadJson
                .put("type", "wifi")
                .put("ssid", payload.ssid)
                .put("password", payload.password)
                .put("security", payload.security)
                .put("hidden", payload.hidden)
            is BarcodePayload.Contact -> payloadJson
                .put("type", "contact")
                .put("name", payload.name ?: "")
                .put("vcard", payload.vcard)
            is BarcodePayload.Calendar -> payloadJson
                .put("type", "calendar")
                .put("summary", payload.summary ?: "")
                .put("location", payload.location ?: "")
                .put("start", payload.startTimeEpochMillis ?: JSONObject.NULL)
                .put("end", payload.endTimeEpochMillis ?: JSONObject.NULL)
                .put("vevent", payload.vevent)
            is BarcodePayload.Phone -> payloadJson.put("type", "phone").put("number", payload.number)
            is BarcodePayload.Email -> payloadJson
                .put("type", "email")
                .put("address", payload.address)
                .put("subject", payload.subject ?: "")
                .put("body", payload.body ?: "")
            is BarcodePayload.Geo -> payloadJson
                .put("type", "geo")
                .put("latitude", payload.latitude)
                .put("longitude", payload.longitude)
            is BarcodePayload.Plain -> payloadJson.put("type", "text").put("text", payload.text)
        }
        json.put("payload", payloadJson)
    }

    private fun jpegDataUrl(bitmap: android.graphics.Bitmap): String {
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, output)
        return "data:image/jpeg;base64," +
            android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun exifLatitude(exif: android.media.ExifInterface): Double? {
        val value = exif.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE) ?: return null
        val reference = exif.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF) ?: return null
        return dmsToDecimal(value)?.let { if (reference == "S") -it else it }
    }

    private fun exifLongitude(exif: android.media.ExifInterface): Double? {
        val value = exif.getAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE) ?: return null
        val reference = exif.getAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF) ?: return null
        return dmsToDecimal(value)?.let { if (reference == "W") -it else it }
    }

    /** "37/1 25/1 123/100" (DMS rationals) -> decimal degrees, or null. */
    private fun dmsToDecimal(dms: String): Double? {
        val parts = dms.split(',').mapNotNull { part ->
            val slash = part.trim().split('/')
            val numerator = slash[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val denominator = if (slash.size > 1) slash[1].trim().toDoubleOrNull() ?: 1.0 else 1.0
            if (denominator == 0.0) null else numerator / denominator
        }
        if (parts.size < 3) return null
        return parts[0] + parts[1] / 60.0 + parts[2] / 3600.0
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

/** The five bytes every PDF starts with: "%PDF-". */
    private fun hasPdfMagic(bytes: ByteArray): Boolean =
        bytes.size >= 5 &&
            bytes[0] == 0x25.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() &&
            bytes[3] == 0x46.toByte() &&
            bytes[4] == 0x2D.toByte()

    /** The total bytes the selection would occupy at [destination] — every file, whole tree. */
    private fun measureTotalSize(pathsJson: String): Long? {
        var total = 0L
        eachPath(pathsJson) { source ->
            if (source.isFile) total += source.length()
            else if (source.isDirectory) total += folderSize(source, SIZE_WALK_DEPTH_CEILING)
        }
        return total
    }

    private fun folderSize(dir: File, depthLeft: Int): Long {
        if (depthLeft <= 0) return 0L
        var total = 0L
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) total += folderSize(child, depthLeft - 1)
            else total += child.length()
        }
        return total
    }

    private fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return bytes.toString() + " B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024.0 && unit < units.size - 1) { value /= 1024.0; unit++ }
        return "%.1f".format(value) + " " + units[unit]
    }

    private fun pathResult(file: File): String =
        JSONObject().put("ok", true).put("path", file.absolutePath).put("name", file.name).toString()

    private fun failure(reason: String): String = okErr(false, reason)

    private fun okErr(ok: Boolean, error: String): String =
        JSONObject().put("ok", ok).put("error", error).toString()

    // ── the PDF reader ────────────────────────────────────────────────────────

    /**
     * Hands the page a PDF's bytes as base64, the one carrier that works here.
     * pdf.js must never be given a file:// URL: Chromium blocks fetch() on
     * file:// origins by CORS policy (the fleet hit this in ac_cloud-nav and
     * documented it), so the document travels as data and pdf.js opens it from
     * a Uint8Array — no network layer is ever exercised.
     *
     * The ceiling exists for the same reason the editor has one: the string
     * the bridge returns is held whole by the WebView's JavaScript engine, so
     * a multi-hundred-megabyte scan would be an out-of-memory kill rather than
     * a slow reader. 24 MB covers every document on a phone and stays far
     * below the JS heap cliff.
     */
    @JavascriptInterface
    fun readPdf(path: String): String {
        val file = resolve(path) ?: return failure("that path is outside the storage roots")
        if (!file.isFile) return failure("not a file: " + file.name)
        if (file.length() > PDF_READER_BYTE_CEILING)
            return failure(file.name + " is too large to read here (over " +
                (PDF_READER_BYTE_CEILING / 1024 / 1024) + " MB)")
        return try {
            val bytes = file.readBytes()
            // A file that does not start with the PDF magic is either an HTML
            // error page saved with a .pdf name or a truncated download; pdf.js
            // would produce its least useful failure (a blank canvas) for it.
            if (!hasPdfMagic(bytes))
                return failure(file.name + " does not look like a PDF")
            JSONObject()
                .put("ok", true)
                .put("path", file.absolutePath)
                .put("name", file.name)
                .put("size", bytes.size)
                .put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                .toString()
        } catch (error: Exception) {
            failure(error.message ?: "cannot read " + file.name)
        }
    }

    /** A PDF another app handed over through the open-with intent, null until set. */
    private var incomingPdf: Uri? = null

    /**
     * Receives the content:// (or legacy file://) URI from MainActivity's
     * onNewIntent. The bridge holds it so the activity does not need to mint a
     * path from it — a content:// URI can only ever be read through the
     * ContentResolver, and turning it into a path is exactly the bug this
     * method exists to keep out of the codebase.
     */
    fun setIncomingPdf(uri: Uri) {
        incomingPdf = uri
    }

    /**
     * The page drains the hand-off URI once, when it first loads or when a
     * singleTask second launch lands on an already-running activity. Reading
     * happens on demand so a tap that arrives before the page exists is not
     * lost — the URI is stored, the page asks later.
     */
    @JavascriptInterface
    fun takeIncomingPdf(): String {
        val uri = incomingPdf ?: return failure("no incoming document")
        incomingPdf = null
        return try {
            val bytes = if (uri.scheme == "content") {
                // The system grants the receiving app a read lease on the URI
                // for the lifetime of the intent; ContentResolver is the only
                // sanctioned way to exercise it.
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return failure("the shared PDF cannot be opened")
            } else {
                // A legacy file:// hand-off has no resolver entry — read the
                // path the URI names (it is a content-less scheme by design).
                java.io.File(uri.path ?: "").takeIf { it.isFile }?.readBytes()
                    ?: return failure("the shared PDF cannot be opened")
            }
            if (bytes.size.toLong() > PDF_READER_BYTE_CEILING)
                return failure("the shared PDF is too large to read here (over " +
                    (PDF_READER_BYTE_CEILING / 1024 / 1024) + " MB)")
            if (!hasPdfMagic(bytes))
                return failure("the shared file does not look like a PDF")
            JSONObject()
                .put("ok", true)
                .put("name", uri.lastPathSegment ?: "document.pdf")
                .put("size", bytes.size)
                .put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                .toString()
        } catch (error: Exception) {
            failure(error.message ?: "cannot read the shared PDF")
        }
    }

    private companion object {
        /** The single pool every background job (search, properties, duplicates) runs on. One
         *  thread is deliberately enough: these jobs all read, and serialising them keeps the
         *  phone from thrashing a camera roll with three competing walks. */
        val JOB_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable).apply { isDaemon = true }
        }

        /**
         * The largest file the editor will open. The page holds the whole text as one
         * JavaScript string, so this is a memory ceiling rather than a taste judgement:
         * every plain-text file a phone actually holds is far below it, and everything
         * above it is a log or a database that an editor has no business loading whole.
         */
        const val EDITABLE_BYTE_CEILING = 2L * 1024 * 1024

        /**
         * The largest PDF the reader will open. The page holds the document as
         * one base64 string and one Uint8Array, so — exactly like the editor's
         * ceiling — this is a JavaScript-heap ceiling, not a taste judgement.
         */
        const val PDF_READER_BYTE_CEILING = 24L * 1024 * 1024

        /** How deep [mirror] will walk. See the ponytail note in mirrorInto. */
        const val MIRROR_DEPTH_CEILING = 32

        /** Search reads a text file up to this ceiling; the page must not OOM opening it. */
        const val SEARCH_TEXT_BYTE_CEILING = 1L * 1024 * 1024

        /** Search stops here, and says it truncated. See the search doc comment. */
        const val SEARCH_RESULT_CEILING = 300

        /** How deep a zip walk goes before giving up; mirror's ceiling shape, same ponytail. */
        const val ARCHIVE_DEPTH_CEILING = 32

        /** How deep transfer's free-space estimate walks a source tree. */
        const val SIZE_WALK_DEPTH_CEILING = 32

        /** Chunk for hashing and zipping; 1 MB keeps one read cheap without stalling progress. */
        const val DIGEST_BUFFER_SIZE = 1 * 1024 * 1024

        val KEY_TREE_GRANT_URI = "saf_tree_grant_uri"
        val KEY_TREE_GRANT_NAME = "saf_tree_grant_name"
    }
}

/**
 * The Zip Slip guard, as a pure function so the shell tester and any future JVM unit test can
 * hold it to account. Returns the file an entry may be written to, or null when the entry name
 * would escape [destination] — anything absolute, anything containing a ".." segment, or a
 * canonical path outside the destination's canonical root. The page cannot hand this function
 * a crafted name that climbs out: every segment is checked, backslashes are read as separators
 * (a zip created on Windows may use them), and the final canonical path is compared against the
 * destination's own canonical path plus the separator, exactly like [FilesBridge.resolve].
 */
internal fun zipEntryTarget(destination: File, entryName: String): File? {
    val cleaned = entryName.replace('\\', '/')
    if (cleaned.startsWith("/")) return null
    val segments = cleaned.split("/").filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) return null
    if (segments.isEmpty()) return null
    val target = File(destination, segments.joinToString("/"))
    val destCanonical = try { destination.canonicalFile } catch (error: Exception) { return null }
    val targetCanonical = try { target.canonicalFile } catch (error: Exception) { return null }
    val allowed = destCanonical == targetCanonical ||
        targetCanonical.path.startsWith(destCanonical.path + File.separator)
    return if (allowed) targetCanonical else null
}
