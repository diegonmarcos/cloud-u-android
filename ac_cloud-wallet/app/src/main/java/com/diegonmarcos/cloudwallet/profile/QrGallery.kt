package com.diegonmarcos.cloudwallet.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudwallet.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.BufferedReader

/**
 * Self-contained native QR gallery. Reads assets/qrcodes/qrcodes.json
 * (same single source of truth the linktree project uses). Drawn over the business card as a
 * full-screen layer (it was a full-screen Dialog); a tapped code goes full screen over it.
 */
@Composable
fun QrGallery(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val manifest = remember { loadManifest(ctx) }
    var fullScreen by remember { mutableStateOf<Triple<String, String, Palette>?>(null) }
    BackHandler { if (fullScreen != null) fullScreen = null else onClose() }

    if (manifest == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("qrcodes.json missing in assets", color = colorResource(R.color.wallet_error), textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 48.dp).clickable { onClose() })
        }
        return
    }

    val density = LocalDensity.current
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val page = manifest.optJSONObject("page") ?: JSONObject()
    val specs = remember(manifest) {
        val arr = manifest.optJSONArray("qrcodes") ?: org.json.JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }
    Box(Modifier.fillMaxSize().background(colorResource(R.color.wallet_card))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 24.dp)) {
            Text(page.optString("title", "QR Codes"), color = colorResource(R.color.wallet_accent), fontSize = 24.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(page.optString("subtitle", ""), color = colorResource(R.color.wallet_text), fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().alpha(0.75f).padding(top = 4.dp, bottom = 18.dp))
            specs.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { spec ->
                        Box(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            QrCard(spec, manifest, screenW, density) { payload, title, palette ->
                                fullScreen = Triple(payload, title, palette)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text("Close", color = colorResource(R.color.wallet_text), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().alpha(0.7f).clickable { onClose() }.padding(top = 24.dp))
        }
        fullScreen?.let { (payload, title, palette) ->
            val side = minOf(screenW, LocalConfiguration.current.screenHeightDp.dp) * 0.85f
            val bmp = remember(payload) { qrBitmap(payload, with(density) { side.roundToPx() }, palette) }
            Column(Modifier.fillMaxSize().background(colorResource(R.color.wallet_scrim)).clickable { fullScreen = null },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(title, color = Color(palette.eyeFinder), fontSize = 24.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp))
                if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(side))
            }
        }
    }
}

@Composable
private fun QrCard(
    spec: JSONObject, manifest: JSONObject, screenW: Dp, density: androidx.compose.ui.unit.Density,
    onOpen: (String, String, Palette) -> Unit,
) {
    val category = spec.optString("category", "web")
    val title = spec.optString("title", spec.optString("id"))
    val palette = categoryPalette(category)
    val payload = remember(spec) { resolvePayload(spec, manifest) }
    val qrSize = maxOf(screenW / 2 - 60.dp, 140.dp)
    val qr = remember(payload) { qrBitmap(payload, with(density) { qrSize.roundToPx() }, palette) }
    Column(Modifier.fillMaxWidth().background(colorResource(R.color.wallet_tile)).clickable { onOpen(payload, title, palette) }
        .padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color(palette.eyeFinder), fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 6.dp))
        if (qr != null) Image(qr.asImageBitmap(), contentDescription = null, modifier = Modifier.size(qrSize))
    }
}

private fun loadManifest(ctx: Context): JSONObject? = runCatching {
    ctx.assets.open("qrcodes/qrcodes.json").bufferedReader().use(BufferedReader::readText)
        .let(::JSONObject)
}.getOrNull()

private fun resolvePayload(spec: JSONObject, manifest: JSONObject): String {
    val source = spec.optJSONObject("source") ?: return spec.optString("id")
    return when (source.optString("type")) {
        "url" -> {
            val ref = source.optString("ref", "")
            if (ref.isNotEmpty()) {
                manifest.optJSONObject("links")?.optJSONObject(ref)?.optString("value", "") ?: ""
            } else source.optString("value", "")
        }
        "vcard" -> {
            val contact = manifest.optJSONObject("contact") ?: return ""
            val links = manifest.optJSONObject("links")
            renderVcard(contact, links)
        }
        else -> ""
    }
}

private fun renderVcard(contact: JSONObject, links: JSONObject?): String {
    val lines = mutableListOf("BEGIN:VCARD", "VERSION:3.0")
    val family = contact.optString("family", "")
    val given = contact.optString("given", "")
    lines += "N:$family;$given;"
    lines += "FN:${contact.optString("displayName", "$given $family")}"
    contact.optString("email").takeIf { it.isNotEmpty() }?.let {
        val t = contact.optString("emailType", "personal").uppercase()
        lines += "EMAIL;TYPE=$t:$it"
    }
    contact.optString("tel").takeIf { it.isNotEmpty() }?.let {
        val t = contact.optString("telType", "mobile").uppercase()
        lines += "TEL;TYPE=$t:$it"
    }
    contact.optJSONObject("address")?.let { a ->
        val t = a.optString("type", "home").uppercase()
        lines += "ADR;TYPE=$t:;;${a.optString("street")};${a.optString("city")};" +
            "${a.optString("region")};${a.optString("postalCode")};${a.optString("country")}"
    }
    val urls = contact.optJSONArray("urls")
    if (urls != null) for (i in 0 until urls.length()) {
        val u = urls.getJSONObject(i)
        val (type, value) = if (u.has("ref")) {
            val link = links?.optJSONObject(u.getString("ref")) ?: continue
            link.optString("type") to link.optString("value")
        } else {
            u.optString("type") to u.optString("value")
        }
        lines += "URL;TYPE=$type:$value"
    }
    contact.optString("birthday").takeIf { it.isNotEmpty() }?.let { lines += "BDAY:$it" }
    lines += "END:VCARD"
    return lines.joinToString("\r\n")
}

private data class Palette(val gradStart: Int, val gradEnd: Int, val eyeFinder: Int, val eyeDot: Int)

private fun categoryPalette(category: String): Palette = when (category) {
    "vcard"  -> Palette(0xFFF5D0FE.toInt(), 0xFF86198F.toInt(), 0xFF86198F.toInt(), 0xFFE879F9.toInt())
    "social" -> Palette(0xFFC7D2FE.toInt(), 0xFF1E1B4B.toInt(), 0xFF1E1B4B.toInt(), 0xFF818CF8.toInt())
    else     -> Palette(0xFFA78BFA.toInt(), 0xFF34208C.toInt(), 0xFF34208C.toInt(), 0xFF7F6DF2.toInt())
}

private fun qrBitmap(text: String, size: Int, palette: Palette): Bitmap? = runCatching {
    if (text.isBlank()) return@runCatching null
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        EncodeHintType.MARGIN to 2,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (y in 0 until size) {
        for (x in 0 until size) {
            if (matrix.get(x, y)) {
                val t = ((x + y).toFloat() / (size * 2)).coerceIn(0f, 1f)
                bmp.setPixel(x, y, lerpColor(palette.gradStart, palette.gradEnd, t))
            } else {
                bmp.setPixel(x, y, AndroidColor.WHITE)
            }
        }
    }
    bmp
}.getOrNull()

private fun lerpColor(a: Int, b: Int, t: Float): Int {
    val ar = (a shr 16) and 0xff; val ag = (a shr 8) and 0xff; val ab = a and 0xff
    val br = (b shr 16) and 0xff; val bg = (b shr 8) and 0xff; val bb = b and 0xff
    val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
    val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
    val bC = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
    return (0xff shl 24) or (r shl 16) or (g shl 8) or bC
}
