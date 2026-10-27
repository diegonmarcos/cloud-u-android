package com.diegonmarcos.superapp.bluetooth

/** A decoded characteristic value: a one-line [text], the headline number for the graph when there is one, and named detail fields. */
data class Decoded(val text: String, val value: Double? = null, val unit: String = "", val fields: Map<String, String> = emptyMap())

/** The known-service decoders (Bluetooth SIG formats), by the id the catalog's `decoder` names. Short or malformed data decodes to null. */
object GattDecoders {
    private val SENSOR_LOCATIONS = listOf("Other", "Chest", "Wrist", "Finger", "Hand", "Ear lobe", "Foot")

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)
    private fun s16(b: ByteArray, i: Int): Int = u16(b, i).toShort().toInt()

    /** IEEE-11073 32-bit FLOAT: 24-bit signed mantissa, 8-bit signed exponent. */
    fun float32(b: ByteArray, i: Int): Double {
        var mant = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
        if (mant and 0x800000 != 0) mant -= 0x1000000
        val exp = b[i + 3].toInt()
        return mant * Math.pow(10.0, exp.toDouble())
    }

    fun decode(decoder: String, b: ByteArray): Decoded? = when (decoder) {
        "battery_level" -> if (b.isEmpty()) null else u8(b, 0).let { Decoded("$it %", it.toDouble(), "%") }
        "heart_rate" -> heartRate(b)
        "sensor_location" -> if (b.isEmpty()) null else u8(b, 0).let { Decoded(SENSOR_LOCATIONS.getOrElse(it) { "Reserved ($it)" }) }
        "temperature_measurement" -> temperature(b)
        "temperature_c" -> if (b.size < 2) null else (s16(b, 0) / 100.0).let { Decoded(fmt(it) + " C", it, "C") }
        "humidity" -> if (b.size < 2) null else (u16(b, 0) / 100.0).let { Decoded(fmt(it) + " %", it, "%") }
        "pressure" -> if (b.size < 4) null else (u32(b, 0) / 1000.0).let { Decoded(fmt(it) + " hPa", it, "hPa") }
        "csc" -> csc(b)
        "utf8" -> if (b.isEmpty()) null else String(b, Charsets.UTF_8).trimEnd('\u0000').let { Decoded(it) }
        "uint" -> if (b.isEmpty() || b.size > 4) null else littleEndian(b).let { Decoded(it.toString(), it.toDouble()) }
        "cccd" -> if (b.size < 2) null else Decoded(when (u16(b, 0)) { 0 -> "Off"; 1 -> "Notifications"; 2 -> "Indications"; else -> "0x%04X".format(u16(b, 0)) })
        else -> null
    }

    private fun littleEndian(b: ByteArray): Long { var v = 0L; for (i in b.indices) v = v or ((b[i].toLong() and 0xFF) shl (8 * i)); return v }

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else "%.2f".format(d)

    private fun heartRate(b: ByteArray): Decoded? {
        if (b.size < 2) return null
        val flags = u8(b, 0)
        val wide = flags and 0x01 != 0
        var i = 1
        if (b.size < i + (if (wide) 2 else 1)) return null
        val bpm = if (wide) u16(b, i) else u8(b, i)
        i += if (wide) 2 else 1
        val fields = LinkedHashMap<String, String>()
        when ((flags shr 1) and 0x03) { 2 -> fields["contact"] = "no contact"; 3 -> fields["contact"] = "contact" }
        if (flags and 0x08 != 0 && b.size >= i + 2) { fields["energy"] = "${u16(b, i)} kJ"; i += 2 }
        if (flags and 0x10 != 0) {
            val rr = ArrayList<String>()
            while (b.size >= i + 2) { rr.add("%.0f ms".format(u16(b, i) * 1000.0 / 1024.0)); i += 2 }
            if (rr.isNotEmpty()) fields["rr"] = rr.joinToString(", ")
        }
        return Decoded("$bpm bpm", bpm.toDouble(), "bpm", fields)
    }

    private fun temperature(b: ByteArray): Decoded? {
        if (b.size < 5) return null
        val flags = u8(b, 0)
        val fahrenheit = flags and 0x01 != 0
        val v = float32(b, 1)
        val unit = if (fahrenheit) "F" else "C"
        return Decoded(fmt(Math.round(v * 100) / 100.0) + " " + unit, v, unit)
    }

    private fun csc(b: ByteArray): Decoded? {
        if (b.isEmpty()) return null
        val flags = u8(b, 0)
        var i = 1
        val fields = LinkedHashMap<String, String>()
        if (flags and 0x01 != 0) {
            if (b.size < i + 6) return null
            fields["wheel revs"] = u32(b, i).toString(); fields["wheel event"] = "%.3f s".format(u16(b, i + 4) / 1024.0); i += 6
        }
        if (flags and 0x02 != 0) {
            if (b.size < i + 4) return null
            fields["crank revs"] = u16(b, i).toString(); fields["crank event"] = "%.3f s".format(u16(b, i + 2) / 1024.0)
        }
        if (fields.isEmpty()) return null
        return Decoded(fields.entries.joinToString(", ") { "${it.key} ${it.value}" }, fields["crank revs"]?.toDoubleOrNull() ?: fields["wheel revs"]?.toDoubleOrNull(), "", fields)
    }

    /** The decoded value of [char] by the catalog's decoder for it, falling back to ASCII when every byte is printable. */
    fun describe(catalog: GattCatalog, charUuid: String, b: ByteArray): Decoded {
        decode(catalog.decoderFor(charUuid), b)?.let { return it }
        val printable = b.isNotEmpty() && b.all { it in 0x20..0x7E }
        return if (printable) Decoded("\"" + String(b, Charsets.US_ASCII) + "\"") else Decoded(Hex.format(b))
    }
}
