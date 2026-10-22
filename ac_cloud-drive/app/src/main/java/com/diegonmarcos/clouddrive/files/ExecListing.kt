package com.diegonmarcos.clouddrive.files

import android.content.Context
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import com.diegonmarcos.cloudlib.sharedstore.StoreExec
import com.diegonmarcos.cloudlib.sharedstore.StoreExec.Companion.q
import java.io.File
import java.io.IOException

/**
 * Browsing the terminal's filesystem when this app is NOT in its uid (StoreAccess EXEC): the folder is
 * listed by the terminal itself through CLOUD_EXEC, so the Terminal roots stay browsable instead of hidden.
 * Changes go the same way ([ExecMutation]). Pure parse so the JVM suite checks it.
 */
object ExecListing {

    fun script(guestDir: String): String = "find ${q(guestDir)} -mindepth 1 -maxdepth 1 -printf '%y\\t%s\\t%T@\\t%f\\n'"

    /** Host-side twin for the terminal's private dirs outside the guest's /root (the whole data dir): same columns, type spelled by stat. */
    fun hostScript(dir: String): String =
        "T=\$(printf '\\t'); cd ${q(dir)} || { echo 'cannot enter the folder' >&2; exit 2; }; [ -r . ] || { echo 'permission denied' >&2; exit 13; }; " +
            "stat -L -c \"%F\$T%s\$T%Y\$T%n\" -- * .[!.]* ..?* 2>/dev/null; true"

    /** One line per child: type, bytes, mtime seconds, name. Unreadable lines are skipped, never fatal. */
    fun parse(dir: String, out: String, mimes: FileOps.MimeResolver, showHidden: Boolean): List<FileOps.Entry> =
        out.lineSequence().mapNotNull { line ->
            val f = line.split('\t', limit = 4)
            if (f.size < 4 || f[3].isEmpty()) return@mapNotNull null
            val isDir = f[0] == "d" || f[0] == "directory"
            val name = f[3]
            val ext = FileOps.extensionOf(name)
            FileOps.Entry(
                location = Location.Local(dir.trimEnd('/') + "/" + name), name = name, isDirectory = isDir,
                size = if (isDir) 0L else f[1].toLongOrNull() ?: 0L,
                modified = ((f[2].toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                mime = if (isDir) FileOps.DIRECTORY_MIME else (mimes.mimeOf(ext) ?: FileOps.OCTET_MIME),
                extension = ext, hidden = name.startsWith("."),
            )
        }.filter { showHidden || !it.hidden }.toList()

    @Throws(IOException::class)
    fun list(ctx: Context, dir: File, mimes: FileOps.MimeResolver, showHidden: Boolean): List<FileOps.Entry> {
        val guest = StoreAccess.paths.guestPath(dir.absolutePath, StoreAccess.userId())
        val exec = StoreExec(ctx)
        val r = if (guest != null) exec.run(script(guest), timeoutMs = 30_000)
            else if (StoreAccess.paths.inOwnerData(dir.absolutePath, StoreAccess.userId())) exec.runHost(hostScript(dir.absolutePath), timeoutMs = 30_000)
            else throw IOException("${dir.name} is not inside the terminal's data")
        if (!r.ok) throw IOException("the terminal could not list ${dir.name}: ${r.err.ifBlank { r.out }.take(160)}")
        return parse(dir.absolutePath, r.out, mimes, showHidden)
    }
}
