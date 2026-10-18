package com.diegonmarcos.clouddrive.files.convert

import com.diegonmarcos.clouddrive.files.FileOps
import java.io.File
import java.io.IOException

/**
 * One file-format conversion the row menu can offer. Add a format by implementing this and
 * listing it in [ConverterRegistry.default]: the "Convert…" item and its dialog read the
 * registry, so nothing else changes. Every converter is bundled and offline.
 */
interface Converter {
    /** Stable id, e.g. `mhtml-html`. */
    val id: String

    /** What the dialog row says it produces, e.g. `HTML + assets folder`. */
    val label: String

    /** True for a (lower-case, dot-less) source extension this converter reads. */
    fun accepts(extension: String): Boolean

    /**
     * Converts [source], writing NEW files into [destDir] (never overwriting: names are made
     * unique). Returns the files and folders written. Throws [IOException] with a message the
     * snackbar can show; [cancelled] is polled between steps.
     */
    @Throws(IOException::class)
    fun convert(source: File, destDir: File, progress: FileOps.Progress, cancelled: () -> Boolean): List<File>
}

/** The list the menu is driven by. Immutable; [plus] returns a registry with one more converter. */
class ConverterRegistry(val converters: List<Converter>) {

    /** The converters that read [fileName]'s extension, in registry order. Empty = no "Convert…" item. */
    fun forName(fileName: String): List<Converter> {
        val ext = FileOps.extensionOf(fileName)
        return if (ext.isEmpty()) emptyList() else converters.filter { it.accepts(ext) }
    }

    fun plus(converter: Converter) = ConverterRegistry(converters + converter)

    companion object {
        /** What ships: the converters in [Converters]. */
        val default: ConverterRegistry by lazy { ConverterRegistry(Converters.ALL) }
    }
}
