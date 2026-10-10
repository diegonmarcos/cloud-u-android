package com.diegonmarcos.superapp.autofill

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor

/**
 * Row codecs (pure: a row is `column → text`) and the reader the browser and the keyboard use.
 * Every call is allowed to fail — Cloud Account not installed, permission not granted, provider
 * dead — and then answers empty: autofill is a convenience, never a reason for the caller to
 * crash. Nothing here logs a value.
 */
object AutofillRows {
    private fun Map<String, String>.long(c: String) = this[c]?.toLongOrNull() ?: 0
    private fun Map<String, String>.flag(c: String) = this[c] == "1"
    private fun Map<String, String>.pick(cols: List<String>) = cols.mapNotNull { c -> this[c]?.takeIf { it.isNotBlank() }?.let { c to it } }.toMap()

    fun profile(r: Map<String, String>) = AutofillProfile(r.long(AutofillSot.ID), r[AutofillSot.LABEL].orEmpty(), r.flag(AutofillSot.IS_DEFAULT),
        r.pick(AutofillSot.PROFILE_FIELDS), updatedAt = r.long(AutofillSot.UPDATED_AT))

    fun address(r: Map<String, String>) = AutofillAddress(r.long(AutofillSot.ID), r.long(AutofillSot.PROFILE_ID),
        r[AutofillSot.TYPE].orEmpty().ifBlank { AddressType.HOME }, r[AutofillSot.LABEL].orEmpty(), r.flag(AutofillSot.IS_DEFAULT),
        r.pick(AutofillSot.ADDRESS_FIELDS), r.long(AutofillSot.UPDATED_AT))

    fun contact(r: Map<String, String>) = AutofillContact(r.long(AutofillSot.ID), r.long(AutofillSot.PROFILE_ID),
        r[AutofillSot.C_KIND].orEmpty(), r[AutofillSot.C_VALUE].orEmpty(), r[AutofillSot.TYPE].orEmpty(), r[AutofillSot.C_COUNTRY].orEmpty(),
        r.flag(AutofillSot.IS_DEFAULT), r.long(AutofillSot.UPDATED_AT))

    fun rule(r: Map<String, String>) = SiteRule(r.long(AutofillSot.ID), r[AutofillSot.R_DOMAIN].orEmpty(), r[AutofillSot.R_FORM].orEmpty(),
        r[AutofillSot.R_FIELD].orEmpty(), r[AutofillSot.R_KEY].orEmpty(), r[AutofillSot.R_VALUE].orEmpty(), r[AutofillSot.R_ENABLED] != "0",
        r.long(AutofillSot.UPDATED_AT))

    fun snippet(r: Map<String, String>) = Snippet(r.long(AutofillSot.ID), r[AutofillSot.LABEL].orEmpty(), r[AutofillSot.S_TEXT].orEmpty(),
        r.long(AutofillSot.UPDATED_AT))

    /** Profiles with their addresses and contacts attached. Orphans (no such profile) are dropped. */
    fun assemble(profiles: List<Map<String, String>>, addresses: List<Map<String, String>>, contacts: List<Map<String, String>>): List<AutofillProfile> {
        val a = addresses.map(::address).groupBy { it.profileId }
        val c = contacts.map(::contact).groupBy { it.profileId }
        return profiles.map(::profile).map { p -> p.copy(addresses = a[p.id].orEmpty(), contacts = c[p.id].orEmpty()) }
    }

    private fun flag(b: Boolean) = if (b) "1" else "0"

    /** A row as column → text, without _id/updated_at (the provider sets those). */
    fun columns(p: AutofillProfile): Map<String, String> =
        linkedMapOf(AutofillSot.LABEL to p.label, AutofillSot.IS_DEFAULT to flag(p.isDefault)) + AutofillSot.PROFILE_FIELDS.associateWith { p[it] }

    fun columns(a: AutofillAddress, profileId: Long = a.profileId): Map<String, String> = linkedMapOf(
        AutofillSot.PROFILE_ID to profileId.toString(), AutofillSot.TYPE to a.type, AutofillSot.LABEL to a.label, AutofillSot.IS_DEFAULT to flag(a.isDefault),
    ) + AutofillSot.ADDRESS_FIELDS.associateWith { a[it] }

    fun columns(c: AutofillContact, profileId: Long = c.profileId): Map<String, String> = linkedMapOf(
        AutofillSot.PROFILE_ID to profileId.toString(), AutofillSot.C_KIND to c.kind, AutofillSot.C_VALUE to c.value.trim(),
        AutofillSot.TYPE to c.type, AutofillSot.C_COUNTRY to c.country, AutofillSot.IS_DEFAULT to flag(c.isDefault))

    fun columns(r: SiteRule): Map<String, String> = linkedMapOf(
        AutofillSot.R_DOMAIN to r.domain.trim().lowercase(), AutofillSot.R_FORM to r.formSelector.trim(),
        AutofillSot.R_FIELD to r.fieldSelector.trim(), AutofillSot.R_KEY to r.fieldKey.trim(),
        AutofillSot.R_VALUE to r.literalValue, AutofillSot.R_ENABLED to flag(r.enabled))

    fun columns(s: Snippet): Map<String, String> = linkedMapOf(AutofillSot.LABEL to s.label, AutofillSot.S_TEXT to s.text)

    fun values(m: Map<String, String>): ContentValues = ContentValues().apply { m.forEach { (k, v) -> put(k, v) } }

    fun rows(c: Cursor): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        while (c.moveToNext()) out += c.columnNames.indices.associate { i -> c.getColumnName(i) to (if (c.isNull(i)) "" else c.getString(i).orEmpty()) }
        return out
    }
}

/** Reads (and, for a writer, proposes) SOT rows over Cloud Account's provider. */
object AutofillSotClient {
    fun installed(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(AutofillSot.PKG, 0).enabled
    }.getOrDefault(false)

    fun canRead(ctx: Context): Boolean =
        ctx.checkSelfPermission(AutofillSot.PERMISSION_READ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun read(ctx: Context, path: String): List<Map<String, String>> {
        if (!installed(ctx)) return emptyList()
        return runCatching { ctx.contentResolver.query(AutofillSot.uri(path), null, null, null, null)?.use(AutofillRows::rows) }
            .getOrNull().orEmpty()
    }

    /** Every profile with its addresses and contacts. */
    fun profiles(ctx: Context): List<AutofillProfile> = AutofillRows.assemble(
        read(ctx, AutofillSot.PATH_PROFILES), read(ctx, AutofillSot.PATH_ADDRESSES), read(ctx, AutofillSot.PATH_CONTACTS))
    fun rules(ctx: Context): List<SiteRule> = read(ctx, AutofillSot.PATH_RULES).map(AutofillRows::rule)
    fun snippets(ctx: Context): List<Snippet> = read(ctx, AutofillSot.PATH_SNIPPETS).map(AutofillRows::snippet)

    private fun insert(ctx: Context, path: String, m: Map<String, String>): Long? = runCatching {
        ctx.contentResolver.insert(AutofillSot.uri(path), AutofillRows.values(m))?.let { ContentUris.parseId(it) }
    }.getOrNull()

    /**
     * Writes what the user has just confirmed (the browser's "save this address"): [address] under the
     * existing profile [profileId], or — [profileId] null — a new [profile] with that address and its
     * contacts. Needs [AutofillSot.PERMISSION_WRITE] (insert only); answers whether it landed.
     */
    fun addAddress(ctx: Context, profileId: Long?, profile: AutofillProfile, address: AutofillAddress): Boolean {
        if (!installed(ctx)) return false
        val pid = profileId ?: insert(ctx, AutofillSot.PATH_PROFILES, AutofillRows.columns(profile)) ?: return false
        if (profileId == null) profile.contacts.forEach { insert(ctx, AutofillSot.PATH_CONTACTS, AutofillRows.columns(it, pid)) }
        return insert(ctx, AutofillSot.PATH_ADDRESSES, AutofillRows.columns(address, pid)) != null
    }
}
