package com.diegonmarcos.cloudme

/**
 * #1367 seed-once. A collection that declares a `seed` gets those records copied into the store the first time a load
 * meets it, stamped [MOCK] = "1" (the page marks them as mock data; an edit keeps only declared fields, so an edited
 * record is the owner's). The store remembers every seeded collection in [MARK], so the seed is never applied again:
 * not after the owner deletes every record, not after an app update changes the declared seed. A collection that
 * already holds records when it is first met is only marked, never topped up: user records are never overwritten.
 */
object LedgerSeed {
    const val MARK = "_seeded"
    const val MOCK = "mock"

    /** [d] with every unseen seeded collection of [defs] applied and marked; the same instance when nothing changed. */
    fun applied(d: LedgerData, defs: List<LedgerDef>): LedgerData {
        val done = d.rows(MARK).mapNotNull { it["collection"] }.toSet()
        var out = d
        for (def in defs) {
            if (def.seed.isEmpty() || def.collection in done) continue
            if (out.rows(def.collection).isEmpty()) for (r in def.seed) out = out.added(def.collection, r - "id" + (MOCK to "1"))
            out = out.added(MARK, mapOf("collection" to def.collection))
        }
        return out
    }

    /** The badge a seeded, never-edited record shows on its row; null for the owner's own records. */
    fun badge(r: Map<String, String>): String? = if (r[MOCK] == "1") "mock data" else null

    /** Test tag of that badge. */
    fun tag(collection: String, id: String) = "me:ledger:mock:$collection:$id"
}
