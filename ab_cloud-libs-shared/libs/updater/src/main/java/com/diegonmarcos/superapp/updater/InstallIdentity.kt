package com.diegonmarcos.superapp.updater

import android.content.Context
import android.os.Process
import android.os.UserManager
import android.util.Log

/**
 * THE INSTALL-IDENTITY DECLARATION — the single source of truth for which
 * Android install of this application the updater is allowed to manage.
 *
 * A phone can hold more than one copy of the same package, and Android runs
 * each copy as a DIFFERENT Android USER. The updater acted on every reachable
 * copy as though it were *the* install, so a parallel clone the owner never
 * installed produced the same badge, update prompt and notification as the
 * real one (#453). The way out is not a suppression list — a third list that
 * says which clones to hide is exactly the second-copy-of-the-answer shape
 * this fleet keeps tripping over. The updater must ASK the model which install
 * it is, and the model must be declared ONCE.
 *
 * ## What each install kind means for this fleet
 *
 * Android's multi-user system hosts a distinct principal per install, and it
 * is that distinction, not an OEM name, that decides whether we may act:
 *
 *   - MAIN: the install running as the device's PRIMARY user (Android user
 *     id 0 — the owner's own user). This is the cloud install the owner put
 *     on the phone, and it is the only one the updater may manage, badge,
 *     prompt or notify from.
 *
 *   - WORK PROFILE: a Device-Policy / Enterprise-managed profile, which
 *     Android reports with [UserManager.isManagedProfile] = true and runs
 *     under a non-zero Android user id. It is a second, isolated copy of the
 *     same APK. The updater did not put it there and must stay silent on it.
 *
 *   - PARALLEL CLONE: an OEM app-cloning feature ("parallel apps", "dual
 *     messenger") that provisions a fresh secondary Android user to host a
 *     sandboxed copy. Non-zero user id, not a managed profile. Same verdict
 *     as a work profile: unmanaged, silent.
 *
 *   - SAMSUNG SECURE FOLDER: Samsung's Knox workspace. It registers each
 *     cloned app under a separate Knox/secure user, which public Android APIs
 *     present only as another non-zero user id. It exposes no flag a normal
 *     app may read, so it is indistinguishable, by the public platform, from
 *     any other parallel clone — and it does not need to be told apart,
 *     because it is unmanaged for the SAME reason the others are.
 *
 * ## The decision (declared, not commented)
 *
 * [MANAGED] is the one place the answer lives: the updater is allowed to act
 * on exactly ONE kind — [InstallKind.MAIN], the primary-user install. Every
 * other Android user — a work profile, a parallel/dual-app clone, or a
 * Secure Folder copy — is a second copy the updater did not install, so it
 * must stay silent. Add a kind to [MANAGED] and the updater begins acting on
 * it; remove it and it stops. Nothing else in the engine re-states this set.
 *
 * [current] reads the Android user this process runs as and returns the kind.
 * The platform lets an ordinary app distinguish ONLY three cases reliably:
 * the primary user (id 0), a managed profile, and any other non-zero user.
 * The last covers parallel clones and Secure Folder alike — they collapse
 * into [InstallKind.CLONE] precisely because the platform exposes no signal
 * between them and the policy treats them identically.
 *
 * ## The reader
 *
 * Every updater path that can emit a badge, a prompt or a notification —
 * [Updater.start], the self [UpdateWorker], and the fleet
 * [com.diegonmarcos.superapp.appstore.ConstellationWorker] — calls
 * [isManaged] at its head and returns silently when it is false. Gating the
 * SCHEDULERS means a clone never even queues the periodic work; gating the
 * WORKERS means a copy that was scheduled before this build (or re-armed by
 * an in-app toggle) cannot act. Both read the same [MANAGED] set.
 */
object InstallIdentity {
    private const val TAG = "Fleet/Identity"

    /** Every install kind the updater can be asked to judge. The four ways a
     *  device can hold this app are named here; see the file doc for what each
     *  means to this fleet. [CLONE] is the platform's collapse of parallel-app
     *  clones and Secure Folder, which Android reports as the same thing. */
    enum class InstallKind {
        /** The primary-user install (Android user id 0) — the owner's own copy. */
        MAIN,
        /** An Enterprise / Device-Policy managed profile. */
        WORK_PROFILE,
        /** Any other non-primary user: an OEM parallel/dual-app clone or a
         *  Samsung Secure Folder copy — whatever Android reports as a
         *  secondary user that is not a managed profile. */
        CLONE,
    }

    /** THE DECLARATION. Exactly one managed kind: the primary-user install.
     *  This set is the entire answer; every caller reads it here. */
    val MANAGED: Set<InstallKind> = setOf(InstallKind.MAIN)

    /**
     * Which install kind THIS process is running as. Reads the current
     * Android user id — the one signal this phone gives us that is true for
     * every hosting mechanism at once.
     *
     * Returns [InstallKind.MAIN] only for the primary user (id 0), [WORK_PROFILE]
     * for a managed profile, and [CLONE] for any other non-zero user. The last
     * arm deliberately subsumes parallel/dual-app clones and Samsung Secure
     * Folder: no public Android API separates them, and the policy does not
     * need them separated — both are unmanaged.
     */
    fun current(context: Context): InstallKind {
        // The user id this process runs its own copy under. A work profile, a
        // parallel-app clone and a Secure Folder copy all run as a non-zero
        // user; only the primary install is user 0.
        val user = Process.myUserHandle().identifier
        if (user == 0) return InstallKind.MAIN
        val um = runCatching {
            context.getSystemService(UserManager::class.java)
        }.getOrNull()
        if (um != null && um.isManagedProfile) return InstallKind.WORK_PROFILE
        return InstallKind.CLONE
    }

    /** Whether the updater may act on the install this process is running as.
     *  The one question every emitting path asks. Reads [MANAGED]; never
     *  duplicates its answer. */
    fun isManaged(context: Context): Boolean = current(context) in MANAGED

    /** Log-safe one-word kind, for the gate lines that say which copy passed. */
    fun describe(context: Context): String = current(context).name
}