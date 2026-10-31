package com.diegonmarcos.cgomniverse

/**
 * Where the app opens: the launcher intent may carry `section` (a declared section id, e.g. store or galaxy) and `world` (a Store
 * world id, opened full screen in Galaxy). The emulator proof uses them to open Store and Galaxy directly (`am start -e section store`).
 * Anything absent or unknown falls back to the declared default page, so a bad extra can never open a page that does not exist.
 */
object Launch {
    const val EXTRA_SECTION = "section"
    const val EXTRA_WORLD = "world"

    data class Start(val section: String, val worldId: String?)

    fun resolve(section: String?, world: String?, sections: Set<String>, worlds: Set<String>, default: String): Start {
        val w = world?.takeIf { it in worlds }
        val s = when {
            w != null -> Decl.SECTION_GALAXY
            section != null && section in sections -> section
            else -> default
        }
        return Start(s, w)
    }
}
