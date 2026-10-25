package com.diegonmarcos.cloudme.health

import java.io.File

/** The REAL declared data files (ac_cloud-me/data/health), read from the source tree: a test never carries its own copy of the plan. */
object HealthTestData {
    private fun file(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("$rel not found above ${File("").absolutePath}")
    }

    fun text(rel: String): String = file(rel).readText()

    fun content(): HealthContent = HealthContent.parse(
        text("data/health/${HealthContent.HOME}"), text("data/health/${HealthContent.FORMULAS}"),
        text("data/health/${HealthContent.PLAN}"), text("data/health/${HealthContent.WORKOUT}"),
    )
}
