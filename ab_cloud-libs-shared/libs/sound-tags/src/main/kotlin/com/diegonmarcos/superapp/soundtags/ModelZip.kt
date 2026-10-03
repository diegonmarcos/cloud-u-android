package com.diegonmarcos.superapp.soundtags

/**
 * #798 a file out of the zip a TFLite model with metadata carries after its flatbuffer (the label
 * list, e.g. YAMNet's yamnet_label_list.txt), so the labels come from the very file that produced
 * the scores and cannot drift from it. The metadata writer stores entries uncompressed with
 * offsets counted from the start of the FILE; a zip appended by other tools counts them from the
 * start of the zip. Both are read: the shift is what the end record's own offset is missing.
 */
object ModelZip {
    private const val EOCD = 0x06054b50L
    private const val CENTRAL = 0x02014b50L
    private const val LOCAL = 0x04034b50L

    /** The stored entry [name], or null when there is no zip or no such entry. */
    fun entry(b: ByteArray, name: String): ByteArray? {
        val eocd = (b.size - 22 downTo maxOf(0, b.size - 22 - 0xFFFF)).firstOrNull { u32(b, it) == EOCD } ?: return null
        val count = u16(b, eocd + 10)
        val cenSize = u32(b, eocd + 12).toInt()
        val shift = eocd - cenSize - u32(b, eocd + 16).toInt()
        var p = eocd - cenSize
        repeat(count) {
            require(p >= 0 && u32(b, p) == CENTRAL) { "corrupt zip central directory" }
            val method = u16(b, p + 10)
            val size = u32(b, p + 20).toInt()
            val nameLen = u16(b, p + 28)
            if (String(b, p + 46, nameLen, Charsets.UTF_8) == name) {
                require(method == 0) { "$name is compressed (method $method); only stored entries are read" }
                val local = u32(b, p + 42).toInt() + shift
                require(local >= 0 && u32(b, local) == LOCAL) { "corrupt zip local header for $name" }
                val data = local + 30 + u16(b, local + 26) + u16(b, local + 28)
                return b.copyOfRange(data, data + size)
            }
            p += 46 + nameLen + u16(b, p + 30) + u16(b, p + 32)
        }
        return null
    }

    /** The non-blank lines of the stored text entry [name]; empty when it is absent. */
    fun lines(b: ByteArray, name: String): List<String> =
        entry(b, name)?.toString(Charsets.UTF_8)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
