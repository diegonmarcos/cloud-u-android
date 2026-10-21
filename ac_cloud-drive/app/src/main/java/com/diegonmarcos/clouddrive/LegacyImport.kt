package com.diegonmarcos.clouddrive

import com.diegonmarcos.clouddrive.sync.StoreLegs
import java.io.File

/**
 * One-time, idempotent import of the legacy CloudDrive clones (`<shared storage>/CloudDrive/git/<name>`)
 * into the shared store's git folder, run by the seed worker once the store is verified. It replaces
 * the manual `cp -a` the terminal used to print: a store whose git folder is empty is filled here,
 * and whatever is not imported is cloned from the declared repository list by the seed loop after it.
 *
 * Only a COMPLETE legacy clone is imported ([StoreMigration.isComplete]); a destination that is
 * already a repository is never touched; the copy goes through [StoreLegs.Copier] (replace = true,
 * so it lands beside the target and swaps in) and is VERIFIED before it counts. The legacy copy is
 * never deleted. Pure over java.io.File plus two injected seams, so the JVM suite runs what the phone runs.
 */
object LegacyImport {

    data class Item(val name: String, val from: File, val to: File)
    data class Outcome(val name: String, val ok: Boolean, val detail: String) { fun line() = "legacy-import ${if (ok) "imported" else "FAILED"} $name — $detail" }

    /** The complete legacy clones the store does not already hold as a repository, by name. */
    fun plan(legacyGit: File, storeGit: File, isRepository: (File) -> Boolean): List<Item> =
        legacyGit.listFiles().orEmpty().filter { StoreMigration.isComplete(it) }.sortedBy { it.name }
            .map { Item(it.name, it, File(storeGit, it.name)) }
            .filter { !isRepository(it.to) }

    fun run(items: List<Item>, copier: StoreLegs.Copier, isRepository: (File) -> Boolean): List<Outcome> = items.map { i ->
        val err = runCatching { copier.copy(i.from.absolutePath, i.to.absolutePath, true) }.getOrElse { it.message ?: it.javaClass.simpleName }
        when {
            err != null -> Outcome(i.name, false, err)
            !isRepository(i.to) -> Outcome(i.name, false, "copied but ${i.to.name} is not a repository in the store")
            else -> Outcome(i.name, true, i.to.absolutePath)
        }
    }
}
