package com.diegonmarcos.superapp.image.mlkit

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * [ImageScanner] behind libs:ml-l-image's IImageScanEngine, shipped in
 * Cloud-Lib-Ml-L-Image-Mlkit.apk (engine-apk-split move 6). Drive, Mail, Camera,
 * Media Center and Office bind this through the contract module's client and compile
 * nothing of this module, so an edit here ships this APK alone.
 *
 * THE IMAGE ARRIVES AS A DESCRIPTOR the caller opened (this process cannot open the
 * caller's files or its granted Uris); it is read to the end — the caller may hand a
 * file, a provider's stream or a pipe — capped at [MAX_IMAGE_BYTES].
 *
 * A PUBLISHED CONTRACT: [methodNames] may only grow; an answer that changes shape ships
 * under a new method name and a higher CONTRACT in the manifest. Not a DataBackendService
 * because core's IDataBackend carries strings only, and the image is a descriptor.
 */
class ImageScanBackendService : Service() {

    private val scanner by lazy { ImageScanner() }

    fun methodNames(): Array<String> = arrayOf(BARCODE, OCR)

    fun dispatch(method: String, image: ByteArray): String = when (method) {
        BARCODE -> scanner.barcodeJson(image)
        OCR -> scanner.ocrJson(image)
        else -> throw IllegalArgumentException("unknown method: $method")
    }

    private val binder = object : IImageScanEngine.Stub() {
        override fun scan(method: String?, image: ParcelFileDescriptor?): String =
            runCatching { dispatch(method.orEmpty(), readAll(requireNotNull(image) { "no image descriptor" })) }
                // Never let an exception cross the binder: on the far side it is a bare
                // RemoteException with nothing a user could be shown.
                .getOrElse { t -> JSONObject().put("error", t.message ?: t.toString()).toString() }

        override fun methods(): Array<String> = methodNames()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun readAll(image: ParcelFileDescriptor): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(image).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                require(out.size() <= MAX_IMAGE_BYTES) { "the image is larger than ${MAX_IMAGE_BYTES / (1024 * 1024)} MB" }
            }
            out.toByteArray()
        }

    private companion object {
        const val BARCODE = "barcode"
        const val OCR = "ocr"

        /** ponytail: one whole image in memory; a phone photo is a few MB, 64 MB refuses a runaway stream. */
        const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
    }
}
