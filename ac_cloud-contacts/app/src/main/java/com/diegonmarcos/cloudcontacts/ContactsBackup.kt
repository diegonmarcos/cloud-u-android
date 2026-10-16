package com.diegonmarcos.cloudcontacts

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import com.diegonmarcos.superapp.fleetconfig.SetupStores

/**
 * The phone's contacts as one migrating store, `contacts_vcards` (file `contacts`, keys `vcf` and
 * `count`), so Cloud Account's backup carries them in the device file and its restore writes them back.
 * Export reads every contact from the system provider — names, phones, emails, postal addresses,
 * organisation, birthday, note; no photos — as vCard 3.0. Apply inserts each card that is not already on
 * the phone (same name and same first phone or email) into a phone-local account, and never deletes.
 */
object ContactsBackup : SetupStores.SetupHandler {
    const val STORE = "contacts_vcards"
    const val FILE = "contacts"

    private class Card(var name: String = "", var given: String = "", var family: String = "",
                       val phones: MutableList<String> = ArrayList(), val emails: MutableList<String> = ArrayList(),
                       val addrs: MutableList<String> = ArrayList(), var org: String = "", var bday: String = "",
                       var note: String = "") {
        fun key() = (name.lowercase().trim() + "|" + (phones.firstOrNull()?.filter { it.isDigit() }?.takeLast(9)
            ?: emails.firstOrNull()?.lowercase() ?: ""))
    }

    override fun read(ctx: Context, file: String): Map<String, Any?>? {
        if (file != FILE) return null
        val cards = exportCards(ctx)
        return mapOf("vcf" to cards.joinToString("") { toVcard(it) }, "count" to cards.size.toLong())
    }

    override fun write(ctx: Context, file: String, set: Map<String, Any?>, remove: Set<String>): Boolean {
        if (file != FILE) return false
        val vcf = set["vcf"] as? String ?: return remove.isEmpty() || set.isEmpty()
        val have = exportCards(ctx).map { it.key() }.toSet()
        val resolver = ctx.contentResolver
        var batch = ArrayList<ContentProviderOperation>()
        fun flush() { if (batch.isNotEmpty()) { resolver.applyBatch(android.provider.ContactsContract.AUTHORITY, batch); batch = ArrayList() } }
        for (c in parse(vcf)) {
            if (c.name.isBlank() && c.phones.isEmpty() && c.emails.isEmpty()) continue
            if (c.key() in have) continue
            // A contact never spans two batches, so its back reference is its own index in this batch.
            if (batch.size > 400) flush()
            val back = batch.size
            batch += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null).withValue(RawContacts.ACCOUNT_NAME, null).build()
            fun row(mime: String, vararg kv: Pair<String, Any?>) {
                val b = ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, back)
                    .withValue(Data.MIMETYPE, mime)
                kv.forEach { (k, v) -> b.withValue(k, v) }
                batch += b.build()
            }
            row(StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME to c.name,
                StructuredName.GIVEN_NAME to c.given.ifBlank { null }, StructuredName.FAMILY_NAME to c.family.ifBlank { null })
            c.phones.forEach { row(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to it, Phone.TYPE to Phone.TYPE_MOBILE) }
            c.emails.forEach { row(Email.CONTENT_ITEM_TYPE, Email.ADDRESS to it, Email.TYPE to Email.TYPE_HOME) }
            c.addrs.forEach { row(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS to it, StructuredPostal.TYPE to StructuredPostal.TYPE_HOME) }
            if (c.org.isNotBlank()) row(Organization.CONTENT_ITEM_TYPE, Organization.COMPANY to c.org)
            if (c.bday.isNotBlank()) row(Event.CONTENT_ITEM_TYPE, Event.START_DATE to c.bday, Event.TYPE to Event.TYPE_BIRTHDAY)
            if (c.note.isNotBlank()) row(Note.CONTENT_ITEM_TYPE, Note.NOTE to c.note)
        }
        flush()
        return true
    }

    private fun exportCards(ctx: Context): List<Card> {
        val byRaw = LinkedHashMap<Long, Card>()
        val proj = arrayOf(Data.RAW_CONTACT_ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3)
        ctx.contentResolver.query(Data.CONTENT_URI, proj, null, null, Data.RAW_CONTACT_ID)?.use { c ->
            while (c.moveToNext()) {
                val card = byRaw.getOrPut(c.getLong(0)) { Card() }
                val d1 = c.getString(2).orEmpty()
                when (c.getString(1)) {
                    StructuredName.CONTENT_ITEM_TYPE -> { card.name = d1; card.given = c.getString(3).orEmpty(); card.family = c.getString(4).orEmpty() }
                    Phone.CONTENT_ITEM_TYPE -> if (d1.isNotBlank() && d1 !in card.phones) card.phones += d1
                    Email.CONTENT_ITEM_TYPE -> if (d1.isNotBlank() && d1 !in card.emails) card.emails += d1
                    StructuredPostal.CONTENT_ITEM_TYPE -> if (d1.isNotBlank()) card.addrs += d1
                    Organization.CONTENT_ITEM_TYPE -> card.org = d1
                    Event.CONTENT_ITEM_TYPE -> if (c.getString(3) == Event.TYPE_BIRTHDAY.toString()) card.bday = d1
                    Note.CONTENT_ITEM_TYPE -> card.note = d1
                }
            }
        }
        // The same person linked from several accounts appears once.
        return byRaw.values.filter { it.name.isNotBlank() || it.phones.isNotEmpty() || it.emails.isNotEmpty() }
            .distinctBy { it.key() }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    private fun unesc(s: String) = s.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun toVcard(c: Card): String = buildString {
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        append("FN:").append(esc(c.name)).append("\r\n")
        append("N:").append(esc(c.family)).append(';').append(esc(c.given)).append(";;;\r\n")
        c.phones.forEach { append("TEL:").append(esc(it)).append("\r\n") }
        c.emails.forEach { append("EMAIL:").append(esc(it)).append("\r\n") }
        c.addrs.forEach { append("ADR:;;").append(esc(it)).append(";;;;\r\n") }
        if (c.org.isNotBlank()) append("ORG:").append(esc(c.org)).append("\r\n")
        if (c.bday.isNotBlank()) append("BDAY:").append(esc(c.bday)).append("\r\n")
        if (c.note.isNotBlank()) append("NOTE:").append(esc(c.note)).append("\r\n")
        append("END:VCARD\r\n")
    }

    private fun parse(vcf: String): List<Card> {
        val out = ArrayList<Card>(); var cur: Card? = null
        for (raw in vcf.split("\r\n", "\n")) {
            val line = raw.trimEnd(); if (line.isEmpty()) continue
            val colon = line.indexOf(':'); if (colon < 0) continue
            val tag = line.substring(0, colon).substringBefore(';').uppercase(); val v = line.substring(colon + 1)
            when (tag) {
                "BEGIN" -> cur = Card()
                "END" -> { cur?.let { out += it }; cur = null }
                "FN" -> cur?.name = unesc(v)
                "N" -> cur?.let { val p = v.split(';'); it.family = unesc(p.getOrElse(0) { "" }); it.given = unesc(p.getOrElse(1) { "" }) }
                "TEL" -> cur?.phones?.add(unesc(v))
                "EMAIL" -> cur?.emails?.add(unesc(v))
                "ADR" -> cur?.addrs?.add(unesc(v.split(';').getOrElse(2) { v }))
                "ORG" -> cur?.org = unesc(v)
                "BDAY" -> cur?.bday = unesc(v)
                "NOTE" -> cur?.note = unesc(v)
            }
        }
        return out
    }
}
