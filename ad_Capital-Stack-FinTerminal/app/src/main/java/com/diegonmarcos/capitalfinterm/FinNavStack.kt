package com.diegonmarcos.capitalfinterm

/**
 * A tab's back stack (UI spec 4.3 / 4.4), as saveable strings: `list` (the tab's dashboard list), `dash:<id>`, `quote:<symbol>`.
 * A tab opens on [initial] = [list, its default dashboard]; a quote is pushed on top; back pops a quote, then returns a non-default
 * dashboard to the tab's default, then leaves a non-home tab for the home tab ([HOME]), and on the home tab's default lets the system
 * exit (null). The island's own selection never adds to a stack (tabs are peers).
 */
object FinNavStack {
    const val LIST = "list"
    val LIST_ONLY: List<String> = listOf(LIST)
    /** The answer of [back] that means: switch to the home tab. */
    val HOME: List<String> = listOf("__home__")

    fun initial(defaultDashboard: String?): List<String> = if (defaultDashboard == null) LIST_ONLY else listOf(LIST, "dash:$defaultDashboard")

    /** A dashboard replaces the tab's detail (no dashboard history: back goes to the tab's default). */
    fun open(@Suppress("UNUSED_PARAMETER") stack: List<String>, id: String): List<String> = listOf(LIST, "dash:$id")

    fun quote(stack: List<String>, symbol: String): List<String> = if (stack.lastOrNull() == "quote:$symbol") stack else stack + "quote:$symbol"

    fun dashboardOf(stack: List<String>): String? = stack.lastOrNull { it.startsWith("dash:") }?.removePrefix("dash:")

    fun symbolOf(screen: String?): String? = screen?.takeIf { it.startsWith("quote:") }?.removePrefix("quote:")

    fun back(stack: List<String>, defaultDashboard: String?, isHomeTab: Boolean): List<String>? {
        val top = stack.lastOrNull() ?: return if (isHomeTab) null else HOME
        if (top.startsWith("quote:")) return stack.dropLast(1)
        if (defaultDashboard != null && top != "dash:$defaultDashboard") return initial(defaultDashboard)
        return if (isHomeTab) null else HOME
    }
}
