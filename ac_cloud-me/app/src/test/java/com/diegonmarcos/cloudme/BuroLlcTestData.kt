package com.diegonmarcos.cloudme

import java.io.File

/** The REAL declarations (build.json, data/ui), read from the source tree: a test never carries its own copy. */
object BuroLlcTestData {
    fun text(rel: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("$rel not found above ${File("").absolutePath}")
    }
}
