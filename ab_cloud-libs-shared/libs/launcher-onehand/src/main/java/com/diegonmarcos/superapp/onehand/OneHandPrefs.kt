package com.diegonmarcos.superapp.onehand

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-user override of each swipe's action, persisted on-device. build.json
 * seeds the DEFAULT action for every slot; the user re-maps any slot here and
 * the override wins. Key = "<handleId>.<slotKey>" → OneHandAction.name.
 * Absent key = fall back to the baked default.
 */
object OneHandPrefs {
    private const val FILE = "onehand_prefs"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Override string wins; else the baked default; null = unmapped slot. */
    fun actionFor(ctx: Context, handleId: String, slotKey: String, default: GestureAction?): GestureAction? {
        val raw = prefs(ctx).getString("$handleId.$slotKey", null) ?: return default
        return GestureAction.parse(raw) ?: default
    }

    fun setAction(ctx: Context, handleId: String, slotKey: String, action: GestureAction?) {
        val e = prefs(ctx).edit()
        if (action == null) e.putString("$handleId.$slotKey", OneHandAction.NONE.name)
        else e.putString("$handleId.$slotKey", action.serialize())
        e.apply()
    }

    fun clear(ctx: Context, handleId: String, slotKey: String) {
        prefs(ctx).edit().remove("$handleId.$slotKey").apply()
    }

    /**
     * The keys of the per-sector edge-menu overrides, and nothing else.
     *
     * Every override is written as "<handleId>.<slotKey>", so the dot is what
     * separates them from this store's flat settings — `enabled`, `trigger`,
     * `debug_visible`, the prune marker. Those describe the FEATURE, not the
     * menu's contents, and a reset of the menu must leave them alone. Read off
     * the store rather than off the current handle list on purpose: an override
     * left behind by a handle or a sector build.json no longer declares is
     * exactly the kind of stale value the reset exists to clear, and walking the
     * declared slots would step over it.
     */
    private fun edgeMenuKeys(p: SharedPreferences): List<String> =
        p.all.keys.filter { it.contains('.') }

    /** How many sectors the user has overridden — what the confirmation names. */
    fun edgeMenuOverrideCount(ctx: Context): Int = edgeMenuKeys(prefs(ctx)).size

    /**
     * "Reset to default" for both edge menus: REMOVE the overrides so
     * [actionFor] falls through to the baked build.json action for every slot.
     * Returns how many were removed.
     *
     * IT DELETES, IT DOES NOT WRITE TODAY'S DEFAULTS BACK. The two are
     * indistinguishable on the day they ship and diverge forever after: values
     * written into the store would shadow every default shipped afterwards, so
     * the owner would be pinned to whatever onehand.handles said the day he
     * pressed the button, and would have to press it again after every update
     * without ever being told why. With the keys gone the menu tracks build.json,
     * which is the only thing "default" can honestly mean.
     *
     * The prune marker is deliberately left set: there is nothing left to prune
     * once the overrides are gone, and clearing it would re-arm a one-time
     * repair against a store it has already finished with.
     */
    fun clearEdgeMenuOverrides(ctx: Context): Int {
        val p = prefs(ctx)
        val keys = edgeMenuKeys(p)
        val e = p.edit()
        for (k in keys) e.remove(k)
        e.apply()
        return keys.size
    }

    /**
     * One-time repair of overrides nobody chose.
     *
     * The Configs editor attached its spinner listener straight after
     * setSelection, and setSelection POSTS its callback — so simply opening
     * that screen persisted an override for every slot at whatever was on
     * display. Unmapped slots showed "None", so they were written as NONE, and
     * from then on the stored NONE beat any default later shipped in
     * build.json. A device that had visited the screen once could never receive
     * a new default again.
     *
     * The editor no longer does that. This clears what it already wrote: every
     * stored NONE is dropped once, so those slots fall back to the baked
     * default. A deliberate "None" is lost in the process, which is the honest
     * trade — the bug means we cannot tell a chosen None from a written one,
     * and unmapped-by-accident was overwhelmingly the common case. Any real
     * choice is one spinner tap to restore, and is then respected.
     *
     * Guarded by a marker so it happens exactly once per install.
     */
    fun pruneSpuriousNones(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(PRUNED_KEY, false)) return
        val e = p.edit()
        for ((k, v) in p.all) {
            // "<handleId>.<slotKey>" entries only — never `enabled` / `trigger`.
            if (k.contains('.') && v == OneHandAction.NONE.name) e.remove(k)
        }
        e.putBoolean(PRUNED_KEY, true).apply()
    }

    private const val PRUNED_KEY = "spurious_nones_pruned_v1"

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
    }

    fun trigger(ctx: Context, default: OneHandConfig.Trigger): OneHandConfig.Trigger {
        val raw = prefs(ctx).getString("trigger", null) ?: return default
        return runCatching { OneHandConfig.Trigger.valueOf(raw) }.getOrDefault(default)
    }

    fun setTrigger(ctx: Context, t: OneHandConfig.Trigger) {
        prefs(ctx).edit().putString("trigger", t.name).apply()
    }

    /** Debug: force the (normally invisible) handles to a bright visible bar so
     *  their placement/size can be verified on-device without a rebuild. */
    fun debugVisible(ctx: Context): Boolean = prefs(ctx).getBoolean("debug_visible", false)

    fun setDebugVisible(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("debug_visible", on).apply()
    }
}
