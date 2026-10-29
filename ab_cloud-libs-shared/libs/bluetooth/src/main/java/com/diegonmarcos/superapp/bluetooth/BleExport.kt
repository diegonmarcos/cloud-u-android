package com.diegonmarcos.superapp.bluetooth

/**
 * The scanner's export (#1264): every seen device with its parsed advertisement, RSSI history, smoothed RSSI and distance estimate,
 * as JSON or CSV. Plain Kotlin string building (no org.json), so the JVM suite checks the exact text.
 */
object BleExport {
    fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    private fun q(s: String?) = if (s == null) "null" else "\"" + esc(s) + "\""

    fun csvCell(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    data class Row(val device: BleDevice, val adv: Advertisement, val history: List<Int>, val smoothed: Double?, val distance: Double?)

    fun rows(devices: Collection<BleDevice>, history: Map<String, List<Int>>, pathLoss: Double): List<Row> = devices.sortedByDescending { it.rssi }.map { d ->
        val adv = Hex.parse(d.raw)?.let { AdvParser.parse(it) } ?: Advertisement()
        val h = history[d.address].orEmpty()
        val sm = AdvParser.smooth(h)
        Row(d, adv, h, sm, sm?.let { AdvParser.distanceMeters(it.toInt(), adv.txAt1m ?: d.txPower?.minus(41), pathLoss) })
    }

    fun json(rows: List<Row>, companies: CompanyIds?, atMs: Long): String = buildString {
        append("{\"exported_ms\":").append(atMs).append(",\"devices\":[")
        rows.forEachIndexed { i, r ->
            if (i > 0) append(',')
            val d = r.device
            append("{\"address\":").append(q(d.address)).append(",\"name\":").append(q(d.name))
            append(",\"rssi\":").append(d.rssi).append(",\"smoothed_rssi\":").append(r.smoothed?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "null")
            append(",\"distance_m\":").append(r.distance?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "null")
            append(",\"tx_power\":").append(d.txPower ?: "null").append(",\"connectable\":").append(d.connectable)
            append(",\"bonded\":").append(d.bonded).append(",\"classic\":").append(d.classic)
            append(",\"device_class\":").append(d.deviceClass?.let { q(BtClass.major(it)) } ?: "null")
            append(",\"flags\":[").append(r.adv.flagNames.joinToString(",") { q(it) }).append(']')
            append(",\"service_uuids\":[").append(d.serviceUuids.joinToString(",") { q(it) }).append(']')
            append(",\"manufacturer\":[").append(r.adv.manufacturer.entries.joinToString(",") { (k, v) ->
                "{\"id\":$k,\"company\":${q(companies?.name(k) ?: "0x%04X".format(k))},\"data\":${q(Hex.format(v, ""))}}" }).append(']')
            append(",\"beacons\":[").append(r.adv.beacons.joinToString(",") { b ->
                "{\"kind\":${q(b.kind)}," + b.fields.joinToString(",") { (k, v) -> "${q(k)}:${q(v)}" } + "}" }).append(']')
            append(",\"rssi_history\":[").append(r.history.joinToString(",")).append(']')
            append(",\"raw\":").append(q(d.raw)).append('}')
        }
        append("]}")
    }

    val CSV_HEADER = listOf("address", "name", "rssi", "smoothed_rssi", "distance_m", "tx_power", "connectable", "bonded", "classic", "company", "beacon", "services", "raw")

    fun csv(rows: List<Row>, companies: CompanyIds?): String = buildString {
        append(CSV_HEADER.joinToString(",")).append('\n')
        for (r in rows) {
            val d = r.device
            append(listOf(d.address, d.name.orEmpty(), d.rssi.toString(), r.smoothed?.let { "%.1f".format(java.util.Locale.ROOT, it) }.orEmpty(),
                r.distance?.let { "%.2f".format(java.util.Locale.ROOT, it) }.orEmpty(), d.txPower?.toString().orEmpty(), d.connectable.toString(),
                d.bonded.toString(), d.classic.toString(), r.adv.manufacturer.keys.joinToString(" ") { companies?.name(it) ?: "0x%04X".format(it) },
                r.adv.beacons.joinToString(" ") { it.kind }, d.serviceUuids.joinToString(" ") { Uuids.short(it) }, d.raw).joinToString(",") { csvCell(it) })
            append('\n')
        }
    }
}
