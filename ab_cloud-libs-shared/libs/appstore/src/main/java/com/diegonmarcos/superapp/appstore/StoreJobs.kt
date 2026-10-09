package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.updater.UpdateProgress

/**
 * The Store's one [JobBoard] and [JobRunner], fed by the pipeline through
 * [UpdateProgress.sink]: whatever a job's thread publishes lands on that job's row only.
 */
object StoreJobs : UpdateProgress.JobSink {

    val board = JobBoard()
    val runner = JobRunner(board, maxDownloads = 3)

    private val observers = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addObserver(o: () -> Unit) { observers.addIfAbsent(o); o() }
    fun removeObserver(o: () -> Unit) { observers.remove(o) }
    private fun changed() = observers.forEach { it() }

    fun install() { UpdateProgress.sink = this }

    override fun onState(key: String, state: UpdateProgress.State) {
        when (state) {
            is UpdateProgress.State.Downloading -> board.download(key, state.bytes, state.total)
            is UpdateProgress.State.CheckingManifest -> board.phase(key, JobBoard.Phase.VERIFYING)
            is UpdateProgress.State.Installing -> board.phase(key, JobBoard.Phase.INSTALLING)
            is UpdateProgress.State.Done -> board.done(key)
            is UpdateProgress.State.Failed -> board.fail(key, state.message)
            is UpdateProgress.State.Cancelled -> board.fail(key, "cancelled")
            else -> return
        }
        changed()
    }

    override fun onStage(key: String, stage: String) {
        when (stage) {
            UpdateProgress.STAGE_DOWNLOADING -> board.phase(key, JobBoard.Phase.DOWNLOADING)
            UpdateProgress.STAGE_VERIFYING -> board.phase(key, JobBoard.Phase.VERIFYING)
            UpdateProgress.STAGE_INSTALLING -> board.phase(key, JobBoard.Phase.INSTALLING)
            else -> return
        }
        changed()
    }

    override fun onJob(key: String, job: UpdateProgress.Job?) {
        if (job != null) board.begin(key, job.app.ifEmpty { key })
        changed()
    }
}
