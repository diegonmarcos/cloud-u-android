package com.diegonmarcos.cloudcalc

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.cloudcalc.engine.CalcClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The daily exchange-rate refresh: once a day, only with a network, the calc engine re-downloads its rate
 * files (the engine does the fetching; this app opens no URL) and replaces them only on success, so the
 * date Convert > Currency shows is always the fetched file's own.
 */
class RatesWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val answer = Fx.refresh(CalcClient(applicationContext), System.currentTimeMillis())
        return if (runCatching { JSONObject(answer).optBoolean("ok") }.getOrDefault(false)) Result.success() else Result.retry()
    }

    companion object {
        const val NAME = "cloud-calc-rates-daily"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RatesWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
