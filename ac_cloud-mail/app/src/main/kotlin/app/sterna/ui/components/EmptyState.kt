package app.sterna.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A calm, coastal line-art scene for an empty screen. Each one is drawn as a
 */
enum class EmptyArt { INBOX_ZERO, SEARCH, FOLDER, TRASH, OFFLINE }

private fun ImageVector.Builder.stroke(d: String, color: Color, width: Float = 2.4f) {
    addPath(
        PathParser().parsePathString(d).toNodes(),
        stroke = SolidColor(color),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

private fun ImageVector.Builder.fill(d: String, color: Color) {
    addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(color))
}

private fun buildArt(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 120.dp,
        defaultHeight = 120.dp,
        viewportWidth = 120f,
        viewportHeight = 120f,
    ).apply(block).build()

private fun artVector(art: EmptyArt, ink: Color, accent: Color): ImageVector = when (art) {
    // A tern gliding over a calm horizon, a low coral sun. The hero scene.
    EmptyArt.INBOX_ZERO -> buildArt("inboxZero") {
        fill("M70,64 a6,6 0 1,0 12,0 a6,6 0 1,0 -12,0 Z", accent)
        stroke("M20,74 L100,74", ink)
        stroke("M34,48 C42,42 47,42 53,48 C59,42 64,42 72,48", ink)
    }
    // A magnifier over open water, one far-off coral speck.
    EmptyArt.SEARCH -> buildArt("searchEmpty") {
        stroke("M36,52 a18,18 0 1,0 36,0 a18,18 0 1,0 -36,0 Z", ink)
        stroke("M67,65 L83,81", ink)
        fill("M84,40 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0 Z", accent)
    }
    // An open, empty folder; a coral feather-dot drifting above.
    EmptyArt.FOLDER -> buildArt("folderEmpty") {
        stroke("M32,52 L50,52 L56,58 L88,58 L88,82 L32,82 Z", ink)
        fill("M58,42 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0 Z", accent)
    }
    // An empty bin, swept clean — a small coral sparkle.
    EmptyArt.TRASH -> buildArt("trashEmpty") {
        stroke("M40,52 L80,52", ink)
        stroke("M44,52 L48,82 L72,82 L76,52", ink)
        stroke("M54,52 L54,47 L66,47 L66,52", ink)
        fill("M60,33 L61.6,38.4 L67,40 L61.6,41.6 L60,47 L58.4,41.6 L53,40 L58.4,38.4 Z", accent)
    }
    // A cloud, adrift and disconnected — a coral line across it.
    EmptyArt.OFFLINE -> buildArt("offline") {
        stroke("M44,72 C34,72 33,59 43,57 C44,46 61,45 63,55 C72,52 78,65 69,72 Z", ink)
        stroke("M40,46 L80,76", accent)
    }
}

/**
 * Centred empty-state: a theme-aware coastal illustration + a calm title, an
 * optional [body] line, and an optional [action] (e.g. a button).
 */
@Composable
fun EmptyState(
    art: EmptyArt,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    // Coral is the marginal accent (tertiary), not the primary teal.
    val accent = MaterialTheme.colorScheme.tertiary
    val image = remember(art, ink, accent) { artVector(art, ink, accent) }
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            imageVector = image,
            contentDescription = null,
            modifier = Modifier.size(128.dp),
        )
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        action?.invoke()
    }
}
