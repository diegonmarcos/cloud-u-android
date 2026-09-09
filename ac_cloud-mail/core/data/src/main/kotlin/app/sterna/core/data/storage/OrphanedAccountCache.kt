package app.sterna.core.data.storage

/**
 * Which cached accounts no longer exist — the decision behind
 */
object OrphanedAccountCache {

    /**
     * The cached account ids in [cachedAccountIds] that [knownAccountIds] does not contain. Order is
     */
    fun orphans(knownAccountIds: Collection<String>, cachedAccountIds: Collection<String>): List<String> {
        if (knownAccountIds.isEmpty()) return emptyList()
        val known = knownAccountIds.toSet()
        return cachedAccountIds.distinct().filterNot { it in known }
    }
}
