package org.fossify.phone.sms

enum class SmsEncoding { Gsm7, Ucs2 }

/**
 * The text split into the parts the radio will carry. [unitsPerPart] is what one part holds (160 or
 * 153 septets for GSM, 70 or 67 UTF-16 units for UCS-2), [unitsUsed] what the whole text costs.
 */
data class SmsSegments(
    val encoding: SmsEncoding,
    val parts: List<String>,
    val unitsUsed: Int,
    val unitsPerPart: Int,
) {
    val count: Int get() = parts.size
    val remainingInLastPart: Int get() = if (parts.isEmpty()) unitsPerPart else parts.size * unitsPerPart - unitsUsed
}

/**
 * Multipart SMS split (GSM 03.38 / UCS-2). The platform's SmsManager.divideMessage does the same on
 * the wire; this is the pure twin the composer's counter ("2 / 153") and the unit tests run against.
 *
 *  - GSM 7-bit: 160 septets in one part, 153 per part when concatenated (7 septets go to the UDH).
 *    An extension-table character (€ [ ] { } \ ^ ~ |) costs two septets and is never split.
 *  - UCS-2 (any other character): 70 UTF-16 units in one part, 67 concatenated; a surrogate pair
 *    is never split.
 */
object SmsSegmenter {
    const val GSM_SINGLE = 160
    const val GSM_MULTI = 153
    const val UCS2_SINGLE = 70
    const val UCS2_MULTI = 67

    private const val BASIC = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ ÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val EXTENSION = "^{}\\[~]|€\u000C"

    fun isGsm(text: String): Boolean = text.all { it in BASIC || it in EXTENSION }

    private fun cost(c: Char): Int = if (c in BASIC) 1 else 2

    fun split(text: String): SmsSegments {
        if (text.isEmpty()) return SmsSegments(SmsEncoding.Gsm7, emptyList(), 0, GSM_SINGLE)
        return if (isGsm(text)) splitGsm(text) else splitUcs2(text)
    }

    private fun splitGsm(text: String): SmsSegments {
        val total = text.sumOf { cost(it) }
        if (total <= GSM_SINGLE) return SmsSegments(SmsEncoding.Gsm7, listOf(text), total, GSM_SINGLE)
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var used = 0
        for (c in text) {
            val k = cost(c)
            if (used + k > GSM_MULTI) {
                parts.add(cur.toString()); cur.setLength(0); used = 0
            }
            cur.append(c); used += k
        }
        if (cur.isNotEmpty()) parts.add(cur.toString())
        return SmsSegments(SmsEncoding.Gsm7, parts, total, GSM_MULTI)
    }

    private fun splitUcs2(text: String): SmsSegments {
        val total = text.length
        if (total <= UCS2_SINGLE) return SmsSegments(SmsEncoding.Ucs2, listOf(text), total, UCS2_SINGLE)
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < text.length) {
            val pair = text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            val k = if (pair) 2 else 1
            if (cur.length + k > UCS2_MULTI) {
                parts.add(cur.toString()); cur.setLength(0)
            }
            cur.append(text, i, i + k)
            i += k
        }
        if (cur.isNotEmpty()) parts.add(cur.toString())
        return SmsSegments(SmsEncoding.Ucs2, parts, total, UCS2_MULTI)
    }
}
