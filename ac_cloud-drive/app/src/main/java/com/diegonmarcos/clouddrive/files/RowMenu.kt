package com.diegonmarcos.clouddrive.files

import com.diegonmarcos.clouddrive.files.convert.ConverterRegistry

/**
 * Which of the per-file dropdown's registry-driven items a row shows. Pure and ordered, so the
 * menu is DATA: Open with… for any local file, Unzip… only when [ArchiveExtractor] can read the
 * type, Convert… only when a [ConverterRegistry] entry accepts it. FilesScreen renders exactly
 * this list; RowMenuTest holds it (an empty registry means no Convert… item anywhere).
 */
object RowMenu {

    enum class Item { OPEN_WITH, UNZIP, CONVERT }

    fun itemsFor(name: String, isDirectory: Boolean, isLocal: Boolean, registry: ConverterRegistry): List<Item> {
        if (isDirectory || !isLocal) return emptyList()
        return buildList {
            add(Item.OPEN_WITH)
            if (ArchiveExtractor.kindOf(name) != null) add(Item.UNZIP)
            if (registry.forName(name).isNotEmpty()) add(Item.CONVERT)
        }
    }
}
