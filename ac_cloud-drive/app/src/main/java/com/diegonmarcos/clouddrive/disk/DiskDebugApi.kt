package com.diegonmarcos.clouddrive.disk

import android.content.Context
import com.diegonmarcos.cloudlib.disk.DiskContract
import com.diegonmarcos.superapp.devtools.AppDebugServer

/**
 * #813 /api/disk/<op> — the Disk Management engine, readable from the phone's own shell with the
 * screen locked. Registered on the fleet's AppDebugServer (loopback, fleet Bearer on every route)
 * by DriveDebugApi.register; no second server. Every answer is DriveDisk's engine — the page's
 * own — and carries `contract` (DiskContract.VERSION). The one destructive op, clean, deletes
 * only with run=1&confirm=1, and its answer reports the dry run beside the bytes reclaimed.
 */
object DiskDebugApi {
    fun register(app: Context) {
        AppDebugServer.route(
            DiskContract.GROUP,
            listOf(
                AppDebugServer.Op(DiskContract.OP_VOLUMES, "", "internal, SD/USB and app-data volumes: total/used/free bytes"),
                AppDebugServer.Op(DiskContract.OP_MAP, "rescan=1 (optional)", "storage map of shared storage by top-level folder"),
                AppDebugServer.Op(DiskContract.OP_HUGE, "threshold=<bytes|K|M|G> (default 100M)&top=N", "files at or above the threshold, largest first"),
                AppDebugServer.Op(DiskContract.OP_DUPLICATES, "min=<bytes|K|M|G> (default 1M)&top=N", "duplicate groups (size, partial hash, full hash) and the bytes keeping one copy frees"),
                AppDebugServer.Op(DiskContract.OP_APPS, "pkgs=a,b (default: the fleet)", "per-app APK/data/cache bytes (StorageStatsManager) and whether usage access is granted"),
                AppDebugServer.Op(DiskContract.OP_CLEAN, "run=1&confirm=1 to delete (default: dry run)", "cache/temp clean: dry-run bytes by source; with run, the bytes reclaimed"),
                AppDebugServer.Op(DiskContract.OP_MEMORY, "", "RAM total/available and per-process PSS where readable"),
            ),
        ) { op, query -> DriveDisk.engine(app).json(op, query)?.toString() }
    }
}
