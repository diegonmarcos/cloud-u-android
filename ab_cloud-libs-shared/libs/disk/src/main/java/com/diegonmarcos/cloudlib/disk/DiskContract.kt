package com.diegonmarcos.cloudlib.disk

/**
 * #813 THE CONTRACT of the Disk Management engine. [VERSION] is in every answer
 * ([DiskEngine] JSON `contract`), so a page or an agent reading /api/disk/<op> can tell which shape
 * it got. Bump it when a field changes meaning or disappears; adding a field is not a bump.
 *
 * The ops are the /api/disk/<op> routes and the page's sections, one list for both.
 */
object DiskContract {
    const val VERSION = 1
    const val GROUP = "disk"

    const val OP_VOLUMES = "volumes"
    const val OP_MAP = "map"
    const val OP_HUGE = "huge"
    const val OP_DUPLICATES = "duplicates"
    const val OP_APPS = "apps"
    const val OP_CLEAN = "clean"
    const val OP_MEMORY = "memory"

    val OPS = listOf(OP_VOLUMES, OP_MAP, OP_HUGE, OP_DUPLICATES, OP_APPS, OP_CLEAN, OP_MEMORY)
}
