package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.cloudlib.sharedstore.StoreExec
import com.diegonmarcos.cloudlib.sharedstore.StoreExec.Companion.q
import com.diegonmarcos.cloudlib.sharedstore.StorePaths

/**
 * [StoreLegs.Copier] over CLOUD_EXEC: the copy runs inside cld.termux, where the store is
 * `/root/<dir>` and shared storage is `/storage/emulated/<u>`. Used whenever the caller is not the
 * store's own uid, and for engine staging in every mode that reaches cld.termux.
 */
class ExecCopier(private val ctx: Context, private val paths: StorePaths, private val userId: Int) : StoreLegs.Copier {

    private fun guest(p: String) = paths.guestPath(p, userId) ?: p

    override fun copy(from: String, to: String, replace: Boolean): String? {
        val r = StoreExec(ctx).run(copyScript(guest(from), guest(to), replace), timeoutMs = 110_000)
        return if (r.ok) null else (r.err.ifBlank { r.out }.ifBlank { "exit ${r.exit}" })
    }

    override fun remove(path: String): String? {
        val r = StoreExec(ctx).run(removeScript(guest(path)), timeoutMs = 60_000)
        return if (r.ok) null else (r.err.ifBlank { "exit ${r.exit}" })
    }

    companion object {
        fun copyScript(from: String, to: String, replace: Boolean): String {
            val into = if (replace) "$to.incoming" else to
            val body = "if [ -d ${q(from)} ]; then mkdir -p ${q(into)} && cp -a ${q(from)}/. ${q(into)}/; " +
                "elif [ -e ${q(from)} ]; then mkdir -p \"\$(dirname ${q(into)})\" && cp -a ${q(from)} ${q(into)}; else exit 0; fi"
            val swap = if (replace) "; [ ! -e ${q(into)} ] || { rm -rf ${q(to)} && mv ${q(into)} ${q(to)}; }" else ""
            return "set -e; $body$swap"
        }

        fun removeScript(path: String): String = "rm -rf ${q(path)}"
    }
}
