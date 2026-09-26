package com.diegonmarcos.cloudlib.auth

/**
 * Configs ▸ Profile ▸ Connect — THE JOURNEY (#573): sign in → who → which
 * device → get everything. This object is the one rule that decides, from
 * what the page knows, which of the four steps is done, which is next, which
 * is locked and why. It is pure — no Android, no I/O, no strings, no light
 * (the host maps a [Phase] to its own status light) — so the screen is a
 * rendering of a [State] and the state machine is testable on the JVM
 * without a view. Shared through libs:auth (#587): cloud-drive's sign-in
 * reads the same rule for step 1.
 *
 * See a0_docs/eng-specs/superapp-auth-profile-peer-flow.md, section 2.
 */
object ProfileJourney {

    /** The four steps, in the order the page draws them. */
    enum class Step { SIGN_IN, WHO, DEVICE, GET }

    /** What a step's card shows: its light and whether its body is open. */
    enum class Phase { DONE, ACTIVE, LOCKED, FAILED }

    /** Why a step cannot be taken yet — the summary the locked card prints. */
    enum class Lock { SIGN_IN_FIRST, IDENTITY_ONLY, NO_REGISTRY, PICK_IDENTITY, PICK_PEER, REFETCH }

    /**
     * Everything the page knows, gathered once per draw by the fragment.
     *
     * @param session the sign-in of this process, if any (memory only).
     * @param storedBearerEmail the address the stored Authelia bearer belongs
     *   to, or "" — a stored bearer IS a durable sign-in.
     * @param identityOnly true when the session's provider proves an address
     *   and grants nothing else, so no registry can come from it.
     * @param registry the user's identities and peers, off the artifact (or its cache).
     * @param artifactInMemory whether the full artifact is here to apply from.
     * @param identity the STORED identity pick ("" until the owner taps one).
     * @param peer the STORED peer pick ("" until the owner taps one).
     * @param appliedAt when the artifact was last applied on this device, or "".
     * @param failed steps whose last attempt reported an error.
     */
    data class State(
        val session: SignIn.Session? = null,
        val storedBearerEmail: String = "",
        val identityOnly: Boolean = false,
        val registry: UserRegistry.Registry? = null,
        val artifactInMemory: Boolean = false,
        val identity: String = "",
        val peer: String = "",
        val appliedAt: String = "",
        val vaultFetched: Boolean = false,
        val failed: Set<Step> = emptySet(),
    ) {
        val signedIn: Boolean get() = session != null || storedBearerEmail.isNotBlank()
        val chosenIdentity: UserRegistry.Identity? get() = registry?.identity(identity)
        val chosenPeer: UserRegistry.Peer? get() = registry?.peer(peer)
    }

    /** The row to pre-highlight in step 2: the address the sign-in proved when
     *  it is one of the registry's, else the primary. A default, never a pick. */
    fun defaultIdentity(s: State): UserRegistry.Identity? {
        val r = s.registry ?: return null
        return s.session?.identity?.takeIf { it.isNotBlank() }?.let { r.identity(it) }
            ?: s.storedBearerEmail.takeIf { it.isNotBlank() }?.let { r.identity(it) }
            ?: r.primaryIdentity
    }

    /** The row to pre-highlight in step 3: the primary peer. */
    fun defaultPeer(s: State): UserRegistry.Peer? = s.registry?.primaryPeer

    fun done(s: State, step: Step): Boolean = when (step) {
        Step.SIGN_IN -> s.signedIn
        Step.WHO -> s.registry != null && s.chosenIdentity != null
        Step.DEVICE -> done(s, Step.WHO) && s.chosenPeer != null
        Step.GET -> done(s, Step.DEVICE) && s.appliedAt.isNotBlank()
    }

    /** Why [step] is locked, or null when it can be taken (or is done). */
    fun lock(s: State, step: Step): Lock? = when (step) {
        Step.SIGN_IN -> null
        Step.WHO -> when {
            !s.signedIn -> Lock.SIGN_IN_FIRST
            s.registry != null -> null
            s.identityOnly -> Lock.IDENTITY_ONLY
            else -> Lock.NO_REGISTRY
        }
        Step.DEVICE -> lock(s, Step.WHO) ?: if (done(s, Step.WHO)) null else Lock.PICK_IDENTITY
        Step.GET -> lock(s, Step.DEVICE) ?: when {
            !done(s, Step.DEVICE) -> Lock.PICK_PEER
            !s.artifactInMemory -> Lock.REFETCH
            else -> null
        }
    }

    fun phase(s: State, step: Step): Phase = when {
        step in s.failed -> Phase.FAILED
        done(s, step) -> Phase.DONE
        lock(s, step) == null -> Phase.ACTIVE
        else -> Phase.LOCKED
    }

    /** The step the hero points at: the first one not done. */
    fun next(s: State): Step = Step.values().firstOrNull { !done(s, it) } ?: Step.GET

    /** "Step N of 4" — N counts done steps from the top, capped at the last. */
    fun stepNumber(s: State): Int = Step.values().indexOf(next(s)) + 1

    fun allDone(s: State): Boolean = Step.values().all { done(s, it) }

    /** A done card is closed, the active (or failed) one open, a locked one closed. */
    fun bodyOpen(phase: Phase): Boolean = phase == Phase.ACTIVE || phase == Phase.FAILED

    /** The tag a step's card carries, so a layout test can find it without knowing how it is built. */
    fun tag(step: Step): String = "step:" + step.name.lowercase()
}
