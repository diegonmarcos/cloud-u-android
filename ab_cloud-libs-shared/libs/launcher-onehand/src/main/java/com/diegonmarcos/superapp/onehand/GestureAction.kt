package com.diegonmarcos.superapp.onehand

import android.accessibilityservice.AccessibilityService
import android.content.Context

/**
 * What a swipe does: either a global accessibility action, or launch an app by
 * package. Stored/serialized as a string — a bare action id ("back") or
 * "app:<package>" ("app:com.brave.browser"). This is the single value type for
 * both build.json defaults and per-swipe user overrides ([OneHandPrefs]).
 */
sealed class GestureAction {
    data class Global(val action: OneHandAction) : GestureAction()
    data class OpenApp(val pkg: String) : GestureAction()

    /**
     * A destination inside the HOST app — "action:open_search", "section:cloud",
     * a page target, a URL. Serialized "action:<target>".
     *
     * The gesture service runs outside any Activity, so it cannot call the
     * launcher directly. It launches the host app with the target in the
     * `shortcut_action` extra, which MainActivity.handleShortcutIntent already
     * routes — the same door the launcher long-press shortcuts come through.
     * So this variant adds a new WAY IN and no new dispatch: search opened from
     * the edge is the identical sheet the star opens.
     */
    data class AppTarget(val target: String) : GestureAction()

    /** Round-trips through [OneHandPrefs] and matches spinner selections. */
    fun serialize(): String = when (this) {
        is Global -> action.name
        is OpenApp -> "app:$pkg"
        is AppTarget -> "action:$target"
    }

    /**
     * [label] is the name the menu drew on the sector the finger just left. It
     * is used for one thing only: SAYING which app is missing when the launch
     * cannot happen. The package id is a correct answer and an unreadable one.
     */
    fun perform(svc: AccessibilityService, label: String? = null) {
        when (this) {
            is Global -> if (action.supported) svc.performGlobalAction(action.globalAction)
            is OpenApp -> launch(svc, pkg, label)
            is AppTarget -> openInHost(svc, target)
        }
    }

    companion object {
        fun parse(s: String?): GestureAction? {
            if (s.isNullOrBlank()) return null
            if (s.startsWith("app:")) return OpenApp(s.removePrefix("app:"))
            // "action:" before the bare-id lookup: no OneHandAction is spelled
            // with a colon, so the prefixed form can never be a global action.
            if (s.startsWith("action:")) return AppTarget(s.removePrefix("action:"))
            return OneHandAction.from(s)?.let { Global(it) }
        }

        /** Wake the host app on the given target. [Context.getPackageName] is
         *  the host, so this stays correct for whichever app links the library
         *  rather than hardcoding the SuperApp. */
        private fun openInHost(ctx: Context, target: String) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("shortcut_action", if (target.contains(':')) target else "action:$target")
            runCatching { ctx.startActivity(intent) }
        }

        /** Say it out loud instead of doing nothing. Wrapped, because a throw
         *  out of the gesture handler is worse than the silence it replaces. */
        private fun say(ctx: Context, message: String) {
            runCatching {
                android.widget.Toast
                    .makeText(ctx, message, android.widget.Toast.LENGTH_LONG)
                    .show()
            }
        }

        /**
         * An `app:` sector whose package is not installed used to `return` here:
         * the menu animated, the finger lifted, and nothing at all happened —
         * indistinguishable from a missed swipe, and the single most common way
         * a shortcut on this fleet fails without anyone noticing.
         *
         * That state is not hypothetical and it is not rare. Drive was lifted
         * out of this app into its own APK (tasks 302 and 304), so the phone now
         * carries an edge sector pointing at a package that may legitimately be
         * absent, and the in-app page it used to open no longer exists to fall
         * back to. The deliberate answer is therefore to make the failure
         * VISIBLE rather than to substitute some other destination.
         *
         * Installing it is not offered from here on purpose: the download and
         * install path belongs to the host app behind `extapp:` targets, and an
         * accessibility service starting a download from a swipe would be a
         * second mechanism for something the SuperApp already owns.
         *
         * THE SENTENCE SITS ON THE EXIT ITSELF, not on the line above it. That
         * is the shape 1_cicd/src/scripts/cloud-android-silence-guard.py reads,
         * and this function is now a listed entry point in
         * 1_cicd/src/data/silence-guard.json: a `return` here that names no
         * reporter fails the build. An explanation on a neighbouring line is one
         * careless edit away from being deleted on its own.
         */
        private fun launch(ctx: Context, pkg: String, label: String?) {
            val name = label?.takeIf { it.isNotBlank() } ?: pkg
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                ?: run { say(ctx, "$name is not installed"); return }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
                .onFailure { say(ctx, "$name could not be opened") }
        }
    }
}
