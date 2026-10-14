package com.diegonmarcos.superapp.agents

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import com.diegonmarcos.superapp.uikit.KitState
import java.text.DateFormat
import java.util.Date

/**
 * The live half of an agent row. Every channel here is LOCAL and already exists: this app's own
 * WorkManager and services, PackageManager, and a fleet app's own read-only provider (Cloud
 * Drive's Screenshot agent `state`). No HTTP call to another app or to the gateway is made; an
 * agent whose declaration says `none` is shown as "not exposed" with the declared reason.
 *
 * Suspends on WorkManager's flows and blocks on a provider query: call it off the main thread.
 */
object AgentStatusReader {

    data class Live(val word: String, val state: KitState, val details: List<Pair<String, String>> = emptyList())

    const val NOT_EXPOSED = "not exposed"
    const val NOT_INSTALLED = "app not installed"

    suspend fun read(ctx: Context, a: FleetAgents.Agent): Live = try { readOrThrow(ctx, a) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Live("error", KitState.BAD, listOf("Error" to (e.message ?: e.javaClass.simpleName))) }

    private fun installed(ctx: Context, pkg: String): Boolean = pkg.isNotBlank() &&
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private suspend fun readOrThrow(ctx: Context, a: FleetAgents.Agent): Live {
        val s = a.status
        // An agent of another app says nothing while that app is absent, whatever its channel.
        if (!a.isSelf && s.source != "installed" && !installed(ctx, a.packageName))
            return Live(NOT_INSTALLED, KitState.IDLE, listOf("Package" to a.packageName))
        return when (s.source) {
            "workmanager" -> work(WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(s.unique).first(), "Work" to s.unique)
            "workmanager_tag" -> work(WorkManager.getInstance(ctx).getWorkInfosByTagFlow(s.tag).first(), "Tag" to s.tag)
            "service" -> service(ctx, s.serviceClass)
            "provider" -> provider(ctx, a.packageName, s.authoritySuffix, s.path)
            "provider_presence" -> {
                val auth = "${a.packageName}.${s.authoritySuffix}"
                if (ctx.packageManager.resolveContentProvider(auth, 0) != null) Live("available", KitState.OK, listOf("Provider" to auth))
                else Live("absent", KitState.WARN, listOf("Provider" to "$auth is not published by this build of the app"))
            }
            "installed" ->
                if (installed(ctx, s.packageName)) Live("installed", KitState.OK, listOf("Package" to s.packageName))
                else Live("not installed", KitState.IDLE, listOf("Package" to s.packageName))
            else -> Live(NOT_EXPOSED, KitState.IDLE, listOf("Why" to s.reason.ifBlank { "the app exposes no status for it yet" }))
        }
    }

    /** The newest of a unique work's (or a tag's) infos, as a word and its state. */
    internal fun work(infos: List<WorkInfo>, key: Pair<String, String>): Live {
        if (infos.isEmpty()) return Live("not scheduled", KitState.IDLE, listOf(key))
        val running = infos.count { it.state == WorkInfo.State.RUNNING }
        val queued = infos.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        val failed = infos.count { it.state == WorkInfo.State.FAILED }
        val done = infos.count { it.state == WorkInfo.State.SUCCEEDED }
        val details = listOf(key, "Jobs" to infos.size.toString(), "Running" to running.toString(),
            "Queued" to queued.toString(), "Succeeded" to done.toString(), "Failed" to failed.toString(),
            "Attempts" to infos.sumOf { it.runAttemptCount }.toString())
        return when {
            running > 0 -> Live("running", KitState.BUSY, details)
            failed > 0 && queued == 0 -> Live("failed", KitState.BAD, details)
            queued > 0 -> Live("scheduled", KitState.OK, details)
            done > 0 -> Live("done", KitState.OK, details)
            else -> Live("off", KitState.IDLE, details)
        }
    }

    @Suppress("DEPRECATION") // getRunningServices still answers for the caller's OWN services, which is all this asks.
    private fun service(ctx: Context, cls: String): Live {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val up = am.getRunningServices(Int.MAX_VALUE).orEmpty().any { it.service.className == cls }
        return if (up) Live("running", KitState.OK, listOf("Service" to cls.substringAfterLast('.')))
        else Live("stopped", KitState.IDLE, listOf("Service" to cls.substringAfterLast('.')))
    }

    /** One row of a fleet app's read-only provider, every column shown; a few known ones read as words. */
    private fun provider(ctx: Context, pkg: String, suffix: String, path: String): Live {
        val auth = "$pkg.$suffix"
        if (ctx.packageManager.resolveContentProvider(auth, 0) == null)
            return Live("absent", KitState.WARN, listOf("Provider" to "$auth is not published by this build of the app"))
        val uri = Uri.parse("content://$auth/$path")
        val row = try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) emptyMap()
                else (0 until c.columnCount).associate { c.getColumnName(it) to (c.getString(it) ?: "") }
            }
        } catch (e: SecurityException) {
            return Live("not permitted", KitState.WARN, listOf("Why" to "this build of Cloud SuperApp holds no grant for $auth (reinstall it after the app)"))
        } ?: return Live("no answer", KitState.WARN, listOf("Provider" to auth))
        return providerLive(row)
    }

    /** The provider row as a word: pending work is busy, failures warn, a past scan is ok. */
    internal fun providerLive(row: Map<String, String>): Live {
        if (row.isEmpty()) return Live("no runs yet", KitState.IDLE)
        val details = row.map { (k, v) ->
            if (k.endsWith("_ms") && (v.toLongOrNull() ?: 0L) > 0L)
                label(k) to DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(v.toLong()))
            else label(k) to v
        }
        val pending = row["pending"]?.toLongOrNull() ?: 0L
        val failed = row["failed"]?.toLongOrNull() ?: 0L
        val lastScan = row["last_scan_ms"]?.toLongOrNull() ?: 0L
        return when {
            pending > 0 -> Live("$pending pending", KitState.BUSY, details)
            failed > 0 -> Live("$failed failed", KitState.WARN, details)
            lastScan > 0 -> Live("up to date", KitState.OK, details)
            else -> Live("no runs yet", KitState.IDLE, details)
        }
    }

    private fun label(col: String) = col.removeSuffix("_ms").replace('_', ' ').replaceFirstChar { it.uppercase() }
}
