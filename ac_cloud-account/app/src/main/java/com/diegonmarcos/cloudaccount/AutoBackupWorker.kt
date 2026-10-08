package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.profile.AccountDevice
import com.diegonmarcos.superapp.profile.AutoBackup
import com.diegonmarcos.superapp.profile.DeviceVault
import java.util.concurrent.TimeUnit

/**
 * Settings ▸ Auto-backup (spec 4.11): once a day (on Wi-Fi only when so chosen), capture this
 * phone and hand it to DeviceVault.backup, which commits nothing when only captured_at would
 * change. Off cancels the job. Re-armed from App.onCreate and whenever Settings changes it.
 */
class AutoBackupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val id = AccountDevice.id(applicationContext)
        if (id.isBlank()) return Result.success()   // no device picked: nothing to back up to
        val r = runCatching { DeviceVault(applicationContext).backup(id, false) }.getOrNull() ?: return Result.retry()
        return if (r.optBoolean("ok")) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "cloud-account-auto-backup"

        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            when (val mode = AutoBackup.mode(ctx)) {
                AutoBackup.OFF -> wm.cancelUniqueWork(NAME)
                else -> {
                    val net = if (mode == AutoBackup.WIFI) NetworkType.UNMETERED else NetworkType.CONNECTED
                    val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
                        .build()
                    wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
                }
            }
        }
    }
}
