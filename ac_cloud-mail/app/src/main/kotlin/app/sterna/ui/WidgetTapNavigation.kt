package app.sterna.ui

/** What a consumed widget tap does to the back stack. Two arms, and no third. */
enum class WidgetTapReturn {

    /** Pop back to the list, so the unified inbox the tap asked for is what the user SEES. */
    BackToList,

    /** Leave the back stack exactly as it is. The list underneath still changes selection. */
    StayPut,
}

/**
 * Whether a home-screen widget tap may unwind the back stack (#112), decided from the WHOLE STACK of
 */
object WidgetTapNavigation {

    /**
     * [backStack] is oldest-first, the order `NavController.currentBackStack` hands back. The
     * reachable stacks and their verdicts are all run in `WidgetTapNavigationTest`.
     */
    fun from(backStack: List<String?>): WidgetTapReturn {
        val screens = backStack.map(::destination)
        // lastIndexOf, not indexOf: whatever came before a second visit to the list is already
        // behind it and would not be popped.
        val list = screens.lastIndexOf(INBOX)
        if (list < 0) return WidgetTapReturn.StayPut
        val above = screens.subList(list + 1, screens.size)
        // Nothing above the list: the unified view is already on screen.
        if (above.isEmpty()) return WidgetTapReturn.StayPut
        return if (above.all { it == MESSAGE }) WidgetTapReturn.BackToList else WidgetTapReturn.StayPut
    }

    private fun destination(route: String?): String? =
        route?.takeWhile { it != '/' && it != '?' }

    /** The list's route in `SternaApp`'s NavHost — the floor the caller pops back to. */
    private const val INBOX = "inbox"

    /** The reader's route in `SternaApp`'s NavHost, and the only destination that is popped. */
    private const val MESSAGE = "message"
}
