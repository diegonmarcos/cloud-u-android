package com.diegonmarcos.superapp.bluetooth

/** Bluetooth UUID spelling (#1221): 16-bit and 32-bit short forms live on the SIG base UUID; everything is compared lower-case 128-bit. */
object Uuids {
    const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
    private val FULL = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val SHORT = Regex("^(0x)?([0-9a-f]{4}|[0-9a-f]{8})$")

    /** `180d`, `0x180D`, `0000180d` or a full UUID, to the full lower-case form; null when it is none of those. */
    fun normalize(text: String?): String? {
        val t = text?.trim()?.lowercase() ?: return null
        if (FULL.matches(t)) return t
        val m = SHORT.matchEntire(t) ?: return null
        val hex = m.groupValues[2]
        return (if (hex.length == 4) "0000$hex" else hex) + BASE_SUFFIX
    }

    /** The shortest honest spelling: `180d` for a 16-bit SIG UUID, the full form otherwise. */
    fun short(uuid: String): String {
        val u = normalize(uuid) ?: return uuid
        return if (u.endsWith(BASE_SUFFIX) && u.startsWith("0000")) u.substring(4, 8) else u
    }

    /** 16-bit assigned number of [uuid], or null when it is a vendor 128-bit UUID. */
    fun assigned(uuid: String): Int? {
        val u = normalize(uuid) ?: return null
        return if (u.endsWith(BASE_SUFFIX) && u.startsWith("0000")) u.substring(4, 8).toInt(16) else null
    }
}

/** Hex spelling of bytes, as a Dev-tab write field and the console show them. */
object Hex {
    fun format(b: ByteArray, sep: String = " "): String = b.joinToString(sep) { "%02X".format(it.toInt() and 0xFF) }

    /** `AB 00 04`, `0xAB,0x00`, `ab0004`: any run of hex pairs; null on an odd digit count or a non-hex character. */
    fun parse(text: String): ByteArray? {
        val clean = text.replace(Regex("0x", RegexOption.IGNORE_CASE), "").filter { !it.isWhitespace() && it != ',' && it != ':' && it != '-' }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        if (!clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
