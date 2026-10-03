package com.diegonmarcos.superapp.contacts

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Turns a social-network export file into [RawContact]s.
 *
 * Clean-room implementation (#828): written from the behaviour spec in
 * a0_docs/eng-specs/social-import.md and the public LinkedIn / Instagram
 * export formats only. Own code, owner licence.
 *
 * Supported: LinkedIn `Connections.csv` (source "linkedin") and Instagram
 * followers/following JSON (source "instagram"). Anything else -> null.
 */
object SocialImport {

    data class Result(val source: String, val contacts: List<RawContact>)

    fun parse(text: String): Result? = try {
        val body = text.removePrefix("﻿").trim()
        when {
            body.isEmpty() -> null
            body[0] == '[' || body[0] == '{' -> fromInstagram(body)
            else -> fromLinkedIn(body)
        }
    } catch (_: Exception) {
        null
    }

    // ---- LinkedIn ---------------------------------------------------------

    private fun fromLinkedIn(body: String): Result? {
        val rows = csvRows(body)
        val headerAt = rows.indexOfFirst { row ->
            val keys = row.map { it.trim().lowercase() }
            "first name" in keys && "last name" in keys
        }
        if (headerAt < 0) return null
        val columns = rows[headerAt].map { it.trim().lowercase() }
        fun pick(row: List<String>, key: String): String {
            val i = columns.indexOf(key)
            return if (i in row.indices) row[i].trim() else ""
        }
        val out = ArrayList<RawContact>()
        for (row in rows.drop(headerAt + 1)) {
            val fullName = "${pick(row, "first name")} ${pick(row, "last name")}"
                .trim().replace(Regex("\\s+"), " ")
            val email = pick(row, "email address")
            val url = pick(row, "url")
            if (fullName.isEmpty() && email.isEmpty() && url.isEmpty()) continue
            val slug = Regex("/in/([^/?#]+)").find(url)?.groupValues?.get(1).orEmpty()
            out += RawContact(
                source = "linkedin",
                name = fullName,
                emails = listOfNotBlank(email),
                org = pick(row, "company"),
                title = pick(row, "position"),
                urls = listOfNotBlank(url),
                handles = if (slug.isEmpty()) emptyMap() else mapOf("linkedin" to slug),
            )
        }
        return Result("linkedin", out)
    }

    /** RFC 4180 reader: quoted fields, doubled quotes, newlines inside quotes. */
    private fun csvRows(s: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { fields.add(cell.toString()); cell.setLength(0) }
        fun endRow() { endCell(); rows.add(fields); fields = ArrayList() }
        while (i < s.length) {
            val ch = s[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                ',' -> endCell()
                '\r' -> { endRow(); if (i + 1 < s.length && s[i + 1] == '\n') i++ }
                '\n' -> endRow()
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || fields.isNotEmpty()) endRow()
        return rows
    }

    // ---- Instagram --------------------------------------------------------

    private fun fromInstagram(body: String): Result? {
        val entries = ArrayList<JSONObject>()
        when (val root = JSONTokener(body).nextValue()) {
            is JSONArray -> collectEntries(root, entries)
            is JSONObject -> root.keys().forEach { k ->
                (root.opt(k) as? JSONArray)?.let { collectEntries(it, entries) }
            }
            else -> return null
        }
        if (entries.isEmpty()) return null
        val seen = HashSet<String>()
        val out = ArrayList<RawContact>()
        for (e in entries) {
            val user = usernameOf(e) ?: continue
            if (!seen.add(user.lowercase())) continue
            out += RawContact(
                source = "instagram",
                name = user,
                urls = listOf("https://www.instagram.com/$user"),
                handles = mapOf("instagram" to user),
            )
        }
        return Result("instagram", out)
    }

    private fun collectEntries(arr: JSONArray, into: MutableList<JSONObject>) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.has("string_list_data") || o.has("title")) into += o
        }
    }

    private fun usernameOf(entry: JSONObject): String? {
        val items = entry.optJSONArray("string_list_data")
        var href = ""
        if (items != null) for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val v = item.optString("value").trim()
            if (v.isNotEmpty()) return v
            if (href.isEmpty()) href = item.optString("href").trim()
        }
        val title = entry.optString("title").trim()
        if (title.isNotEmpty()) return title
        val tail = href.trimEnd('/').substringAfterLast('/').substringBefore('?').trim()
        return tail.ifEmpty { null }
    }

    private fun listOfNotBlank(v: String): List<String> = if (v.isBlank()) emptyList() else listOf(v)
}
