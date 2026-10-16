package com.diegonmarcos.cloudagenda.agent

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.diegonmarcos.cloudagenda.BuildConfig
import com.diegonmarcos.cloudagenda.data.AgentConfig
import com.diegonmarcos.cloudagenda.data.TasksDb
import com.diegonmarcos.cloudagenda.data.TasksRepository
import com.diegonmarcos.superapp.ai.AiAgents
import com.diegonmarcos.superapp.ai.AiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Builds the real runner: the tasks database, Cloud Mail's agent provider, the fleet AI gateway. */
object AgentRunners {
    fun build(context: Context): AgentRunner {
        val app = context.applicationContext
        return AgentRunner(
            TasksRepository(TasksDb.shared(app)),
            CloudMailProvider(app, BuildConfig.MAIL_PACKAGE),
            GatewayAgentBackend(AiConfig.load(app), BuildConfig.AI_GATEWAY_URL, AiAgents.load(app)),
        )
    }
}

/**
 * The periodic worker behind Settings > Agent. It runs [AgentRunner] once: the runner checks the backend, reads the
 * new mail and stores PROPOSALS, never tasks. Returns success whatever the pass found: a missing backend is recorded
 * as the agent's status (and shown on the page), not retried in a tight loop.
 */
class MailAgentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.IO) { runCatching { AgentRunners.build(applicationContext).run() } }
        return Result.success()
    }
}

/** Applies the declaration's frequency to WorkManager: one unique periodic job, replaced on every save, gone when the agent is off. */
object AgentScheduler {
    /** The unique work name; build.json::fleet_agents[mail-tasks].status.unique names the same string. */
    const val UNIQUE = "tasks-mail-agent"
    /** WorkManager's floor for periodic work. */
    const val MIN_PERIOD_MINUTES = 15L

    /** The period to schedule, or null when nothing should be scheduled (agent off, or manual only). */
    fun periodMinutes(cfg: AgentConfig): Long? =
        if (!cfg.enabled || cfg.frequencyMinutes <= 0) null else maxOf(MIN_PERIOD_MINUTES, cfg.frequencyMinutes.toLong())

    fun apply(context: Context, cfg: AgentConfig) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val period = periodMinutes(cfg)
        if (period == null) {
            wm.cancelUniqueWork(UNIQUE)
            return
        }
        val request = PeriodicWorkRequestBuilder<MailAgentWorker>(period, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
