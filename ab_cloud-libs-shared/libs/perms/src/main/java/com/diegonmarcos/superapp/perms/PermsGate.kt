package com.diegonmarcos.superapp.perms

import android.content.Context

/**
 * #1360 an operation that failed because a grant is missing says so: "Missing permission <label> — Grant", never a
 * generic error. Call [check] before (or [explain] after) the work; both read the live state of this app's own entries
 * and of the helper that does the work. [grant] opens the exact page (own: the Perms page with the rows highlighted;
 * helper: the helper's settings page for that grant).
 */
object PermsGate {
    const val BUTTON = "Grant"

    data class Missing(val own: List<PermEntry>, val helpers: List<HelperRow>) {
        val isEmpty: Boolean get() = own.isEmpty() && helpers.isEmpty()
        val message: String get() = message(this)
    }

    /** What is missing among [ids] (this app's catalogue ids) and the grants of helper [helperPkg] (null = none). */
    fun check(ctx: Context, ids: Collection<String>, helperPkg: String? = null): Missing {
        val own = AppPerms.own(ctx).filter { it.entry.id in ids && it.status.missing }.map { it.entry }
        val helpers = if (helperPkg == null) emptyList() else AppPerms.helpers(ctx).filter { it.helper.pkg == helperPkg && it.status.missing }
        return Missing(own, helpers)
    }

    /** After a failure: the missing-grant message when one explains it, else null (report the original error). */
    fun explain(ctx: Context, ids: Collection<String>, helperPkg: String? = null): String? =
        check(ctx, ids, helperPkg).takeUnless { it.isEmpty }?.message

    fun message(m: Missing): String {
        val parts = m.own.map { it.label } + m.helpers.map { "${it.entry.label} for ${it.helper.label}" }
        return "Missing permission " + parts.joinToString(", ") + " — $BUTTON"
    }

    /**
     * The Grant of the message, the in-place prompt: this app's Perms page with the missing rows (its own and the
     * helper's) first and highlighted, each with its Grant / Open button. [feature] names the caller ("vault", "git").
     */
    fun grant(ctx: Context, m: Missing, feature: String = "app") {
        PermsLink.open(ctx, feature, (m.own.map { it.id } + m.helpers.map { it.entry.id }).distinct())
    }

    /** check + grant in one: null when nothing is missing, else the message, with the Perms page opened on it. */
    fun prompt(ctx: Context, ids: Collection<String>, helperPkg: String? = null, feature: String = "app"): String? {
        val m = check(ctx, ids, helperPkg)
        if (m.isEmpty) return null
        grant(ctx, m, feature)
        return m.message
    }
}
