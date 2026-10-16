package com.diegonmarcos.cloudwallet.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudwallet.R
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Virtual Business Card — LinkedIn-style hero strip (banner photo +
 * round profile picture overlapping the banner edge), then the
 * profile text (Name / Titles / Company / Location / Website), then
 * a QR code that encodes the website URL.
 *
 * All data comes from [ProfilePrefs]. The two photos are picked by
 * the user in Configs → Profile (gallery picker); falls back to a
 * solid brand colour + an initials avatar when neither is set.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The card is a composable drawn by
 * MainActivity over the wallet (it was a Fragment on the back stack); tapping the QR opens the
 * [QrGallery] over it.
 */
@Composable
fun BusinessCard() {
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val p = LocalKitPalette.current
    val profile = remember { ProfilePrefs(ctx) }
    var gallery by remember { mutableStateOf(false) }

    fun px(dp: Int) = with(density) { dp.dp.roundToPx() }
    val banner = remember { loadBitmap(profile.bannerUri) }
    val avatarSize = px(110)
    val avatar = remember {
        circularBitmap(loadBitmap(profile.pictureUri) ?: initialsBitmap(profile.initials, avatarSize), avatarSize, px(3))
    }
    val website = "https://${profile.website.removePrefix("https://").removePrefix("http://")}"
    val qr = remember { qrBitmap(website, px(220)) }

    Box(Modifier.fillMaxSize().background(colorResource(R.color.wallet_bg))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // ── Hero strip: banner photo + overlapping circular avatar ──
            Box(Modifier.fillMaxWidth().height(200.dp)) {
                if (banner != null) {
                    Image(banner.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(140.dp))
                } else {
                    Box(Modifier.fillMaxWidth().height(140.dp).background(p.surface))
                }
                Image(avatar.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp).size(110.dp))
            }

            // ── Identity block ─────────────────────────────────────────
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text(profile.name, color = p.textPrimary, fontSize = 24.sp, modifier = Modifier.padding(top = 2.dp))
                Text(profile.titles, color = p.textPrimary, fontSize = 14.sp,
                    modifier = Modifier.alpha(0.85f).padding(top = 4.dp))
                Spacer(Modifier.height(6.dp))
                Text(profile.company, color = p.textPrimary, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
                Text(profile.location, color = p.textPrimary, fontSize = 14.sp,
                    modifier = Modifier.alpha(0.75f).padding(top = 2.dp))
                Text(profile.website, color = p.textPrimary, fontSize = 14.sp,
                    modifier = Modifier.alpha(0.85f).padding(top = 4.dp).clickable {
                        runCatching {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(website)))
                        }
                    })
            }

            Spacer(Modifier.height(16.dp))

            // ── QR code linking to the website ─────────────────────────
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (qr != null) {
                    val sweep = Brush.sweepGradient(listOf(
                        colorResource(R.color.wallet_frame_start), colorResource(R.color.wallet_avatar),
                        colorResource(R.color.wallet_frame_start)))
                    Box(Modifier.padding(top = 6.dp).size(70.dp)
                        .clip(RoundedCornerShape(24.dp)).background(sweep)
                        .padding(6.dp).clip(RoundedCornerShape(18.dp)).background(Color.White)
                        .padding(4.dp)
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            gallery = true
                        }) {
                        Image(qr.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        if (gallery) QrGallery(onClose = { gallery = false })
    }
}

private fun loadBitmap(path: String): Bitmap? {
    if (path.isBlank()) return null
    return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
}

private fun circularBitmap(src: Bitmap, size: Int, ring: Int): Bitmap {
    val scaled = if (src.width != size || src.height != size) {
        Bitmap.createScaledBitmap(src, size, size, true)
    } else src
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val path = Path().apply { addCircle(size / 2f, size / 2f, size / 2f - ring, Path.Direction.CW) }
    canvas.save()
    canvas.clipPath(path)
    canvas.drawBitmap(scaled, 0f, 0f, paint)
    canvas.restore()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = ring.toFloat()
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - ring / 2f, paint)
    return out
}

private fun initialsBitmap(initials: String, size: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(0xFF7C3AED.toInt())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        textSize = size * 0.42f
        isFakeBoldText = true
    }
    val baselineY = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(initials.ifBlank { "•" }, size / 2f, baselineY, paint)
    return bmp
}

private fun qrBitmap(text: String, size: Int): Bitmap? = runCatching {
    if (text.isBlank()) return@runCatching null
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val hsv = FloatArray(3).apply { this[1] = 0.85f; this[2] = 0.85f }
    for (x in 0 until size) {
        for (y in 0 until size) {
            if (matrix.get(x, y)) {
                hsv[0] = ((x + y).toFloat() / (size * 2) * 360f) % 360f
                bmp.setPixel(x, y, AndroidColor.HSVToColor(hsv))
            } else {
                bmp.setPixel(x, y, AndroidColor.WHITE)
            }
        }
    }
    bmp
}.getOrNull()
