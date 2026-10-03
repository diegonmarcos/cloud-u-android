package cld.camera.identify

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidxc.exifinterface.media.ExifInterface
import cld.camera.analyzer.ImageContentScanner
import cld.camera.capturer.DEFAULT_MEDIA_STORE_CAPTURE_PATH
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.sound.SoundConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * #798 a snapshot from the Identify viewfinder, saved WITH what was identified: the live detection
 * at the moment of the shot, the saved photo identified again on the user's route (More settings ▸
 * Image recognition route: #799 Model (Jev) by default, falling back to on-device and saying so),
 * and the ambient sound classes when Sound mode heard something recently. All of it rides in the
 * photo's own EXIF — ImageDescription a sentence a gallery shows, UserComment the JSON — so it
 * travels with the file into DCIM/Camera and any app that reads it.
 */
object IdentifySnapshot {
    /** Identify [jpeg] on the user's route, write the metadata into it, and file it in DCIM/Camera. */
    fun save(ctx: Context, jpeg: File, live: Recognition?, sound: Recognition?, scanner: ImageContentScanner): Uri {
        val photo = scanner.recognize(jpeg, null)
        val meta = metadata(live, photo, sound?.let { SoundConfig.tags(it) }.orEmpty())
        ExifInterface(jpeg).apply {
            setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, summary(meta))
            setAttribute(ExifInterface.TAG_USER_COMMENT, meta.toString())
            saveAttributes()
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_identified.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_MEDIA_STORE_CAPTURE_PATH)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("the media store refused a new photo")
        ctx.contentResolver.openOutputStream(uri)?.use { out -> jpeg.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("cannot write $uri")
        return uri
    }

    /** The JSON a snapshot carries: what the viewfinder saw, what the photo is on the user's route, what was heard. */
    fun metadata(live: Recognition?, photo: Recognition?, sound: List<Recognition.Label>): JSONObject = JSONObject()
        .put("cloud_identify", 1)
        .put("live", live?.takeIf { it.ok }?.let { r ->
            JSONObject().put("mode", r.mode).put("model", r.model)
                .put("objects", JSONArray().apply { r.boxes.forEach { b -> put(JSONObject().put("label", b.label).put("p", b.p).put("id", b.id ?: JSONObject.NULL)) } })
                .put("labels", labels(r.labels)).put("text", r.text)
        } ?: JSONObject.NULL)
        .put("photo", photo?.takeIf { it.ok }?.let { r ->
            JSONObject().put("route", r.route).put("requested", r.requested).put("fell_back", r.fellBack).put("reason", r.reason).put("model", r.model).put("labels", labels(r.labels))
        } ?: JSONObject.NULL)
        .put("sound", labels(sound))

    /** "Identified: suit, military uniform · Heard: Speech" — the sentence a gallery shows. */
    fun summary(meta: JSONObject): String {
        val seen = LinkedHashSet<String>()
        meta.optJSONObject("live")?.let { l -> names(l.optJSONArray("objects")).forEach { seen += it }; names(l.optJSONArray("labels")).forEach { seen += it } }
        meta.optJSONObject("photo")?.let { p -> names(p.optJSONArray("labels")).forEach { seen += it } }
        val heard = names(meta.optJSONArray("sound"))
        return listOfNotNull(
            seen.takeIf { it.isNotEmpty() }?.joinToString(", ", "Identified: "),
            heard.takeIf { it.isNotEmpty() }?.joinToString(", ", "Heard: "),
        ).joinToString(" · ")
    }

    private fun labels(l: List<Recognition.Label>) = JSONArray().apply { l.forEach { put(JSONObject().put("label", it.label).put("p", it.p)) } }
    private fun names(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it)?.optString("label")?.takeIf { s -> s.isNotBlank() } }
}
