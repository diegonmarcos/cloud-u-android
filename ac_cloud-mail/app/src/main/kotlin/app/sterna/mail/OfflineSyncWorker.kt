package app.sterna.mail

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sterna.container
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Keeps every message inside the "Keep mail offline for" window readable offline: downloads full
 * bodies (and attachments up to the size setting) for each account, then evicts what fell out of
 * the window. Periodic on unmetered + charging, plus one immediate run when the app opens online.
 */
class OfflineSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as Application).container
        val settings = container.settingsRepository
        val window = settings.offlineWindow.first()
        val limit = settings.offlineAttachmentLimit.first()
        var ok = true
        for (credentials in container.accountStore.allCredentials()) {
            runCatching { container.mailRepository.syncOffline(credentials, window, limit) }
                .onSuccess { Log.i(TAG, "offline sync ${credentials.id}: fetched=${it.fetched} evicted=${it.evicted}") }
                .onFailure { ok = false; Log.w(TAG, "offline sync failed for ${credentials.id}", it) }
        }
        if (ok) settings.setOfflineLastSync(System.currentTimeMillis())
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "OfflineSyncWorker"
        private const val PERIODIC = "offline-sync-periodic"
        private const val NOW = "offline-sync-now"

        /** Idempotent periodic schedule: unmetered network and charging. */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** One run as soon as there is a network (app open, or the window setting changed). */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
