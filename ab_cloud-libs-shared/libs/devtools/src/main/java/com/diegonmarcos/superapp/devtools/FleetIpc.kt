package com.diegonmarcos.superapp.devtools

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.diegonmarcos.superapp.devtools.FleetIpcWire.Reply
import com.diegonmarcos.superapp.devtools.FleetIpcWire.Via

/**
 * #ipc THE fleet IPC layer, Android half (a0_docs/eng-specs/fleet-ipc.md). One app asks
 * another on the same phone with [call]: a ContentProvider `call()` on the callee's
 * `<pkg>.fleet` ([FleetTokenProvider], already in every member), method
 * [FleetIpcWire.METHOD], arg = the op (`system/info`, `net/dns`, `adb/exec`, …), extras = the
 * query. The callee answers from [AppDebugServer.dispatch], the same route table its
 * loopback API serves, so a route is written once and reached both ways.
 *
 *  - access: the provider is exported behind CONSTELLATION_DATA (signature): the platform
 *    refuses any APK not signed with the fleet key before it reaches us, and [serve]
 *    re-checks, because the framework does not permission-check `call()` itself.
 *  - visibility: devtools' `<queries>` MESH_MEMBER intent makes every member visible.
 *  - waking: a provider call starts the callee's process; no activity launch, so the
 *    background-start limits do not apply.
 *  - timeouts: every call has one ([FleetIpcWire.DEFAULT_TIMEOUT_MS]); late = 504.
 *  - version skew: a member built before this layer returns null from `call()`; the client
 *    then asks its old loopback debug port ([FleetIpcWire.ladder]).
 *  - large bodies (a logcat) leave by a pipe, not the shared 1 MB binder buffer.
 */
object FleetIpc {
    private const val TAG = "FleetIpc"

    fun authority(pkg: String) = "$pkg.fleet"

    /** Server side: [FleetTokenProvider.call] hands every call here. */
    fun serve(ctx: Context, method: String, op: String?, extras: Bundle?): Bundle? {
        if (method != FleetIpcWire.METHOD) return null
        val granted = Binder.getCallingUid() == Process.myUid() ||
            ctx.checkCallingPermission(FleetToken.PERMISSION) == PackageManager.PERMISSION_GRANTED
        val r = FleetIpcWire.gate(granted) ?: runCatching {
            AppDebugServer.dispatch(ctx.applicationContext, op.orEmpty().trim('/'), queryOf(extras))
        }.getOrElse { Reply(500, "route threw ${it.javaClass.simpleName}\n") }
        return pack(r)
    }

    /**
     * Ask [pkg] for [op]. Blocking: call off the main thread. This app answers itself
     * in-process. [httpFallback] = the old member's loopback port, during the transition.
     */
    fun call(
        ctx: Context, pkg: String, op: String, query: Map<String, String> = emptyMap(),
        timeoutMs: Long = FleetIpcWire.DEFAULT_TIMEOUT_MS, httpFallback: Boolean = true,
    ): Reply {
        val app = ctx.applicationContext
        val o = op.trim('/')
        if (pkg == app.packageName) return AppDebugServer.dispatch(app, o, query).copy(via = Via.SELF)
        val http = if (!httpFallback) null else ({
            AppDebugServer.portOf(pkg)?.let { FleetIpcWire.httpGet(it, FleetIpcWire.path(o, query), FleetToken.get(app)) }
        })
        return FleetIpcWire.ladder({ FleetIpcWire.within(timeoutMs) { ask(app, pkg, o, query) } }, http)
            .also { if (!it.ok) Log.i(TAG, "$o @ $pkg: ${it.status} ${it.via} ${it.note}") }
    }

    /** Does [pkg] carry the IPC door at all? PackageManager only: starts no process. */
    fun hasDoor(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.resolveContentProvider(authority(pkg), 0) != null
    }.getOrDefault(false)

    // ── the typed client: one function per route the fleet asks each other ──
    fun ping(ctx: Context, pkg: String, timeoutMs: Long = FleetIpcWire.DEFAULT_TIMEOUT_MS) = call(ctx, pkg, "system/ping", timeoutMs = timeoutMs)
    fun info(ctx: Context, pkg: String) = call(ctx, pkg, "system/info")
    fun docs(ctx: Context, pkg: String) = call(ctx, pkg, "docs")
    fun peers(ctx: Context, pkg: String) = call(ctx, pkg, "fleet/peers")
    fun dns(ctx: Context, pkg: String) = call(ctx, pkg, "net/dns")
    fun crashes(ctx: Context, pkg: String) = call(ctx, pkg, "diagnostics/crashes")

    private fun ask(ctx: Context, pkg: String, op: String, query: Map<String, String>): Reply? {
        val client = ctx.contentResolver.acquireUnstableContentProviderClient(authority(pkg)) ?: return null
        try {
            val b = client.call(FleetIpcWire.METHOD, op, Bundle().apply { query.forEach { (k, v) -> putString(k, v) } })
                ?: return null
            return unpack(b)
        } finally {
            client.close()
        }
    }

    private fun queryOf(extras: Bundle?): Map<String, String> =
        extras?.keySet()?.mapNotNull { k -> extras.getString(k)?.let { k to it } }?.toMap().orEmpty()

    private fun pack(r: Reply): Bundle = Bundle().apply {
        putInt(FleetIpcWire.K_STATUS, r.status); putString(FleetIpcWire.K_TYPE, r.type)
        if (r.body.length <= FleetIpcWire.INLINE_MAX) { putString(FleetIpcWire.K_BODY, r.body); return@apply }
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread({
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(r.body.toByteArray()) } }
        }, "fleet-ipc-pipe").apply { isDaemon = true; start() }
        putParcelable(FleetIpcWire.K_PIPE, read)
    }

    @Suppress("DEPRECATION")
    private fun unpack(b: Bundle): Reply {
        val body = b.getString(FleetIpcWire.K_BODY)
            ?: (b.getParcelable<ParcelFileDescriptor>(FleetIpcWire.K_PIPE))?.let { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
            }.orEmpty()
        return Reply(b.getInt(FleetIpcWire.K_STATUS, 500), body, b.getString(FleetIpcWire.K_TYPE) ?: "text/plain")
    }
}
