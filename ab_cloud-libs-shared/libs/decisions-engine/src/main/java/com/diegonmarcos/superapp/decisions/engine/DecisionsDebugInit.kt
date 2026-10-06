package com.diegonmarcos.superapp.decisions.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.diegonmarcos.superapp.devtools.AppDebugServer
import org.json.JSONObject

/**
 * Registers /api/decisions on the fleet's debug server (a debug build only starts one) before anything
 * else runs in this process: this library has no Application of its own, and a provider declared in its
 * manifest is the one hook Android calls first (the libs:core CoreInitProvider pattern).
 *
 *   status    token, connectivity, breaker and each use as ✓/✗
 *   journal   the newest n lines as `app use ✓|✗ reason`
 *   probe     one call of the declared `probe` use (a fixed arithmetic statement, no user data): proves
 *             token, endpoint, budget and cache end to end
 *
 * Not a real provider: every data method is a no-op.
 */
class DecisionsDebugInit : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        runCatching {
            AppDebugServer.route("decisions", listOf(
                AppDebugServer.Op("status", "", "token, network, breaker and each use as ✓/✗"),
                AppDebugServer.Op("journal", "n=lines", "the newest journal lines: app use ✓/✗ reason (never state)"),
                AppDebugServer.Op("probe", "", "one call of the probe use: ✓/✗ and the reason"),
            )) { op, q ->
                val h = EngineHolder.holder(ctx)
                when {
                    h == null -> JSONObject().put("status", NO_POLICY).toString()
                    op == "status" -> DebugView.status(h.engine.status(h.self)).toString()
                    op == "journal" -> DebugView.journal(h.journal.summary((q["n"]?.toIntOrNull() ?: 20).coerceIn(1, 100))).toString()
                    op == "probe" -> DebugView.probe(h.engine.decide(h.self, PROBE)).toString()
                    else -> null
                }
            }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val NO_POLICY = "no_policy"
        val PROBE: JSONObject = JSONObject()
            .put("use", "probe")
            .put("state", JSONObject().put("statement", "2 + 2 = 4"))
            .put("questions", JSONObject().put("correct", JSONObject().put("type", "noul")
                .put("instructions", "The arithmetic statement in `statement` is correct.")))
    }
}
