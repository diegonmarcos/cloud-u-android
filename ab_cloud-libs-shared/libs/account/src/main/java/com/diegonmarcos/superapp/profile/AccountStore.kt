package com.diegonmarcos.superapp.profile

import org.json.JSONObject
import java.io.File

/**
 * #778 The three Account files — S (server), R (runtime), L (local) — side by side in app storage,
 * one pattern each:
 *
 *     {"_meta": {"file": "S|R|L", "source": …, "sha256": …, "at": …}, "body": {…vault bundle shape…},
 *      "apps": {…}}            // R only: each app's status, what it observed, what it cannot take back
 *
 * `sha256` is [AccountDrift.sha256] of the body, so a file whose body no longer matches its
 * metadata reads as [Doc.intact] false instead of passing for the file it claims to be.
 *
 * The bytes go through [Io]: on the phone an EncryptedFile per slot (the bodies carry the vault's
 * tokens and passwords, exactly as the fetched export does), in the JVM suite plain files.
 */
class AccountStore(private val dir: File, private val io: Io) {

    /** How a slot's bytes reach the disk. */
    interface Io {
        fun read(file: File): ByteArray?
        fun write(file: File, bytes: ByteArray)
    }

    /** Plain files — the JVM suite's [Io]. */
    object PlainIo : Io {
        override fun read(file: File): ByteArray? = if (file.isFile) file.readBytes() else null
        override fun write(file: File, bytes: ByteArray) { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
    }

    enum class Slot { S, R, L }

    data class Meta(val slot: Slot, val source: String, val sha256: String, val at: String) {
        fun json(): JSONObject = JSONObject().put("file", slot.name).put("source", source).put("sha256", sha256).put("at", at)
    }

    /** One stored file. [intact] false: the body's hash is not the one its metadata recorded. */
    data class Doc(val meta: Meta, val body: JSONObject, val apps: JSONObject?, val intact: Boolean) {
        fun json(): JSONObject = JSONObject().put("_meta", meta.json()).put("body", body).apply { apps?.let { put("apps", it) } }
    }

    private fun file(slot: Slot) = File(dir, "${slot.name}.json")

    fun read(slot: Slot): Doc? {
        val bytes = runCatching { io.read(file(slot)) }.getOrNull() ?: return null
        val o = runCatching { JSONObject(String(bytes)) }.getOrNull() ?: return null
        val m = o.optJSONObject("_meta") ?: return null
        val body = o.optJSONObject("body") ?: JSONObject()
        val sha = m.optString("sha256")
        return Doc(Meta(slot, m.optString("source"), sha, m.optString("at")), body, o.optJSONObject("apps"),
            intact = sha == AccountDrift.sha256(body))
    }

    /** Write [body] into [slot] with fresh metadata; returns what was stored. */
    fun write(slot: Slot, body: JSONObject, source: String, at: String, apps: JSONObject? = null): Doc {
        val doc = Doc(Meta(slot, source, AccountDrift.sha256(body), at), AccountDrift.copy(body), apps, intact = true)
        io.write(file(slot), doc.json().toString(2).toByteArray())
        return doc
    }

    fun delete(slot: Slot) { file(slot).delete() }

    /** The slot's file as it is exported (share sheet / shared storage) — null when the slot is empty. */
    fun export(slot: Slot): String? = read(slot)?.json()?.toString(2)
}
