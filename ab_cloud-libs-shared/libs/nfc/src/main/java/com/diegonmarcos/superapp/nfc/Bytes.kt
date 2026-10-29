package com.diegonmarcos.superapp.nfc

/** Hex spelling and the hex / ASCII dump every NFC view shows. */
object Bytes {
    fun hex(b: ByteArray, sep: String = ""): String = b.joinToString(sep) { "%02X".format(it.toInt() and 0xFF) }

    /** `AB 00 04`, `0xAB,0x00`, `ab:00:04`: any run of hex pairs; null on an odd digit count or a non-hex character. */
    fun parse(text: String): ByteArray? {
        val clean = text.replace(Regex("0x", RegexOption.IGNORE_CASE), "").filter { !it.isWhitespace() && it != ',' && it != ':' && it != '-' }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        if (!clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun ascii(b: ByteArray): String = String(CharArray(b.size) { val c = b[it].toInt() and 0xFF; if (c in 0x20..0x7E) c.toChar() else '.' })

    /** `0000  03 0F D1 01 ...  ..Q..` lines of [width] bytes: offset, hex, ASCII. */
    fun dump(b: ByteArray, width: Int = 8): String = (b.indices step width).joinToString("\n") { o ->
        val row = b.copyOfRange(o, minOf(o + width, b.size))
        "%04X  %-${width * 3}s %s".format(o, hex(row, " "), ascii(row))
    }

    fun u16be(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    fun u16le(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    fun u32be(b: ByteArray, o: Int): Long = (0..3).fold(0L) { a, k -> (a shl 8) or (b[o + k].toLong() and 0xFF) }
}
