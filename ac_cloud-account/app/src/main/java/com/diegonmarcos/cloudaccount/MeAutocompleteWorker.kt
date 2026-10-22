package com.diegonmarcos.cloudaccount

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.profile.MeAutocompleteSync
import java.util.concurrent.TimeUnit

/**
 * #1103 Keeps the Autocomplete document (IDs, Addresses, URLs, Secrets) fresh: cloud-me-api is the source (the
 * cloud-me-* private repos), this app's encrypted store is where it lands, and Cloud Keyboard reads it from there over
 * the `accountdata` provider. Every 6 hours when the network is up, and once at start.
 */
class MeAutocompleteWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = when (val r = MeAutocompleteSync.sync(applicationContext)) {
        is MeAutocompleteSync.Outcome.Synced -> Result.success()
        MeAutocompleteSync.Outcome.NoService -> Result.success() // not declared on this build: nothing to retry
        is MeAutocompleteSync.Outcome.Failed -> { Log.w("MeAutocomplete", "sync failed: ${r.why}"); Result.retry() }
    }

    companion object {
        private const val NAME = "cloud-account-me-autocomplete"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<MeAutocompleteWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
