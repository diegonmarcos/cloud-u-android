package com.diegonmarcos.superapp.media

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.ui.AppIconCache
import com.diegonmarcos.superapp.ui.WaveLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The music mini-island above the Dynamic Island, in Compose: a small black pill, always there, that lights up (the playing
 * app's icon on the left, the song title scrolling on the right, a thin wave behind both) while a media session is PLAYING.
 * It was a FrameLayout + ImageView + TextView in activity_main.xml, driven by MusicIslandController through findViewById.
 */

/** What the island shows: [pkg] is the playing app (its icon), [title] the "artist - song" line. Null = idle. */
data class NowPlayingLine(val pkg: String, val title: String)

/** The island's one piece of state. */
class MusicIslandState {
    var playing by mutableStateOf<NowPlayingLine?>(null)
}

@Composable
fun MusicIsland(state: MusicIslandState, onTap: () -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    val pill = RoundedCornerShape(50)
    Box(
        modifier.testTag("musicisland").background(Color.Black, pill).border(1.dp, Color(0x55B794F4), pill)
            .clickable(onClick = onTap),
    ) {
        val now = state.playing ?: return@Box
        WaveLine(Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize().padding(start = 3.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val ctx = LocalContext.current
            // The first icon per package pays a slow PackageManager read (Knox): off the main thread, then a map read.
            val icon by produceState<Drawable?>(AppIconCache.cached(now.pkg), now.pkg) {
                value = withContext(Dispatchers.IO) { AppIconCache.load(ctx, now.pkg) }
            }
            icon?.let {
                Image(it.toBitmap(48, 48).asImageBitmap(), null, Modifier.size(12.dp).testTag("musicicon"))
            }
            Text(
                now.title, color = Color(0xFFE5E7EB), fontSize = 8.sp, fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip,
                modifier = Modifier.padding(start = 4.dp).basicMarquee(iterations = Int.MAX_VALUE).testTag("musictitle"),
            )
        }
    }
}
