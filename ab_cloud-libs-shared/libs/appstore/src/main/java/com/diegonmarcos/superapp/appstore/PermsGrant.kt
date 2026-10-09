package com.diegonmarcos.superapp.appstore

/**
 * Store > Access > Android Perms, as pure rules (no Android): the permission filter, the
 * alphabetical order, and the "grant missing perms" plan. The page only draws these and feeds
 * them what PackageManager says; the shell goes through [GrantChannel], so a fake stands in.
 */
object PermsGrant {

    /** What can be done about a declared permission from the shell. */
    enum class Kind {
        /** Dangerous / runtime: `pm grant <pkg> <perm>`. */
        RUNTIME,
        /** An app-op the shell may set: `appops set <pkg> <OP> allow`. */
        APPOP,
        /** Special access that needs a user screen: listed, never granted from here. */
        SPECIAL,
        /** Install-time, signature, normal: nothing to grant. */
        NONE,
    }

    /** One fleet app as the page read it. [ours] = installed AND signed with the constellation key. */
    data class App(
        val pkg: String, val label: String, val ours: Boolean,
        val declared: List<String>, val granted: Set<String>,
    )

    /** Android's PermissionInfo.PROTECTION_DANGEROUS. */
    const val PROTECTION_DANGEROUS = 1

    private const val P = "android.permission."

    /** Special access: a screen the USER operates. perm -> the settings action that opens it. */
    private val SPECIAL: Map<String, String> = mapOf(
        P + "PACKAGE_USAGE_STATS" to "android.settings.USAGE_ACCESS_SETTINGS",
        P + "SYSTEM_ALERT_WINDOW" to "android.settings.action.MANAGE_OVERLAY_PERMISSION",
        P + "BIND_ACCESSIBILITY_SERVICE" to "android.settings.ACCESSIBILITY_SETTINGS",
        P + "BIND_NOTIFICATION_LISTENER_SERVICE" to "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
        P + "BIND_DEVICE_ADMIN" to "android.settings.SECURITY_SETTINGS",
        P + "BIND_VPN_SERVICE" to "android.settings.VPN_SETTINGS",
        P + "BIND_CREDENTIAL_PROVIDER_SERVICE" to "android.settings.CREDENTIAL_PROVIDER",
        P + "BIND_INPUT_METHOD" to "android.settings.INPUT_METHOD_SETTINGS",
        P + "BIND_APPWIDGET" to "android.settings.APPLICATION_DETAILS_SETTINGS",
        P + "SCHEDULE_EXACT_ALARM" to "android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
        P + "ACCESS_NOTIFICATION_POLICY" to "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS",
    )

    /** Permissions whose grant is an app-op the shell can set. perm -> op name for `appops set`. */
    private val APPOPS: Map<String, String> = mapOf(
        P + "MANAGE_EXTERNAL_STORAGE" to "MANAGE_EXTERNAL_STORAGE",
        P + "REQUEST_INSTALL_PACKAGES" to "REQUEST_INSTALL_PACKAGES",
        P + "WRITE_SETTINGS" to "WRITE_SETTINGS",
    )

    const val FALLBACK_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"

    /** [protection] = the permission's base protection level (PermissionInfo.protection), -1 if unknown. */
    fun kind(perm: String, protection: Int): Kind = when {
        perm in SPECIAL -> Kind.SPECIAL
        perm in APPOPS -> Kind.APPOP
        protection == PROTECTION_DANGEROUS -> Kind.RUNTIME
        else -> Kind.NONE
    }

    fun settingsAction(perm: String): String = SPECIAL[perm] ?: FALLBACK_SETTINGS
    fun appOp(perm: String): String? = APPOPS[perm]

    const val ALL = "All"

    /** The filter's options: "All", then every permission any app declares that is grantable or special. */
    fun filterOptions(apps: List<App>, kindOf: (String) -> Kind): List<String> =
        listOf(ALL) + apps.flatMap { it.declared }.distinct().filter { kindOf(it) != Kind.NONE }
            .sortedBy { short(it).lowercase() }

    /** Dropdown search: options whose short name or full name contains [query]. "All" always stays. */
    fun search(options: List<String>, query: String): List<String> {
        val q = query.trim().lowercase()
        return if (q.isEmpty()) options else options.filter { it == ALL || it.lowercase().contains(q) }
    }

    /** [selected] = [ALL] keeps everything; a permission keeps the apps that declare it. Always A-Z by label. */
    fun view(apps: List<App>, selected: String): List<App> =
        sorted(if (selected == ALL) apps else apps.filter { selected in it.declared })

    fun sorted(apps: List<App>): List<App> = apps.sortedWith(compareBy<App>({ it.label.lowercase() }, { it.pkg }))

    fun short(perm: String): String = perm.substringAfterLast('.')

    /** A permission to grant on one app. */
    data class Step(val pkg: String, val label: String, val perm: String, val kind: Kind) {
        /** The shell command that grants it. */
        fun command(): String = when (kind) {
            Kind.APPOP -> "appops set $pkg ${appOp(perm)} allow"
            else -> "pm grant $pkg $perm"
        }
    }

    /** A permission only the user can give, with the screen that opens it. */
    data class NeedsYou(val pkg: String, val label: String, val perm: String, val action: String)

    data class Plan(
        val steps: List<Step>, val alreadyGranted: Int, val needsYou: List<NeedsYou>,
    ) {
        val apps: Int get() = steps.map { it.pkg }.distinct().size
        fun confirmText(): String = "Grant ${steps.size} permissions to $apps fleet apps?"
    }

    /**
     * The plan: ONLY apps that are ours (constellation-signed), ONLY permissions the shell can
     * give. Held ones are counted, special access goes to [Plan.needsYou], the rest is ignored.
     */
    fun plan(apps: List<App>, kindOf: (String) -> Kind): Plan {
        val steps = ArrayList<Step>(); val need = ArrayList<NeedsYou>(); var held = 0
        for (a in sorted(apps)) {
            if (!a.ours) continue
            for (p in a.declared.distinct()) when (val k = kindOf(p)) {
                Kind.NONE -> {}
                Kind.SPECIAL -> if (p !in a.granted) need += NeedsYou(a.pkg, a.label, p, settingsAction(p)) else held++
                else -> if (p in a.granted) held++ else steps += Step(a.pkg, a.label, p, k)
            }
        }
        return Plan(steps, held, need)
    }

    /** The privileged channel, as the grant sees it. [exec] = the command's output, null when no channel ran it. */
    interface GrantChannel {
        fun up(): Boolean
        fun exec(command: String): String?
    }

    data class Failure(val step: Step, val reason: String)

    data class Summary(
        val granted: Int, val alreadyGranted: Int, val needsYou: Int, val failures: List<Failure>,
    ) {
        fun text(): String = "$granted granted, $alreadyGranted already granted, $needsYou need you" +
            (if (failures.isEmpty()) "" else ", ${failures.size} failed")
    }

    const val DISABLED_REASON = "needs Wireless Dbg/channel up"

    /** Null when the button may run; else why it may not. */
    fun disabledReason(channelUp: Boolean): String? = if (channelUp) null else DISABLED_REASON

    /** Runs [plan] through [channel]; every grant is reported to [log]. Never runs when the channel is down. */
    fun run(plan: Plan, channel: GrantChannel, log: (String) -> Unit = {}): Summary {
        if (!channel.up()) return Summary(0, plan.alreadyGranted, plan.needsYou.size,
            plan.steps.map { Failure(it, DISABLED_REASON) })
        var ok = 0; val bad = ArrayList<Failure>()
        for (s in plan.steps) {
            val out = channel.exec(s.command())
            val why = failure(out)
            if (why == null) { ok++; log("granted ${s.perm} to ${s.pkg} (${s.kind.name.lowercase()})") }
            else { bad += Failure(s, why); log("FAILED ${s.perm} on ${s.pkg}: $why") }
        }
        return Summary(ok, plan.alreadyGranted, plan.needsYou.size, bad)
    }

    /** `pm grant` / `appops set` print nothing on success; null output = no channel answered. */
    fun failure(out: String?): String? {
        if (out == null) return "no channel answered"
        val t = out.trim()
        if (t.isEmpty()) return null
        val l = t.lowercase()
        return if ("exception" in l || "error" in l || "not " in l || "unknown" in l || "fail" in l) t.lineSequence().first().take(160) else null
    }
}
