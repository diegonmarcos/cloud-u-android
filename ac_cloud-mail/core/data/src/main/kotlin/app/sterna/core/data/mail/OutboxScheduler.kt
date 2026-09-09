package app.sterna.core.data.mail

/** Arms the background job that delivers an outbox item. Implemented in the app layer, which owns
 *  WorkManager, and injected into [MailRepository] so the data module need not depend on the app. */
fun interface OutboxScheduler {
    /** Arm delivery of item [id], first running after [initialDelayMillis] (e.g. the undo window). */
    fun schedule(id: Long, initialDelayMillis: Long)
}
