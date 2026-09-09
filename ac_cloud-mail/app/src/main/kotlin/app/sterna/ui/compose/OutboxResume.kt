package app.sterna.ui.compose

import kotlinx.coroutines.Job

/**
 * Reattach a queued row to a composer rebuilt after a process death: AFTER the startup recovery, never
 */
internal suspend fun <T> resumeOutboxRow(id: Long, startupRecovery: Job, take: suspend (Long) -> T?): T? {
    startupRecovery.join()
    return take(id)
}
