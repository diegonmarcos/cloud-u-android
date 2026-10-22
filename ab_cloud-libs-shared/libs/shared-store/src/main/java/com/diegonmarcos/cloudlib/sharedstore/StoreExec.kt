package com.diegonmarcos.cloudlib.sharedstore

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.termux.cloud.ICloudExec
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The EXEC fallback of [StoreAccess]: run a shell script INSIDE cld.termux's glibc root (where
 * the store is /root/<dir>, git is Debian's and the user's own gitconfig applies), through the
 * signature-level CLOUD_EXEC bound service. Blocking: call it off the main thread.
 *
 * CLOUD_EXEC caps one call at 120 s, and a clone can take longer, so [job] starts the script
 * detached (nohup, its own log + exit file under <store>/.jobs) and polls with short calls.
 * Secrets travel on stdin, never argv (argv is world-readable in /proc).
 */
class StoreExec(private val ctx: Context) {

    data class Result(val exit: Int, val out: String, val err: String) { val ok get() = exit == 0 }

    private fun <T> bound(block: (ICloudExec) -> T): T {
        val latch = CountDownLatch(1)
        var svc: ICloudExec? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { svc = ICloudExec.Stub.asInterface(binder); latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { svc = null }
        }
        val intent = Intent(BuildConfig.EXEC_ACTION).setPackage(BuildConfig.OWNER_PACKAGE)
        check(ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)) { "cannot bind ${BuildConfig.EXEC_ACTION} in ${BuildConfig.OWNER_PACKAGE}" }
        try {
            check(latch.await(15, TimeUnit.SECONDS)) { "CLOUD_EXEC did not connect" }
            return block(svc ?: error("CLOUD_EXEC disconnected"))
        } finally { runCatching { ctx.unbindService(conn) } }
    }

    /** [script] in the guest (sh -c), stdin [stdin], at most [timeoutMs] (<= 120 s). */
    fun run(script: String, stdin: String? = null, timeoutMs: Int = 60_000): Result = bound { exec ->
        val b = exec.exec("/system/bin/sh", arrayOf("-c", guestWrap(script)), stdin, null, timeoutMs)
        val err = b.getString("error")
        Result(if (err != null) -1 else b.getInt("exit"), b.getString("stdout").orEmpty(), (b.getString("stderr").orEmpty() + (err?.let { "\n$it" } ?: "")).trim())
    }

    /** [script] on the HOST side of cld.termux (its own uid: its whole data dir is readable), not in the glibc guest. */
    fun runHost(script: String, timeoutMs: Int = 60_000): Result = bound { exec ->
        val b = exec.exec("/system/bin/sh", arrayOf("-c", script), null, null, timeoutMs)
        val err = b.getString("error")
        Result(if (err != null) -1 else b.getInt("exit"), b.getString("stdout").orEmpty(), (b.getString("stderr").orEmpty() + (err?.let { "\n$it" } ?: "")).trim())
    }

    /** A long-running [script]: detached in the guest, polled until it writes its exit code or [deadlineMs] passes. */
    fun job(script: String, stdin: String? = null, deadlineMs: Long = 30 * 60_000L): Result {
        val id = UUID.randomUUID().toString()
        val dir = "/root/${BuildConfig.STORE_DIR}/.jobs"
        val start = "mkdir -p $dir && chmod 700 $dir && cat > $dir/$id.in && " +
            "( nohup sh -c ${q("sh -c ${q(script)} < $dir/$id.in > $dir/$id.log 2>&1; echo \$? > $dir/$id.rc; rm -f $dir/$id.in")} >/dev/null 2>&1 & )"
        val started = run(start, stdin ?: "")
        if (!started.ok) return started
        val until = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < until) {
            Thread.sleep(2_000)
            val r = run("[ -f $dir/$id.rc ] && cat $dir/$id.rc", timeoutMs = 20_000)
            if (r.ok && r.out.isNotBlank()) {
                val log = run("tail -c 16000 $dir/$id.log; rm -f $dir/$id.log $dir/$id.rc", timeoutMs = 20_000).out
                return Result(r.out.trim().toIntOrNull() ?: -1, log, "")
            }
        }
        return Result(-1, "", "job $id outlived ${deadlineMs / 1000}s (log: ~/${BuildConfig.STORE_DIR}/.jobs/$id.log)")
    }

    companion object {
        /** Single-quote [s] for sh. */
        fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        /**
         * cld.termux's login shell file (~/.termux/shell -> enter.sh) runs [script] inside the glibc
         * root. Its -c goes to the user's login shell (fish by default), so the script is handed to
         * sh explicitly: the commands here never depend on fish syntax.
         */
        fun guestWrap(script: String): String = "exec /system/bin/sh \"\$HOME/.termux/shell\" -c ${q("sh -c " + q(script))}"
    }
}
