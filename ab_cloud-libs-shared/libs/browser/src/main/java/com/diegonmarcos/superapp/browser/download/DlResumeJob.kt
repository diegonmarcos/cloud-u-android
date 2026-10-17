package com.diegonmarcos.superapp.browser.download

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Brings unfinished downloads back after process death, a reboot, a network the policy did not
 * allow, or the dataSync time limit. Android does not let a boot receiver or a background job
 * start a dataSync foreground service (Android 15), so this job first tries the service and,
 * when that is refused, runs the queue inside the job's own window (network constraint met,
 * process kept alive); when the window ends it parks the queue and asks to be rescheduled.
 * The job is persisted, so it survives a reboot by itself; [DlBootReceiver] re-arms it as well.
 */
class DlResumeJob : JobService() {
    private var params: JobParameters? = null
    private val main = Handler(Looper.getMainLooper())
    private val watcher: () -> Unit = { main.post { checkDone() } }

    override fun onStartJob(p: JobParameters): Boolean {
        val q = DlQueue.get(this)
        if (!q.hasWork()) return false
        if (DlService.running) { schedule(this, q.policy, WATCHDOG_MS); return false }
        // DlQueue's init re-queued what a dead process left (a user's pause stays paused); start it.
        q.pump()
        if (DlService.running) { schedule(this, q.policy, WATCHDOG_MS); return false }
        params = p
        inJob = true
        q.addListener(watcher)
        return true
    }

    private fun checkDone() {
        val p = params ?: return
        val q = DlQueue.get(this)
        if (!q.hasWork() || DlService.running) {
            q.removeListener(watcher); params = null; inJob = false
            jobFinished(p, false)
            if (q.hasWork()) schedule(this, q.policy, WATCHDOG_MS)
        }
    }

    override fun onStopJob(p: JobParameters): Boolean {
        val q = DlQueue.get(this)
        q.removeListener(watcher); params = null; inJob = false
        if (!DlService.running) q.suspendAll()
        return q.hasWork()
    }

    companion object {
        const val JOB_ID = 0xD10AD
        /** While a download is in flight, check this often that a process is still working on it. */
        const val WATCHDOG_MS = 5 * 60_000L

        /** True while this job is the one running the queue: re-scheduling the same id now would stop it. */
        @Volatile var inJob = false; private set

        fun schedule(ctx: Context, policy: DlPolicy, delayMs: Long) {
            if (inJob) return
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val net = when {
                policy.wifiOnly || !policy.allowMetered -> JobInfo.NETWORK_TYPE_UNMETERED
                else -> JobInfo.NETWORK_TYPE_ANY
            }
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, DlResumeJob::class.java))
                .setRequiredNetworkType(net)
                .setMinimumLatency(delayMs.coerceAtLeast(0))
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(info) }
        }

        fun available(ctx: Context): Boolean = DlService.available(ctx)
    }
}

/** After a reboot or an app update: re-arm the resume job when downloads were left unfinished. */
class DlBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val q = DlQueue.get(ctx)
                if (q.hasWork() || q.all().any { it.state == DlState.WAITING_NETWORK }) DlResumeJob.schedule(ctx, q.policy, 10_000)
            }
        }
    }
}
