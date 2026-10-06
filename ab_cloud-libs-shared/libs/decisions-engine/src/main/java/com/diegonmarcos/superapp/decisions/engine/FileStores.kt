package com.diegonmarcos.superapp.decisions.engine

import com.diegonmarcos.superapp.decisions.core.KvStore
import com.diegonmarcos.superapp.decisions.core.LineSink
import org.json.JSONObject
import java.io.File

/**
 * The engine's state on disk, in filesDir/decisions/ (never shared storage, never backed up off the
 * phone: the app's own directory). A document is replaced atomically (write a sibling, rename over); a
 * write that fails is swallowed, because losing a counter must never fail a decision.
 */
class FileKv(private val file: File) : KvStore {
    override fun read(): JSONObject? = runCatching { JSONObject(file.readText()) }.getOrNull()

    override fun write(doc: JSONObject) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(doc.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}

class FileSink(private val file: File) : LineSink {
    @Synchronized
    override fun append(line: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
        }
    }

    @Synchronized
    override fun tail(n: Int): List<String> =
        runCatching { file.readLines().filter { it.isNotEmpty() }.takeLast(n) }.getOrDefault(emptyList())

    @Synchronized
    override fun trim(keep: Int) {
        runCatching {
            val all = file.readLines().filter { it.isNotEmpty() }
            if (all.size > keep) file.writeText(all.takeLast(keep).joinToString("\n", postfix = "\n"))
        }
    }
}
