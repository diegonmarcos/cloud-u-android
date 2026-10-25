package com.diegonmarcos.clouddrive.storage

import android.content.Context
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig
import java.io.File

/**
 * The phone's copy of the user's Unison pairs (one JSON file, no credential in it) laid over the shipped policy by
 * [UnisonPairs]. Cloud Account owns the other copy; [com.diegonmarcos.clouddrive.storage.StorageCoordinator] keeps the two level.
 */
class UnisonPairStore(ctx: Context) {
    private val file = File(ctx.applicationContext.filesDir, "unison/pairs.json")
    private val storageRoot: String = runCatching { android.os.Environment.getExternalStorageDirectory().absolutePath }.getOrNull() ?: UnisonConfig.DEFAULT_STORAGE_ROOT

    fun text(): String = if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""

    fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }

    fun declared(): UnisonConfig.Declared = UnisonPairs.parse(Declarations.unisonPolicy, text(), storageRoot)

    fun add(d: UnisonPairs.Draft): Result<Unit> =
        UnisonPairs.add(text(), d, Declarations.unisonPolicy, storageRoot).map { write(it) }

    fun remove(id: String) = write(UnisonPairs.remove(text(), id))
}
