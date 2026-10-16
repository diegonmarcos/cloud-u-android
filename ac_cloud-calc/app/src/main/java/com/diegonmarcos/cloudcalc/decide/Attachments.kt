package com.diegonmarcos.cloudcalc.decide

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.diegonmarcos.cloudcalc.jev.JevRouter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** One thing attached to a Jev request. [data] is base64 for an image or audio, the text itself for a text file, empty for a file only named. */
data class Attachment(val name: String, val kind: Kind, val mime: String, val data: String, val size: Long) {
    enum class Kind { IMAGE, AUDIO, TEXT, FILE }
}

/** Turns attachments into what the model is sent: content parts (text + image_url + input_audio), and reads them from the phone. */
object Attachments {
    const val MAX_TEXT = 20_000
    const val MAX_AUDIO_BYTES = 8_000_000
    const val IMAGE_SIDE = 1024
    private val TEXT_EXT = setOf("txt", "md", "csv", "tsv", "json", "xml", "html", "log", "yaml", "yml", "kt", "py", "js", "sh")
    private val AUDIO_EXT = mapOf("wav" to "wav", "mp3" to "mp3")

    /** The request as the Decisions API takes it: the plain `{"request"}` object with nothing attached, else content parts. */
    fun state(request: String, atts: List<Attachment>): Any {
        if (atts.isEmpty()) return JevRouter.state(request)
        val text = StringBuilder(request)
        for (a in atts) when (a.kind) {
            Attachment.Kind.TEXT -> text.append("\n\n[attached file ").append(a.name).append("]\n").append(a.data)
            Attachment.Kind.FILE -> text.append("\n\n[attached file ").append(a.name).append(" (").append(a.mime).append(", ").append(a.size).append(" bytes), content not readable here]")
            else -> Unit
        }
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text.toString()))
        for (a in atts) when (a.kind) {
            Attachment.Kind.IMAGE -> parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${a.mime};base64,${a.data}")))
            Attachment.Kind.AUDIO -> parts.put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", a.data).put("format", AUDIO_EXT[a.name.substringAfterLast('.', "").lowercase()] ?: "wav")))
            else -> Unit
        }
        return parts
    }

    fun image(name: String, bitmap: Bitmap): Attachment {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val bytes = out.toByteArray()
        return Attachment(name, Attachment.Kind.IMAGE, "image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size.toLong())
    }

    /** A picked document or image, classified by its type and name. Blocking: call off the main thread. */
    fun read(cr: ContentResolver, uri: Uri, name: String, mimeHint: String? = null): Attachment {
        val mime = mimeHint ?: cr.getType(uri) ?: "application/octet-stream"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (mime.startsWith("image/")) {
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return Attachment(name, Attachment.Kind.FILE, mime, "", 0)
            val bmp = scaled(bytes) ?: return Attachment(name, Attachment.Kind.FILE, mime, "", bytes.size.toLong())
            return image(name, bmp)
        }
        if (mime.startsWith("audio/") || ext in AUDIO_EXT) {
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
            return if (bytes != null && ext in AUDIO_EXT && bytes.size <= MAX_AUDIO_BYTES)
                Attachment(name, Attachment.Kind.AUDIO, mime, Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size.toLong())
            else Attachment(name, Attachment.Kind.FILE, mime, "", bytes?.size?.toLong() ?: 0)
        }
        if (mime.startsWith("text/") || ext in TEXT_EXT) {
            val text = cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Attachment(name, Attachment.Kind.TEXT, mime, text.take(MAX_TEXT), text.length.toLong())
        }
        return Attachment(name, Attachment.Kind.FILE, mime, "", 0)
    }

    private fun scaled(bytes: ByteArray): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0) return null
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= IMAGE_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
