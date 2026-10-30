package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.clouddrive.sync.store.StoreShell
import com.diegonmarcos.clouddrive.sync.store.StoreShell.Companion.q

/**
 * [StoreLegs.Copier] over CLOUD_EXEC: the copy runs inside cld.termux, where the store is
 * `/root/<dir>` and shared storage is `/storage/emulated/<u>`. Used whenever the caller is not the
 * store's own uid, and for engine staging in every mode that reaches cld.termux.
 * #1315 the guest form comes from the ONE [StoreShell.route]; a path outside the store passes through unchanged.
 */
class ExecCopier(ctx: Context) : StoreLegs.Copier {

    private val shell: StoreShell = StoreShell.of(ctx)

    private fun guest(p: String) = shell.route.guest(p) ?: p

    override fun copy(from: String, to: String, replace: Boolean): String? {
        // #1315 the copy's deadline is declared (ui.sync.git.copy_timeout_seconds).
        val r = shell.run(copyScript(guest(from), guest(to), replace), timeoutMs = com.diegonmarcos.clouddrive.Declarations.sync.git.copyTimeoutSeconds * 1000)
        return if (r.ok) null else (r.err.ifBlank { r.out }.ifBlank { "exit ${r.exit}" })
    }

    override fun remove(path: String): String? {
        val r = shell.run(removeScript(guest(path)), timeoutMs = 60_000)
        return if (r.ok) null else (r.err.ifBlank { "exit ${r.exit}" })
    }

    companion object {
        fun copyScript(from: String, to: String, replace: Boolean): String {
            val into = if (replace) "$to.incoming" else to
            val body = "if [ -d ${q(from)} ]; then mkdir -p ${q(into)} && cp -a ${q(from)}/. ${q(into)}/; " +
                "elif [ -e ${q(from)} ]; then mkdir -p \"\$(dirname ${q(into)})\" && cp -a ${q(from)} ${q(into)}; else exit 0; fi"
            val swapCmd = "[ ! -e ${q(into)} ] || { rm -rf ${q(to)} && mv ${q(into)} ${q(to)}; }"
            // #1317 a replace copy starts from no stale <to>.incoming (an earlier run killed by the timeout leaves one) and removes its own on failure.
            if (!replace) return "set -e; $body"
            return "rm -rf ${q(into)}; { $body; } && { $swapCmd; } || { rc=\$?; rm -rf ${q(into)}; exit \$rc; }"
        }

        fun removeScript(path: String): String = "rm -rf ${q(path)}"
    }
}
