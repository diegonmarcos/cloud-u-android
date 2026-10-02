// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.ToolbarMode
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Why the toolbar row is what it is, kept where the debug API can read it (#776).
 *
 * The row used to be built ONCE, in SuggestionStripView's init, from whatever
 * SettingsValues said at that instant - and SettingsValues says HIDDEN whenever the
 * keyguard is locked (SettingsValues: `mToolbarMode = mIsLocked ? HIDDEN : ...`). An
 * input view recreated while the phone was locked (a night-mode switch, a rotation or
 * a colour change reaches KeyboardSwitcher.updateKeyboardTheme, which rebuilds the
 * whole view) therefore came up with zero toolbar keys, and after the unlock nothing
 * rebuilt it: updateKeyboardTheme's other branch only re-attaches the strip. Only a
 * brand-new service - switching to another keyboard and back - built a fresh row.
 *
 * [rebuildReason] is the one decision of when a live row must be rebuilt, so the fix,
 * the self-heal guard and /api/keyboard/toolbar all answer from the same rule.
 */
object ToolbarStatus {

    /** What a row is built from: the mode plus both rows' enabled keys, in order. */
    data class Layout(val mode: ToolbarMode, val first: List<ToolbarKey>, val second: List<ToolbarKey>) {
        /** Icons the first row should draw: none when the user hid the toolbar. */
        val expected get() = if (mode == ToolbarMode.HIDDEN) 0 else first.size
    }

    /**
     * Null when the row built for [builtFor] still describes [now] and draws something;
     * otherwise the reason it has to be rebuilt. The last branch is the self-heal guard:
     * a row that should hold icons and draws none is rebuilt whatever the cause, and says
     * so, so an unknown second cause shows up in the log instead of being papered over.
     */
    fun rebuildReason(builtFor: Layout?, now: Layout, drawn: Int): String? = when {
        builtFor == null -> "never built"
        builtFor.mode != now.mode -> "built for ${builtFor.mode}, settings now say ${now.mode}"
        builtFor.first != now.first || builtFor.second != now.second -> "toolbar keys changed since the row was built"
        now.expected > 0 && drawn == 0 -> "SELF-HEAL: ${now.expected} icons expected, 0 drawn"
        else -> null
    }

    @Volatile private var mode: ToolbarMode? = null
    @Volatile private var expected = 0
    @Volatile private var drawn = 0
    @Volatile private var keys: List<String> = emptyList()
    @Volatile private var dropped: List<String> = emptyList()
    @Volatile private var lastRebuildReason: String? = null
    @Volatile private var lastRebuildAt = 0L
    @Volatile private var lastCheck: String? = null
    @Volatile private var wouldRebuild: String? = null
    @Volatile private var lastCheckAt = 0L
    @Volatile private var rebuilds = 0
    @Volatile private var selfHeals = 0
    @Volatile private var strip: WeakReference<SuggestionStripView>? = null

    fun built(view: SuggestionStripView, reason: String, layout: Layout, keys: List<String>, dropped: List<String>, drawn: Int) {
        strip = WeakReference(view)
        mode = layout.mode
        expected = layout.expected
        this.keys = keys
        this.dropped = dropped
        this.drawn = drawn
        lastRebuildReason = reason
        lastRebuildAt = System.currentTimeMillis()
        rebuilds++
        if (reason.contains("SELF-HEAL")) selfHeals++
    }

    fun checked(trigger: String, expected: Int, drawn: Int, wouldRebuild: String?) {
        this.expected = expected
        this.drawn = drawn
        this.wouldRebuild = wouldRebuild
        lastCheck = trigger
        lastCheckAt = System.currentTimeMillis()
    }

    /**
     * The status as JSON, re-measured on the main thread first when a strip is alive -
     * a snapshot from the last onStartInputView could be minutes old, and the question
     * the architect is asking is what the row draws NOW. Measured only, never repaired:
     * `would_rebuild` says what the next onStartInputView will do about it. Waits at most
     * [timeoutMs]; on a timeout the answer says it is stale rather than pretending.
     */
    fun json(timeoutMs: Long = 500): String {
        val view = strip?.get()
        var live = false
        if (view != null) {
            val done = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                runCatching { view.measureToolbar("debug api") }
                done.countDown()
            }
            live = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
        return JSONObject()
            .put("live", live)
            .put("strip_attached", view != null)
            .put("mode", mode?.name ?: JSONObject.NULL)
            .put("expected_icons", expected)
            .put("drawn_icons", drawn)
            .put("healthy", expected == 0 || drawn > 0)
            .put("would_rebuild", wouldRebuild ?: JSONObject.NULL)
            .put("keys", JSONArray(keys))
            .put("dropped", JSONArray(dropped))
            .put("last_rebuild_reason", lastRebuildReason ?: JSONObject.NULL)
            .put("last_rebuild_at", lastRebuildAt)
            .put("last_check", lastCheck ?: JSONObject.NULL)
            .put("last_check_at", lastCheckAt)
            .put("rebuilds", rebuilds)
            .put("self_heals", selfHeals)
            .toString()
    }
}
