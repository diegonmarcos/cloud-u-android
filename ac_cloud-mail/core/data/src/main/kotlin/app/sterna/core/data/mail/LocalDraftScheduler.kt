package app.sterna.core.data.mail

/**
 * Arms the background job that puts one draft the phone kept (#95) onto the server. It only books
 */
fun interface LocalDraftScheduler {
    fun schedule(accountId: String, id: String, initialDelayMillis: Long)
}
