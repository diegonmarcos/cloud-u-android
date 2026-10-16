package org.fossify.phone.sms

/** One active SIM, as SubscriptionManager reports it. */
data class SimInfo(val subscriptionId: Int, val slotIndex: Int, val displayName: String)

/** What the composer does about the SIM: send on one, or put the picker in front of the owner. */
sealed class SimChoice {
    data object NoSim : SimChoice()
    data class Use(val sim: SimInfo) : SimChoice()
    data class Ask(val sims: List<SimInfo>) : SimChoice()
}

/**
 * Dual-SIM selection. In order of authority:
 *  1. the SIM the owner just picked in this composer ([picked]);
 *  2. the SIM this conversation last sent on ([lastUsedInThread]), so a thread stays on its line;
 *  3. the app's own preferred SIM ([appPreferred], the "always use" choice);
 *  4. the system's default SMS SIM ([systemDefault]);
 *  5. otherwise ask. One SIM never asks; no SIM sends nothing.
 * A subscription that is no longer active is skipped, never used.
 */
object SimSelection {
    fun choose(
        sims: List<SimInfo>,
        picked: Int? = null,
        lastUsedInThread: Int? = null,
        appPreferred: Int? = null,
        systemDefault: Int? = null,
    ): SimChoice {
        if (sims.isEmpty()) return SimChoice.NoSim
        if (sims.size == 1) return SimChoice.Use(sims.first())
        for (id in listOf(picked, lastUsedInThread, appPreferred, systemDefault)) {
            val sim = sims.firstOrNull { it.subscriptionId == id }
            if (sim != null) return SimChoice.Use(sim)
        }
        return SimChoice.Ask(sims.sortedBy { it.slotIndex })
    }
}
