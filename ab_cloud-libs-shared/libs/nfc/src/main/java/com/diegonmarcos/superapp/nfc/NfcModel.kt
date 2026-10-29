package com.diegonmarcos.superapp.nfc

/** One labelled section of what a tag told us (Identity, NfcA, MIFARE Ultralight, NDEF...). */
data class InfoSection(val title: String, val rows: List<Pair<String, String>>, val dump: String = "")

/** Everything one scan learned. [uid] is the anticollision UID in hex; [techs] the Android tech short names. */
data class TagInfo(
    val atMs: Long,
    val uid: String,
    val techs: List<String>,
    val type: String,
    val sections: List<InfoSection> = emptyList(),
    val ndef: List<NdefRecord> = emptyList(),
    val ndefRaw: ByteArray = ByteArray(0),
    val ndefWritable: Boolean? = null,
    val ndefCapacity: Int? = null,
    val error: String = "",
) {
    val ndefSummary: String get() = if (ndef.isEmpty()) "" else Ndef.summary(ndef)
}

/** One history line: only what may be kept (no tag secret; a Wi-Fi key is never kept). */
data class ScanEntry(val atMs: Long, val uid: String, val type: String, val techs: List<String>
    , val ndef: String, val note: String = "")

/** The scan log's export and its persisted form, plain string building so the JVM suite checks exact text. */
object NfcHistory {
    const val MAX = 500

    fun entry(t: TagInfo): ScanEntry = ScanEntry(t.atMs, t.uid, t.type, t.techs, t.ndefSummary, t.error)

    fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    private fun q(s: String) = "\"" + esc(s) + "\""

    fun json(entries: List<ScanEntry>): String = entries.joinToString(",", "[", "]") { e ->
        "{\"at_ms\":${e.atMs},\"uid\":${q(e.uid)},\"type\":${q(e.type)},\"techs\":[${e.techs.joinToString(",") { q(it) }}],\"ndef\":${q(e.ndef)},\"note\":${q(e.note)}}"
    }

    fun csvCell(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    val CSV_HEADER = "at_ms,uid,type,techs,ndef,note"

    fun csv(entries: List<ScanEntry>): String = buildString {
        append(CSV_HEADER).append('\n')
        for (e in entries) append(listOf(e.atMs.toString(), e.uid, e.type, e.techs.joinToString(" "), e.ndef, e.note).joinToString(",") { csvCell(it) }).append('\n')
    }

    /** The persisted list (the same JSON as the export), read back with org.json. */
    fun parse(text: String): List<ScanEntry> = runCatching {
        val a = org.json.JSONArray(text)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val t = o.optJSONArray("techs")
            ScanEntry(o.getLong("at_ms"), o.optString("uid"), o.optString("type"), if (t == null) emptyList() else List(t.length()) { t.getString(it) },
                o.optString("ndef"), o.optString("note"))
        }
    }.getOrDefault(emptyList())
}
