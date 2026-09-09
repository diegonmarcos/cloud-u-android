package app.sterna.core.data.account

/** The write behind the drawer's collapse chevron, kept out of [AccountStore] for a JVM test. */

/** [accounts] with the collapse choice for [folderId] on the ONE record [id] names.
 * `false` WRITES `false` (absent = undecided); `it.id == id` matters because on IMAP
 *  folder ids are PATHS — without it a fold hits a namesake folder on another account (#92/#121). */
internal fun withCollapsedFolder(
    accounts: List<StoredAccount>,
    id: String,
    folderId: String,
    collapsed: Boolean,
): List<StoredAccount> =
    accounts.map {
        if (it.id == id) {
            it.copy(collapsedFolders = it.collapsedFolders + (folderId to collapsed))
        } else {
            it
        }
    }

/** [accounts] with [id]'s fold registry RE-KEYED after a rename ([oldId] + descendants moved
 * to [newId], same as [AccountStore.replaceWatchedFolder]). Descendant test is the full
 *  prefix + delimiter, never bare, or `Travailleur` would be dragged along with `Travail`. */
internal fun withRenamedCollapsedFolder(
    accounts: List<StoredAccount>,
    id: String,
    oldId: String,
    newId: String,
    delimiter: String,
): List<StoredAccount> =
    accounts.map { account ->
        if (account.id == id) {
            account.copy(
                collapsedFolders = account.collapsedFolders.mapKeys { (folder, _) ->
                    when {
                        folder == oldId -> newId
                        folder.startsWith(oldId + delimiter) -> newId + folder.removePrefix(oldId)
                        else -> folder
                    }
                },
            )
        } else {
            account
        }
    }
