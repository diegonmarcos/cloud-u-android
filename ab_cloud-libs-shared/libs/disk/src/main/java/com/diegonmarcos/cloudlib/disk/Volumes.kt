package com.diegonmarcos.cloudlib.disk

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

/**
 * The phone's volumes: internal shared storage, every SD card / USB drive the StorageManager
 * reports as mounted, and the app-data partition (StorageStatsManager's totals for the default
 * UUID — the number Settings ▸ Storage shows). Used/free are StatFs facts of the mount, never an
 * estimate.
 */
object Volumes {
    data class Volume(
        val id: String,
        val label: String,
        val kind: String,
        val path: String?,
        val totalBytes: Long,
        val freeBytes: Long,
    ) {
        val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    }

    const val KIND_INTERNAL = "internal"
    const val KIND_REMOVABLE = "removable"
    const val KIND_APP_DATA = "app_data"

    fun list(ctx: Context): List<Volume> {
        val out = ArrayList<Volume>()
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val primary = Environment.getExternalStorageDirectory()
        stat(primary)?.let { (total, free) -> out += Volume("primary", "Internal storage", KIND_INTERNAL, primary.absolutePath, total, free) }
        for (v in runCatching { sm.storageVolumes }.getOrDefault(emptyList())) {
            if (v.isPrimary || v.state != Environment.MEDIA_MOUNTED) continue
            val dir = dirOf(ctx, v) ?: continue
            val (total, free) = stat(dir) ?: continue
            out += Volume(v.uuid ?: dir.name, v.getDescription(ctx), KIND_REMOVABLE, dir.absolutePath, total, free)
        }
        runCatching {
            val ss = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            out += Volume("app_data", "App data partition", KIND_APP_DATA, null,
                ss.getTotalBytes(StorageManager.UUID_DEFAULT), ss.getFreeBytes(StorageManager.UUID_DEFAULT))
        }
        return out
    }

    private fun dirOf(ctx: Context, v: android.os.storage.StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory
        else v.uuid?.let { File("/storage/$it") }?.takeIf { it.isDirectory }

    fun stat(dir: File): Pair<Long, Long>? =
        runCatching { StatFs(dir.absolutePath).let { it.totalBytes to it.availableBytes } }.getOrNull()
}
