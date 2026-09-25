package com.diegonmarcos.clouddrive.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.ConfirmDialog
import com.diegonmarcos.clouddrive.files.FileOps
import com.diegonmarcos.clouddrive.ui.DriveTheme
import com.diegonmarcos.clouddrive.ui.IslandAction
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.ToolbarIsland
import com.diegonmarcos.superapp.image.mlkit.BarcodePayload
import com.diegonmarcos.superapp.image.mlkit.ImageScanEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #579 the image viewer (cloud-drive-redesign.md §7), natively: the folder's images
 * as a pager, pinch/double-tap zoom, and one action row — Rotate (EXIF tag, pixels
 * untouched), Scan (the shared engine's typed barcode payload → open / join / add /
 * dial / mail / map / copy), Text (OCR → copy / share / save beside), Info (EXIF),
 * Share, Delete. Also the fleet's "Open with" target for image/*.
 */
class ImageViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fromIntent = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data
        val start = intent.getStringExtra(EXTRA_PATH) ?: fromIntent?.let { resolveToFile(it) }?.absolutePath
        val siblings = intent.getStringArrayListExtra(EXTRA_SIBLINGS)?.toList()?.ifEmpty { null } ?: listOfNotNull(start)
        setContent { DriveTheme { Viewer(siblings, siblings.indexOf(start).coerceAtLeast(0)) } }
    }

    /** A content:// image from another app is copied into the cache once so every verb below sees a File. */
    private fun resolveToFile(uri: Uri): File? = runCatching {
        if (uri.scheme == "file") return File(uri.path ?: return null)
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
        val target = File(cacheDir, "viewer/$name").apply { parentFile?.mkdirs() }
        contentResolver.openInputStream(uri)?.use { i -> target.outputStream().use { o -> i.copyTo(o) } } ?: return null
        target
    }.getOrNull()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Viewer(paths: List<String>, startIndex: Int) {
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val engine = remember { ImageScanEngine(this) }
        val pager = rememberPagerState(initialPage = startIndex) { paths.size }
        var sheet by remember { mutableStateOf<Sheet?>(null) }
        var confirmDelete by remember { mutableStateOf(false) }
        var version by remember { mutableIntStateOf(0) }
        val current = paths.getOrNull(pager.currentPage)
        fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

        Column(Modifier.fillMaxSize().background(Color.Black)) {
            ToolbarIsland(
                title = current?.substringAfterLast('/') ?: "",
                subtitle = stringResource(R.string.viewer_counter, pager.currentPage + 1, paths.size),
                leading = { IslandAction(Icons.Filled.Close, stringResource(R.string.chrome_close), { finish() }) },
            )
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { paths[it] }) { page ->
                ZoomableImage(paths[page], version)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                IslandAction(Icons.Filled.Rotate90DegreesCw, stringResource(R.string.viewer_rotate), { current?.let { rotate(File(it)); version++ } }, tint = Color.White)
                IslandAction(Icons.Filled.QrCodeScanner, stringResource(R.string.viewer_scan), { sheet = Sheet.SCAN }, tint = Color.White)
                IslandAction(Icons.Filled.DocumentScanner, stringResource(R.string.viewer_ocr), { sheet = Sheet.OCR }, tint = Color.White)
                IslandAction(Icons.Filled.Info, stringResource(R.string.viewer_info), { sheet = Sheet.INFO }, tint = Color.White)
                IslandAction(Icons.Filled.Share, stringResource(R.string.viewer_share), { current?.let { share(File(it)) } }, tint = Color.White)
                IslandAction(Icons.Filled.Delete, stringResource(R.string.viewer_delete), { confirmDelete = true }, tint = Color.White)
            }
            SnackbarHost(snackbar)
        }

        val path = current ?: return
        when (sheet) {
            null -> Unit
            Sheet.INFO -> ModalBottomSheet(onDismissRequest = { sheet = null }) { InfoSheet(File(path)) }
            Sheet.SCAN -> ModalBottomSheet(onDismissRequest = { sheet = null }) { ScanSheet(engine, File(path), ::say) { sheet = null } }
            Sheet.OCR -> ModalBottomSheet(onDismissRequest = { sheet = null }) { OcrSheet(engine, File(path), ::say) { sheet = null } }
        }
        if (confirmDelete) ConfirmDialog(stringResource(R.string.viewer_delete_title, path.substringAfterLast('/')), stringResource(R.string.files_delete_body), stringResource(R.string.chrome_delete), { confirmDelete = false }) {
            confirmDelete = false
            if (File(path).delete()) { setResult(RESULT_OK, Intent().putExtra(RESULT_DELETED, path)); finish() }
        }
    }

    private enum class Sheet { INFO, SCAN, OCR }

    @Composable
    private fun ZoomableImage(path: String, version: Int) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 8f)
            offset = if (scale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero
        }
        val bitmap by produceState<Bitmap?>(null, path, version) { value = withContext(Dispatchers.IO) { decodeForScreen(File(path)) } }
        Box(
            Modifier.fillMaxSize()
                .transformable(transform)
                .pointerInput(path) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = androidx.compose.ui.geometry.Offset.Zero }) },
            contentAlignment = Alignment.Center,
        ) {
            val b = bitmap
            if (b == null) CircularProgressIndicator()
            else Image(
                b.asImageBitmap(), contentDescription = path.substringAfterLast('/'),
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                contentScale = ContentScale.Fit,
            )
        }
    }

    /** Decoded at most ~2× the screen's longest side, EXIF orientation applied, so a 48-megapixel photo does not OOM the viewer. */
    private fun decodeForScreen(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        val max = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
        if (degrees == 0f) return decoded
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also { if (it !== decoded) decoded.recycle() }
    }

    /** 90° clockwise by rewriting the EXIF orientation tag — the pixels are left untouched. */
    private fun rotate(file: File) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            val next = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_NORMAL -> ExifInterface.ORIENTATION_ROTATE_90
                ExifInterface.ORIENTATION_ROTATE_90 -> ExifInterface.ORIENTATION_ROTATE_180
                ExifInterface.ORIENTATION_ROTATE_180 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, next.toString())
            exif.saveAttributes()
        }
    }

    private fun share(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        }
    }

    @Composable
    private fun InfoSheet(file: File) {
        val info = remember(file) {
            val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val lat = exif?.let { e -> e.getAttribute(ExifInterface.TAG_GPS_LATITUDE)?.let { v -> dms(v)?.let { d -> if (e.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF) == "S") -d else d } } }
            val lon = exif?.let { e -> e.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)?.let { v -> dms(v)?.let { d -> if (e.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF) == "W") -d else d } } }
            listOfNotNull(
                file.absolutePath,
                if (bounds.outWidth > 0) getString(R.string.viewer_info_size, bounds.outWidth, bounds.outHeight, FileOps.humanBytes(file.length())) else FileOps.humanBytes(file.length()),
                exif?.getAttribute(ExifInterface.TAG_DATETIME),
                exif?.let { e -> val make = e.getAttribute(ExifInterface.TAG_MAKE); val model = e.getAttribute(ExifInterface.TAG_MODEL); if (make.isNullOrBlank() && model.isNullOrBlank()) null else getString(R.string.viewer_info_camera, make ?: "", model ?: "") },
                if (lat != null && lon != null) getString(R.string.viewer_info_location, lat, lon) else null,
            )
        }
        Column(Modifier.padding(20.dp).padding(bottom = 24.dp)) {
            info.forEach { Text(it, Modifier.padding(vertical = 3.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
    }

    private fun dms(v: String): Double? {
        val parts = v.split(',').mapNotNull { p -> val s = p.trim().split('/'); val n = s[0].trim().toDoubleOrNull() ?: return@mapNotNull null; val d = s.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 1.0; if (d == 0.0) null else n / d }
        return if (parts.size < 3) null else parts[0] + parts[1] / 60.0 + parts[2] / 3600.0
    }

    @Composable
    private fun ScanSheet(engine: ImageScanEngine, file: File, say: (String) -> Unit, close: () -> Unit) {
        val result by produceState<Result<BarcodePayload?>?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { engine.decodeBarcode(file)?.payload } } }
        Column(Modifier.padding(20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = result
            when {
                r == null -> CircularProgressIndicator()
                r.isFailure -> Text(r.exceptionOrNull()?.message ?: stringResource(R.string.viewer_no_barcode))
                r.getOrNull() == null -> Text(stringResource(R.string.viewer_no_barcode))
                else -> {
                    val p = r.getOrNull()!!
                    Text(payloadText(p), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (p) {
                            is BarcodePayload.Url -> Pill(stringResource(R.string.viewer_open_url), { openUrl(p.url); close() }, filled = true)
                            is BarcodePayload.Wifi -> Pill(stringResource(R.string.viewer_join_wifi), { say(joinWifi(p.ssid, p.password)); close() }, filled = true)
                            is BarcodePayload.Contact -> Pill(stringResource(R.string.viewer_add_contact), { addContact(p.vcard, p.name ?: ""); close() }, filled = true)
                            is BarcodePayload.Calendar -> Pill(stringResource(R.string.viewer_add_event), { addEvent(p.summary ?: "", p.location ?: "", p.startTimeEpochMillis ?: 0L, p.endTimeEpochMillis ?: 0L); close() }, filled = true)
                            is BarcodePayload.Phone -> Pill(stringResource(R.string.viewer_dial), { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.number)))); close() }, filled = true)
                            is BarcodePayload.Email -> Pill(stringResource(R.string.viewer_email), { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(p.address))).putExtra(Intent.EXTRA_SUBJECT, p.subject ?: "").putExtra(Intent.EXTRA_TEXT, p.body ?: "")); close() }, filled = true)
                            is BarcodePayload.Geo -> Pill(stringResource(R.string.viewer_map), { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.latitude},${p.longitude}"))); close() }, filled = true)
                            is BarcodePayload.Plain -> Unit
                        }
                        Pill(stringResource(R.string.viewer_copy), { copyText(payloadText(p)); close() })
                    }
                }
            }
        }
    }

    private fun payloadText(p: BarcodePayload): String = when (p) {
        is BarcodePayload.Url -> p.url
        is BarcodePayload.Wifi -> p.ssid
        is BarcodePayload.Contact -> p.name ?: p.vcard
        is BarcodePayload.Calendar -> p.summary ?: p.vevent
        is BarcodePayload.Phone -> p.number
        is BarcodePayload.Email -> p.address
        is BarcodePayload.Geo -> "${p.latitude}, ${p.longitude}"
        is BarcodePayload.Plain -> p.text
    }

    @Composable
    private fun OcrSheet(engine: ImageScanEngine, file: File, say: (String) -> Unit, close: () -> Unit) {
        val result by produceState<Pair<String?, String?>?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { val r = engine.recognizeText(file); r.text to r.error }.getOrElse { null to (it.message ?: it.toString()) } } }
        Column(Modifier.padding(20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = result
            when {
                r == null -> CircularProgressIndicator()
                r.second != null -> Text(r.second!!)
                r.first.isNullOrBlank() -> Text(stringResource(R.string.viewer_no_text))
                else -> {
                    val text = r.first!!
                    Text(text, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 18)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(stringResource(R.string.viewer_copy), { copyText(text); close() }, filled = true)
                        Pill(stringResource(R.string.viewer_share), { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)); close() })
                        Pill(stringResource(R.string.viewer_save_txt), { say(saveBeside(file, text, "txt")); close() })
                        Pill(stringResource(R.string.viewer_save_md), { say(saveBeside(file, text, "md")); close() })
                    }
                }
            }
        }
    }

    private fun saveBeside(image: File, text: String, ext: String): String = runCatching {
        val target = FileOps.uniqueIn(image.parentFile!!, image.nameWithoutExtension + "." + ext)
        FileOps.writeText(target, text)
        getString(R.string.viewer_saved, target.name)
    }.getOrElse { it.message ?: it.toString() }

    private fun copyText(text: String) { (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("cloud-drive", text)) }
    private fun openUrl(url: String) { val u = Uri.parse(url.trim()); if (u.scheme == "http" || u.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) } }

    /** Android 10+ takes a SUGGESTION the user approves in the system panel; below it the legacy addNetwork joins directly. */
    private fun joinWifi(ssid: String, password: String): String {
        if (ssid.isBlank()) return getString(R.string.viewer_no_barcode)
        val manager = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val b = android.net.wifi.WifiNetworkSuggestion.Builder().setSsid(ssid)
            if (password.isNotEmpty()) b.setWpa2Passphrase(password)
            val status = manager.addNetworkSuggestions(listOf(b.build()))
            if (status == android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS || status == android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) ssid else "Wi-Fi: $status"
        } else {
            @Suppress("DEPRECATION") val cfg = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (password.isEmpty()) allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE) else { allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK); preSharedKey = "\"$password\"" }
            }
            @Suppress("DEPRECATION") val id = manager.addNetwork(cfg)
            @Suppress("DEPRECATION") val enabled = if (id != -1) manager.enableNetwork(id, true) else false
            if (enabled) ssid else "Wi-Fi: $id"
        }
    }

    private fun addContact(vcard: String, name: String) {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        if (name.isNotBlank()) intent.putExtra(ContactsContract.Intents.Insert.NAME, name)
        vcard.lineSequence().firstOrNull { it.trim().startsWith("TEL", ignoreCase = true) }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }?.let { intent.putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        runCatching { startActivity(intent) }
    }

    private fun addEvent(summary: String, location: String, start: Long, end: Long) {
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        if (summary.isNotBlank()) intent.putExtra(CalendarContract.Events.TITLE, summary)
        if (location.isNotBlank()) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        if (start > 0) { intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start); if (end > start) intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end) }
        runCatching { startActivity(intent) }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_SIBLINGS = "siblings"
        const val RESULT_DELETED = "deleted"
        fun intent(context: Context, path: String, siblings: List<String>): Intent =
            Intent(context, ImageViewerActivity::class.java).putExtra(EXTRA_PATH, path).putStringArrayListExtra(EXTRA_SIBLINGS, ArrayList(siblings))
    }
}
