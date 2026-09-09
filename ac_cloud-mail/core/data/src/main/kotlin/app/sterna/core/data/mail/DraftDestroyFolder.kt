package app.sterna.core.data.mail

/**
 * The folder a destroy of a server draft is opposed to. Without one `destroyAll` spares everything,
 */
fun draftDestroyFolder(cachedMailboxId: String?, draftsRoleMailboxId: String?): String? =
    cachedMailboxId ?: draftsRoleMailboxId
