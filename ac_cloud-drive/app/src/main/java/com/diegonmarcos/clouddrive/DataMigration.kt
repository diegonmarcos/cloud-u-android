package com.diegonmarcos.clouddrive

import android.app.Activity
import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.MutableStateFlow
import android.os.Process
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * cloud-shared-store: cloud-drive JOINS cld.termux's uid (android:sharedUserId), which Android
 * only allows on a fresh install — uninstall wipes this app's data. So:
 *
 *  - BEFORE (any launch while NOT in the shared uid): a small backup of this app's own state
 *    (files/ and shared_prefs/, minus Android-Keystore-encrypted prefs, which can never decrypt
 *    after an uninstall — the git token comes back from the vault import) is written to
 *    <shared storage>/<storage.shared_root>/<migration.backup_dir>/<package>/data.zip.
 *  - AFTER (first launch IN the shared uid): a one-time notice offers the restore; the archive is
 *    deleted once restored, and JobPathMigration then moves every saved path to the store.
 *
 * Pure parts (what is backed up, the zip round trip) are in [Archive] and unit-tested.
 */
object DataMigration {

    private const val PREFS = "cloud-shared-store-migration"
    private const val KEY_DONE = "migrated-v1"

    fun archive(ctx: Context): File =
        File(SharedStore.legacyRoot(), "${com.diegonmarcos.cloudlib.sharedstore.BuildConfig.MIGRATION_DIR}/${ctx.packageName}/data.zip")

    private fun joined(ctx: Context): Boolean = StoreAccess.ownerUid(ctx) == Process.myUid()

    /** What the one-time notice shows; null = nothing to show. Rendered by [Dialog] (Compose). */
    data class Notice(val body: String, val zip: File?)
    private val notice = MutableStateFlow<Notice?>(null)
    private val result = MutableStateFlow<String?>(null)

    fun onLaunch(activity: Activity) {
        val ctx = activity.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!joined(ctx)) {
            Thread { runCatching { Archive.write(File(ctx.applicationInfo.dataDir), archive(ctx)) } }.start()
            return
        }
        if (prefs.getBoolean(KEY_DONE, false)) return
        prefs.edit().putBoolean(KEY_DONE, true).apply()
        val zip = archive(ctx)
        notice.value = Notice(
            activity.getString(R.string.shared_store_migration_body) +
                if (zip.isFile) activity.getString(R.string.shared_store_migration_restore, zip.absolutePath) else "",
            zip.takeIf { it.isFile },
        )
    }

    /** The one-time notice and the restore result, as Compose dialogs over the chrome. */
    @Composable
    fun Dialog() {
        val ctx = LocalContext.current
        val n by notice.collectAsState()
        val r by result.collectAsState()
        n?.let { shown ->
            AlertDialog(
                onDismissRequest = { notice.value = null },
                title = { Text(stringResource(R.string.shared_store_migration_title)) },
                text = { Text(shown.body) },
                confirmButton = {
                    if (shown.zip != null) TextButton(onClick = {
                        notice.value = null
                        Thread {
                            val app = ctx.applicationContext
                            val done = runCatching { Archive.read(shown.zip, File(app.applicationInfo.dataDir)) }
                            done.onSuccess { shown.zip.delete(); runCatching { JobPathMigration.run(app) } }
                            result.value = done.fold({ app.getString(R.string.shared_store_migration_done, it) }, { app.getString(R.string.shared_store_migration_failed, it.message ?: it.toString()) })
                        }.start()
                    }) { Text(stringResource(R.string.shared_store_migration_restore_action)) }
                    else TextButton(onClick = { notice.value = null }) { Text(stringResource(R.string.shared_store_migration_ok_action)) }
                },
                dismissButton = { if (shown.zip != null) TextButton(onClick = { notice.value = null }) { Text(stringResource(R.string.shared_store_migration_skip_action)) } },
            )
        }
        r?.let { msg ->
            AlertDialog(
                onDismissRequest = { result.value = null },
                text = { Text(msg) },
                confirmButton = { TextButton(onClick = { result.value = null; (ctx as? Activity)?.recreate() }) { Text(stringResource(R.string.shared_store_migration_ok_action)) } },
            )
        }
    }

    /** The zip round trip, no Android: files/ and shared_prefs/ under a data dir. */
    object Archive {
        private const val MAX_FILE = 4L * 1024 * 1024
        private val ROOTS = listOf("files", "shared_prefs")

        /** What is kept: small files, never Keystore-encrypted prefs, never caches. */
        fun keep(rel: String, size: Long): Boolean =
            size <= MAX_FILE && ROOTS.any { rel.startsWith("$it/") } &&
                !rel.contains("/cache/") && !rel.contains("credentials") && !rel.contains("__androidx_security") &&
                !rel.contains("safe_encrypted_prefs_reset")

        /**
         * #1106 an EncryptedSharedPreferences file by what it holds, whatever it is called: its keyset is sealed
         * by a Keystore master key that never travels, so a restored copy only throws AEADBadTagException.
         */
        fun isEncryptedPrefs(f: File): Boolean =
            f.name.endsWith(".xml") && runCatching { f.useLines { l -> l.any { "__androidx_security_crypto_encrypted_prefs" in it } } }.getOrDefault(false)

        fun write(dataDir: File, out: File): Int {
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".tmp")
            var n = 0
            ZipOutputStream(tmp.outputStream().buffered()).use { z ->
                dataDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(dataDir).invariantSeparatorsPath
                    if (!keep(rel, f.length()) || isEncryptedPrefs(f)) return@forEach
                    z.putNextEntry(ZipEntry(rel)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); n++
                }
            }
            check(tmp.renameTo(out) || (out.delete() && tmp.renameTo(out))) { "cannot write $out" }
            return n
        }

        fun read(zip: File, dataDir: File): Int {
            var n = 0
            val base = dataDir.canonicalFile
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                generateSequence { z.nextEntry }.forEach { e ->
                    val dest = File(base, e.name).canonicalFile
                    require(dest.path.startsWith(base.path + File.separator)) { "entry outside the data dir: ${e.name}" }
                    if (!keep(e.name, 0) || e.isDirectory) return@forEach
                    dest.parentFile?.mkdirs(); dest.outputStream().use { z.copyTo(it) }; n++
                }
            }
            return n
        }
    }
}
