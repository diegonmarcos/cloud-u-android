package org.fossify.phone.sms.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import org.fossify.phone.sms.AndroidSmsNotifier
import org.fossify.phone.sms.SmsDeliverReceiver
import org.fossify.phone.sms.SmsRole
import org.fossify.phone.sms.TelephonyRepo
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** FileProvider with its own class name, so the manifest merge never collides with another library's. */
class MmsFileProvider : FileProvider()

/**
 * MMS on top of the platform's MMS transport. The platform downloads and uploads over the carrier's
 * MMS APN (SmsManager.downloadMultimediaMessage / sendMultimediaMessage); this class does what the
 * default SMS app must do around it: read the notification, ask for the download, decode the PDU
 * ([MmsCodec]) into the provider, and compose the send request for an image.
 */
class MmsService(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val repo = TelephonyRepo(this.context)

    private fun spoolFile(name: String): File {
        val dir = File(context.cacheDir, "mms").apply { mkdirs() }
        return File(dir, name)
    }

    private fun spoolUri(file: File): Uri {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".mms", file)
        // The phone process reads (send) and writes (download) the file on our behalf.
        context.grantUriPermission("com.android.phone", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun manager(subscriptionId: Int): SmsManager {
        val sdk = Build.VERSION.SDK_INT
        return if (subscriptionId < 0) {
            if (sdk >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        } else if (sdk >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
    }

    // ---- receive ----------------------------------------------------------------------------

    /** The WAP push of an incoming MMS: a notification naming where the message waits. Asks the platform to fetch it. */
    fun onNotification(pdu: MmsPdu, subscriptionId: Int): Boolean {
        val location = pdu.contentLocation ?: return false
        if (!SmsRole.isDefault(context)) return false
        val file = spoolFile("dl-" + UUID.randomUUID() + ".pdu")
        return try {
            file.createNewFile()
            val done = PendingIntent.getBroadcast(
                context, file.name.hashCode(),
                Intent(context, MmsDownloadedReceiver::class.java).setAction(MmsDownloadedReceiver.ACTION)
                    .putExtra(MmsDownloadedReceiver.EXTRA_FILE, file.name).putExtra(MmsDownloadedReceiver.EXTRA_SUB, subscriptionId)
                    .putExtra(MmsDownloadedReceiver.EXTRA_FROM, pdu.from.orEmpty()),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
            )
            manager(subscriptionId).downloadMultimediaMessage(context, location, spoolUri(file), null, done)
            true
        } catch (e: Exception) {
            file.delete()
            false
        }
    }

    /** A downloaded PDU, decoded and written to the provider. Returns the stored message's id. */
    fun storeRetrieved(bytes: ByteArray, subscriptionId: Int, fallbackFrom: String): Long? {
        val pdu = MmsCodec.decode(bytes) ?: return null
        if (!pdu.isRetrieveConf) return null
        val from = pdu.from ?: fallbackFrom
        val threadId = repo.threadIdFor(setOf(from).filter { it.isNotEmpty() }.toSet().ifEmpty { setOf("unknown") })
        val id = insertMms(
            threadId = threadId, box = Telephony.Mms.MESSAGE_BOX_INBOX, subject = pdu.subject, dateSeconds = pdu.dateSeconds ?: (System.currentTimeMillis() / 1000),
            subscriptionId = subscriptionId, read = false, partCount = pdu.parts.size,
        ) ?: return null
        addAddress(id, from, 137)
        pdu.to.forEach { addAddress(id, it, 151) }
        pdu.parts.forEachIndexed { i, p -> addPart(id, p, i) }
        return id
    }

    // ---- send -------------------------------------------------------------------------------

    /** Sends [text] and an optional image to [addresses] as one MMS. Needs the default-SMS role; false if it could not start. */
    fun send(addresses: List<String>, text: String, image: Uri?, subscriptionId: Int): Boolean {
        if (!SmsRole.isDefault(context) || addresses.isEmpty()) return false
        val parts = ArrayList<MmsPart>()
        if (text.isNotBlank()) parts.add(MmsPart("text/plain", "text.txt", text.toByteArray(Charsets.UTF_8)))
        if (image != null) {
            val jpeg = ImageShrink.toJpeg(context, image) ?: return false
            parts.add(MmsPart("image/jpeg", "image.jpg", jpeg))
        }
        if (parts.isEmpty()) return false
        val threadId = repo.threadIdFor(addresses.toSet())
        val id = insertMms(threadId, Telephony.Mms.MESSAGE_BOX_OUTBOX, null, System.currentTimeMillis() / 1000, subscriptionId, read = true, partCount = parts.size) ?: return false
        addresses.forEach { addAddress(id, it, 151) }
        parts.forEachIndexed { i, p -> addPart(id, p, i) }

        val file = spoolFile("send-$id.pdu")
        return try {
            file.writeBytes(MmsCodec.encodeSendReq(UUID.randomUUID().toString().take(16), addresses, null, parts))
            val sent = PendingIntent.getBroadcast(
                context, id.toInt(),
                Intent(context, MmsSentReceiver::class.java).setAction(MmsSentReceiver.ACTION).putExtra(MmsSentReceiver.EXTRA_ID, id).putExtra(MmsSentReceiver.EXTRA_FILE, file.name),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
            )
            manager(subscriptionId).sendMultimediaMessage(context, spoolUri(file), null, null, sent)
            true
        } catch (e: Exception) {
            setBox(id, Telephony.Mms.MESSAGE_BOX_FAILED)
            file.delete()
            false
        }
    }

    fun setBox(id: Long, box: Int) {
        try {
            resolver.update(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id), ContentValues().apply { put("msg_box", box) }, null, null)
        } catch (e: Exception) {
        }
    }

    // ---- provider writes --------------------------------------------------------------------

    private fun insertMms(threadId: Long, box: Int, subject: String?, dateSeconds: Long, subscriptionId: Int, read: Boolean, partCount: Int): Long? = try {
        val values = ContentValues().apply {
            put("thread_id", threadId)
            put("date", dateSeconds)
            put("date_sent", dateSeconds)
            put("msg_box", box)
            put("read", if (read) 1 else 0)
            put("seen", if (read) 1 else 0)
            put("sub", subject)
            put("sub_cs", 106)
            put("ct_t", "application/vnd.wap.multipart.related")
            put("m_type", if (box == Telephony.Mms.MESSAGE_BOX_INBOX) 132 else 128)
            put("v", 18)
            put("pri", 129)
            put("rr", 129)
            put("d_rpt", 129)
            put("sub_id", subscriptionId)
            put("text_only", if (partCount <= 1) 1 else 0)
        }
        resolver.insert(Telephony.Mms.CONTENT_URI, values)?.let { ContentUris.parseId(it) }
    } catch (e: Exception) {
        null
    }

    private fun addAddress(mmsId: Long, address: String, type: Int) {
        try {
            resolver.insert(
                Uri.parse("content://mms/$mmsId/addr"),
                ContentValues().apply { put("address", address); put("type", type); put("charset", 106); put("msg_id", mmsId) },
            )
        } catch (e: Exception) {
        }
    }

    private fun addPart(mmsId: Long, part: MmsPart, index: Int) {
        try {
            val values = ContentValues().apply {
                put("mid", mmsId)
                put("seq", index)
                put("ct", part.contentType)
                put("name", part.name)
                put("cid", "<${part.name}>")
                put("cl", part.name)
                if (part.isText) {
                    put("chset", 106)
                    put("text", part.data.toString(Charsets.UTF_8))
                }
            }
            val uri = resolver.insert(Uri.parse("content://mms/$mmsId/part"), values) ?: return
            if (!part.isText) resolver.openOutputStream(uri)?.use { it.write(part.data) }
        } catch (e: Exception) {
        }
    }
}

/** An outgoing image, decoded and re-encoded small enough for a carrier's MMS size limit. */
object ImageShrink {
    const val MAX_BYTES = 900_000
    private const val MAX_EDGE = 1600

    fun toJpeg(context: Context, uri: Uri): ByteArray? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        bitmap?.let { compress(it) }
    } catch (e: Exception) {
        null
    }

    /** Scales to the edge limit, then lowers the quality until the bytes fit. */
    fun compress(source: Bitmap): ByteArray {
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(source.width, source.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true) else source
        var quality = 90
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_BYTES || quality <= 30) return out.toByteArray()
            quality -= 10
        }
    }
}

/** WAP_PUSH_DELIVER: an MMS notification. Protected by BROADCAST_WAP_PUSH; only the default SMS app gets it. */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.getByteArrayExtra("data") ?: return
        val subscriptionId = intent.getIntExtra("subscription", -1)
        val pdu = MmsCodec.decode(data) ?: return
        if (!pdu.isNotification) return
        val pending = goAsync()
        Thread {
            try {
                MmsService(context).onNotification(pdu, subscriptionId)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** The platform finished (or failed) fetching an MMS: decode the file it wrote and store the message. */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_FILE) ?: return
        val file = File(File(context.cacheDir, "mms"), name)
        val ok = resultCode == Activity.RESULT_OK
        val sub = intent.getIntExtra(EXTRA_SUB, -1)
        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        val pending = goAsync()
        Thread {
            try {
                if (ok && file.exists()) {
                    val id = MmsService(context).storeRetrieved(file.readBytes(), sub, from)
                    if (id != null && !SmsDeliverReceiver.isBlocked(context, from)) {
                        val threadId = TelephonyRepo(context).threadIdFor(from)
                        AndroidSmsNotifier(context).notifyMessage(from, context.getString(org.fossify.phone.R.string.sms_mms_received), threadId)
                    }
                }
            } finally {
                file.delete()
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION = "org.fossify.phone.sms.MMS_DOWNLOADED"
        const val EXTRA_FILE = "file"
        const val EXTRA_SUB = "sub"
        const val EXTRA_FROM = "from"
    }
}

/** The platform finished (or failed) sending an MMS: move the provider row to sent / failed. */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id >= 0) {
            MmsService(context).setBox(id, if (resultCode == Activity.RESULT_OK) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED)
        }
        intent.getStringExtra(EXTRA_FILE)?.let { File(File(context.cacheDir, "mms"), it).delete() }
    }

    companion object {
        const val ACTION = "org.fossify.phone.sms.MMS_SENT"
        const val EXTRA_ID = "mms_id"
        const val EXTRA_FILE = "file"
    }
}
