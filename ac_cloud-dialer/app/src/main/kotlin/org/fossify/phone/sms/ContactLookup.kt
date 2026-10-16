package org.fossify.phone.sms

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** A contact number the recipient picker offers. */
data class Recipient(val name: String, val number: String, val photoUri: String?)

/**
 * Names and photos for the numbers a conversation shows, and the contact list the recipient picker
 * searches. Reads ContactsContract directly: a missing READ_CONTACTS grant degrades to bare numbers.
 */
class ContactLookup(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val cache = HashMap<String, Pair<String?, String?>>()

    private fun entry(number: String): Pair<String?, String?> = cache.getOrPut(number) {
        if (number.isBlank()) return@getOrPut null to null
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI)
            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) to c.getString(1) else null to null
            } ?: (null to null)
        } catch (e: Exception) {
            null to null
        }
    }

    fun nameOf(number: String): String? = entry(number).first

    fun photoOf(number: String): String? = entry(number).second

    /** Every contact phone number, by name; one row per distinct number. */
    fun recipients(): List<Recipient> {
        val out = ArrayList<Recipient>()
        val seen = HashSet<String>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            )
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(1) ?: continue
                    if (seen.add(Recipients.digits(number))) out.add(Recipient(c.getString(0) ?: number, number, c.getString(2)))
                }
            }
        } catch (e: Exception) {
            // no contacts grant: the picker offers typed numbers only
        }
        return out
    }
}

object Recipients {
    /** The digits (and a leading plus) of a number: what two spellings of one number share. */
    fun digits(number: String): String = number.filter { it.isDigit() || it == '+' }

    /** Contacts matching [query] by name or number, best first; a typed number is offered as itself. */
    fun suggest(all: List<Recipient>, query: String, limit: Int = 30): List<Recipient> {
        val q = query.trim()
        if (q.isEmpty()) return all.take(limit)
        val qd = digits(q)
        val hits = all.filter { it.name.contains(q, ignoreCase = true) || (qd.length >= 2 && digits(it.number).contains(qd)) }
        val typed = if (qd.length >= 3 && qd == q.filter { !it.isWhitespace() && it != '-' && it != '(' && it != ')' }) {
            listOf(Recipient(q, q, null)).filter { t -> hits.none { digits(it.number) == digits(t.number) } }
        } else {
            emptyList()
        }
        return (typed + hits).take(limit)
    }
}
