package com.diegonmarcos.clouddrive.sync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.diegonmarcos.clouddrive.Declarations
import java.io.File

/**
 * #642 — cloud-drive does not run git. It asks cloud-terminal-nix to, over the
 * fleet's own RunCommandService (ours: ac_cloud-nix-on-droid
 * RunCommandService.java; #198/#620 exist to make it callable from another app).
 *
 * WHY THE TERMINAL AND NOT A CREDENTIAL HERE. The terminal already holds a real
 * git CLI inside its proot rootfs and the credential that pushes all day.
 * Teaching this app a second git auth was what #641 deleted (an OAuth device
 * grant against a GitHub App, which could never have worked) — the operation
 * belongs to the app that already owns it.
 *
 * EVERY FAILURE IS NAMED. A fire-and-forget `startService` is the one outcome
 * this must never have: not installed, permission not granted and service
 * unreachable are three different facts with three different remedies, and all
 * three look identical if the call is made blind. [plan] returns exactly which
 * one it is BEFORE anything is sent, and [Outcome.Sent] is returned only after
 * the system has accepted the call.
 */
object TerminalGit {

    /** What a handoff resolved to. Everything but [Sent] is a reason, in the
     *  user's words, that the caller must show rather than swallow. */
    sealed class Outcome {
        /** The system accepted the call; [dest] is where the clone will land. */
        data class Sent(val dest: File) : Outcome()
        /** build.json declares no terminal block, or an incomplete one. */
        object NotDeclared : Outcome()
        /** The declaration names an operation this page did not ask about. */
        data class NoSuchOp(val op: String) : Outcome()
        /** cloud-terminal-nix is not on the device. */
        data class NotInstalled(val pkg: String) : Outcome()
        /** Installed, but this app does not hold its RUN_COMMAND permission yet. */
        data class NeedsPermission(val permission: String) : Outcome()
        /** Installed and permitted, but nothing answers the declared action. */
        data class NoService(val action: String) : Outcome()
        /** The system refused the accepted call (SecurityException, background
         *  start refusal, service dead). Carries the platform's own words. */
        data class Refused(val why: String) : Outcome()
    }

    /** True when the terminal is installed. Package visibility is declared in the
     *  manifest (#642 <queries>), so an absent answer here means absent, not hidden. */
    fun installed(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    fun granted(ctx: Context, permission: String): Boolean =
        permission.isBlank() ||
            ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * WHICH outcome, decided from facts alone — no Context, no PackageManager — so every
     * branch is executed by the JVM suite instead of asserted about. [resolvesService] is the
     * caller's answer to "does anything answer the declared action", which is the branch that
     * catches a wrong action, the defect a fire-and-forget startService would hide.
     *
     * Ordered deliberately: declaration, then installed, then permission, then a service that
     * answers. Reporting "grant the permission" for a terminal that is not installed sends the
     * user somewhere that cannot help.
     */
    fun decide(
        decl: Declarations.GitTerminalDecl?,
        op: String,
        installed: Boolean,
        granted: Boolean,
        resolvesService: Boolean,
        dest: File,
    ): Outcome {
        if (decl == null || !decl.declared) return Outcome.NotDeclared
        if (decl.ops[op] == null) return Outcome.NoSuchOp(op)
        if (!installed) return Outcome.NotInstalled(decl.pkg)
        if (!granted) return Outcome.NeedsPermission(decl.permission)
        if (!resolvesService) return Outcome.NoService(decl.action)
        return Outcome.Sent(dest)
    }

    /**
     * The extras of the RUN_COMMAND intent, keyed by the DECLARED extra names. Pure, so the
     * suite asserts the argv really is an array and the workdir really is the clone's parent.
     */
    fun extras(
        decl: Declarations.GitTerminalDecl,
        argv: List<String>,
        dest: File,
    ): List<Pair<String, Any>> = listOf(
        decl.extraPath to decl.command,
        // An argv ARRAY, never a shell string: a space or a quote in a repository name
        // cannot re-split arguments that were never joined.
        decl.extraArguments to argv.toTypedArray(),
        decl.extraWorkdir to (dest.parentFile?.absolutePath ?: dest.absolutePath),
        decl.extraBackground to decl.background,
    )

    /** The intent for [op], or the reason there is none. */
    @Suppress("UNCHECKED_CAST")
    fun plan(
        ctx: Context,
        decl: Declarations.GitTerminalDecl?,
        op: String,
        url: String,
        dest: File,
    ): Pair<Intent?, Outcome> {
        val early = decide(
            decl, op,
            installed = decl != null && installed(ctx, decl.pkg),
            granted = decl != null && granted(ctx, decl.permission),
            resolvesService = true,          // not known yet; the real answer is below
            dest = dest,
        )
        if (early !is Outcome.Sent) return null to early
        val d = decl!!
        val argv = d.argv(op, url, dest.absolutePath) ?: return null to Outcome.NoSuchOp(op)
        val intent = Intent(d.action).apply {
            setPackage(d.pkg)
            extras(d, argv, dest).forEach { (k, v) ->
                when (v) {
                    is String -> putExtra(k, v)
                    is Array<*> -> putExtra(k, v as Array<String>)
                    is Boolean -> putExtra(k, v)
                }
            }
        }
        // resolveService is what turns "the action is wrong" or "the terminal exposes no such
        // service" into a fact BEFORE the call, instead of a startService that returns null
        // and looks exactly like success.
        val resolves = ctx.packageManager.resolveService(intent, 0) != null
        val outcome = decide(decl, op, installed = true, granted = true,
            resolvesService = resolves, dest = dest)
        return (if (outcome is Outcome.Sent) intent else null) to outcome
    }

    /** [plan] and, when it resolved, actually send it. The platform's own refusal
     *  (SecurityException on a permission revoked between check and call, a dead
     *  service) is returned as [Outcome.Refused] rather than thrown into a click
     *  handler where it would vanish. */
    fun run(
        ctx: Context,
        decl: Declarations.GitTerminalDecl?,
        op: String,
        url: String,
        dest: File,
    ): Outcome {
        val (intent, outcome) = plan(ctx, decl, op, url, dest)
        if (intent == null) return outcome
        return runCatching {
            // The terminal's service is a foreground-service starter; startService
            // is what its own documented clients use.
            ctx.startService(intent)
            outcome
        }.getOrElse { Outcome.Refused(it.message ?: it.toString()) }
    }
}
