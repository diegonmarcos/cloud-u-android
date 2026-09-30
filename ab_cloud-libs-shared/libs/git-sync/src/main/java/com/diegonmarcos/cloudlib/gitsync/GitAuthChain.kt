package com.diegonmarcos.cloudlib.gitsync

/**
 * #646 THE WALKER of the declared git-auth chain — ONE ordered declaration
 * (`ab_cloud-libs-shared/build.json::auth.git_chain`, read by libs:auth's
 * AuthDeclaration.gitChain), tried front to back until a rung answers.
 *
 * The owner's ask, in his words: "if our servers are down we can do gh if not we
 * can use our flow". So this is not two features with an `if` between them: it is
 * ONE ranking held in DATA. Reordering that list reorders the real attempts and
 * removing a rung from it stops the rung being tried, with no edit to this file —
 * which is the property #646's tester mutation-proves, because a chain whose
 * order lives half in JSON and half in Kotlin is a chain nobody can re-rank.
 *
 * NOTHING HERE IS SORTED, FILTERED OR RE-RANKED. [resolve] iterates the list it
 * is handed, in the order it is handed, and the caller builds that list straight
 * off the declaration. There is no default order to fall back on and no rung
 * named in this file: a rung this walker has no implementation for is a visible
 * SKIP, not a silent omission.
 *
 * A RUNG THAT CANNOT BE REACHED IS A FALL-THROUGH, NEVER A FAILURE. The fleet
 * rung (#647) may be unreachable because our servers are down, because this
 * network cannot see them, or simply because it HAS NOT BEEN BUILT YET — three
 * causes a phone cannot tell apart and which all mean the same thing: try the
 * next rung. The chain only fails when it runs out of rungs, and it says so with
 * every step it took. This is the #639/#452 shape the fleet keeps rediscovering:
 * a chain that degrades silently looks identical to a chain that works.
 *
 * ONE CREDENTIAL, ONE ID. Whichever rung answers, the token is written through
 * the SAME [GitCredentialStore] under the SAME id the caller passes — which is
 * the id the vault import already writes (#629's `credential_id`). There is
 * exactly one [GitCredentialStore.setSecret] call in this file and the id is a
 * parameter, never a literal: a second id would split the credential in two and
 * stay invisible until a clone failed.
 *
 * NO TOKEN IS EVER RENDERED OR LOGGED. [Answer.Credential] and [Outcome] keep
 * the secret reachable only through a named property, and both override
 * `toString` so an accidental log line or string template prints a placeholder.
 * Every [Step] carries a sentence written for a person, and no step's text is
 * ever built from a token.
 */
object GitAuthChain {

    /** What one rung answers when it is asked for a credential. */
    sealed class Answer {
        /** It worked. [token] goes into the store and is never shown. */
        class Credential(val token: String) : Answer() {
            override fun toString(): String = "Credential(token=<redacted>)"
        }

        /**
         * #653 THE RUNG WILL SERVE THE REQUEST ITSELF, holding the credential on
         * its own side. The chain STOPS here exactly as it does for
         * [Credential] — the rung answered — but there is no token, because the
         * caller was never meant to receive one.
         *
         * This is the shape the fleet rung has: git-proxy-api authenticates the
         * phone with the Authelia bearer it already has, and talks to GitHub with
         * a credential the phone never sees. Modelling that as [Credential] would
         * have forced the rung to hand a GitHub token back to the device, which is
         * the precise thing it exists to avoid, and modelling it as a failure
         * would make the working path look broken.
         *
         * [why] says how the rung proved it can serve — the fact the page shows.
         */
        class Served(val why: String) : Answer()

        /**
         * The rung could not be reached at all — down, unroutable, timed out, or
         * not built yet. THE CHAIN CONTINUES: this is the case the whole
         * declaration exists for.
         */
        class Unreachable(val why: String) : Answer()

        /**
         * The rung was reached and said no (a refused credential, a cancelled
         * grant, an expired code). The chain still continues to the next rung —
         * being turned down by one provider is not a verdict on the next — but
         * the reason is recorded differently because it means something
         * different to the person reading it.
         */
        class Declined(val why: String) : Answer()

        /**
         * The rung is DECLARED but this build has no implementation for it. Kept
         * distinct from [Unreachable] because it is a different fact about a
         * different thing: the server may be perfectly healthy and this APK
         * simply cannot talk to it. Reported in the rung's declared position and
         * falls through like any other non-answer.
         */
        class NoImplementation(val why: String) : Answer()
    }

    /**
     * One rung as the walker sees it: its declared id and label, and how to
     * attempt it. The caller builds these from `AuthDeclaration.gitChain` IN
     * ORDER; this class holds no rank of its own, because the list's order IS
     * the rank and a second copy of it could disagree with the declaration.
     */
    class Rung(val id: String, val label: String, val attempt: () -> Answer)

    /** Why a rung did not produce the credential, as a word the UI can branch on. */
    enum class Result {
        /** This rung answered. At most one step ever carries this. */
        ANSWERED,

        /** Could not be reached — the fall-through case. */
        UNREACHABLE,

        /** Reached, and it said no. */
        DECLINED,

        /** Declared, but this build has no implementation for it. */
        NO_IMPLEMENTATION,

        /** Not attempted, because an earlier rung had already answered. */
        NOT_NEEDED,
    }

    /**
     * One observable attempt. [detail] is the sentence the page shows — "Fleet
     * unreachable → GitHub" is built from these, so the fall-through is VISIBLE
     * rather than inferred from a credential appearing out of nowhere.
     */
    class Step(val id: String, val label: String, val position: Int, val result: Result, val detail: String)

    /**
     * What the whole walk did: every step in the order it happened, and the
     * credential if one was obtained.
     *
     * [token] is deliberately the only way to reach the secret and [toString] is
     * redacted, so the outcome can be held in UI state and logged without the
     * credential travelling with it.
     */
    class Outcome(
        val steps: List<Step>,
        val answeredBy: String?,
        val credentialId: String?,
        private val credential: String?,
    ) {
        val ok: Boolean get() = answeredBy != null

        /** The credential, for the store and for a git operation. Never for display. */
        val token: String? get() = credential

        /** The rung that answered, as its declared label. */
        val answeredByLabel: String? get() = steps.firstOrNull { it.result == Result.ANSWERED }?.label

        /**
         * The chain's own account of itself, for the page: every rung that was
         * skipped and why, ending in the one that answered — or in the fact that
         * none did. This is what makes "Fleet unreachable → GitHub" a thing the
         * owner can READ rather than a thing he has to deduce.
         */
        fun narrative(): String = when {
            steps.isEmpty() -> "No git authentication is declared."
            ok -> steps.filter { it.result != Result.NOT_NEEDED }.joinToString(" → ") { it.detail }
            else -> "No provider could supply a git credential. " +
                steps.joinToString("; ") { it.detail } + "."
        }

        override fun toString(): String =
            "Outcome(answeredBy=$answeredBy, steps=${steps.size}, credential=<redacted>)"
    }

    /**
     * Walk [chain] in the order given and stop at the first rung that answers,
     * writing the credential into [store] under [credentialId].
     *
     * [credentialId] blank means the caller has no declared id to write under, so
     * nothing is stored — the credential is still returned, but it is NOT quietly
     * filed under an invented id. That is the #629 failure this must not repeat.
     */
    fun resolve(
        chain: List<Rung>,
        store: GitCredentialStore?,
        credentialId: String,
    ): Outcome {
        val steps = mutableListOf<Step>()
        var answeredBy: String? = null
        var credential: String? = null

        for ((index, rung) in chain.withIndex()) {
            if (answeredBy != null) {
                steps += Step(rung.id, rung.label, index, Result.NOT_NEEDED, "${rung.label} not needed")
                continue
            }
            // A rung's own implementation may throw — a dead socket, a malformed
            // body, anything. That is an UNREACHABLE rung, not a dead chain: the
            // throw is turned into the fall-through it actually is.
            val answer = try {
                rung.attempt()
            } catch (t: Throwable) {
                Answer.Unreachable(t.message ?: t.javaClass.simpleName)
            }
            when (answer) {
                is Answer.Credential -> {
                    answeredBy = rung.id
                    credential = answer.token
                    steps += Step(rung.id, rung.label, index, Result.ANSWERED, "${rung.label} signed in")
                }
                // #653 ANSWERED WITH NO TOKEN. `credential` is deliberately left
                // null, so nothing is written to the store and the caller can tell
                // "this rung serves it" from "here is a credential" by asking for
                // the token and getting nothing.
                is Answer.Served -> {
                    answeredBy = rung.id
                    steps += Step(
                        rung.id, rung.label, index, Result.ANSWERED,
                        "${rung.label} signed in, and serves it (${answer.why})",
                    )
                }
                is Answer.Unreachable ->
                    steps += Step(
                        rung.id, rung.label, index, Result.UNREACHABLE,
                        "${rung.label} unreachable (${answer.why})",
                    )
                is Answer.Declined ->
                    steps += Step(
                        rung.id, rung.label, index, Result.DECLINED,
                        "${rung.label} declined (${answer.why})",
                    )
                is Answer.NoImplementation ->
                    steps += Step(
                        rung.id, rung.label, index, Result.NO_IMPLEMENTATION,
                        "${rung.label} not available in this build (${answer.why})",
                    )
            }
        }

        // ONE write, one id. Blank id = nothing is stored, deliberately.
        if (credential != null && credentialId.isNotBlank()) {
            store?.setSecret(credentialId, credential)
        }
        return Outcome(
            steps = steps,
            answeredBy = answeredBy,
            credentialId = credentialId.takeIf { it.isNotBlank() && credential != null },
            credential = credential,
        )
    }

    /**
     * Build the walker's list from the DECLARED rungs, preserving their order
     * exactly, with [implementations] supplying the attempt for each declared id.
     *
     * A declared rung with no implementation becomes a rung that reports
     * [Result.NO_IMPLEMENTATION] — visible, and still in its declared position —
     * rather than being dropped from the list. A rung that is NOT declared is
     * never built and therefore never tried, whatever [implementations] contains:
     * the declaration decides, not the code that happens to have an
     * implementation lying around.
     */
    fun rungs(
        declared: List<Pair<String, String>>,
        implementations: Map<String, () -> Answer>,
    ): List<Rung> = declared.map { (id, label) ->
        val impl = implementations[id]
        Rung(id, label) {
            impl?.invoke() ?: Answer.NoImplementation("no implementation for '$id' is linked")
        }
    }
}
