package com.diegonmarcos.superapp.launcher.themes.minimalist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * The COMPLETE design vocabulary of the Cloud Minimalist Black mode.
 *
 * ── Why this file exists ─────────────────────────────────────────────────
 * A mode that borrows its components from another mode cannot look different
 * from it, so each mode owns its vocabulary outright and imports nothing from
 * `ui/`. The owner asked for exactly that — "make all themes have their own
 * folders", "all different, nothing can be equal" — and a rule enforced by
 * what is in scope beats a rule someone has to remember.
 *
 * ── Why this design looks the way it does ────────────────────────────────
 * Minimalist Black is a TERMINAL: a single-typeface, single-hue, text-only
 * machine that shows everything at once.
 *
 *   · PHOSPHOR GREEN on black. One hue, like a VT220. [INK] is the readable
 *     body green; [GLOW] is the brighter accent reserved for the things the
 *     user acts on, so brightness carries meaning rather than decoration.
 *   · MONOSPACE ONLY. Every glyph on the same grid — that is what makes a
 *     dense list of forty apps scannable without any icon at all.
 *   · NO CONTAINERS. Structure comes from prompt characters and indentation,
 *     the way a shell does it. There are no cards and no rounded corners.
 *
 * Compose since #773: the vocabulary is the same, expressed as a [KitPalette] ([kit]: GROUND is
 * the surface, INK/INK_DIM the two text roles, GLOW the accent) plus composables, so the page sits
 * under CloudKitTheme like every other page while still borrowing nothing from `ui/`.
 */
internal object MinimalistDesign {

    // ── Colour ───────────────────────────────────────────────────────────
    // Constants, not colour resources: a res/values token is reachable from
    // every other mode by definition, and this vocabulary must not be.

    /** The ground. Black, because a terminal's ground is black. */
    val GROUND = Color(0xFF000000)

    /** Body phosphor. 10.9:1 on black — long lists stay readable. */
    val INK = Color(0xFFB8E6B8)

    /** The quieter phosphor: hints, counts, section chrome. */
    val INK_DIM = Color(0xFF7FB77F)

    /** The bright accent. Reserved for what the user acts on. */
    val GLOW = Color(0xFF00FF66)

    /** A rule, at the accent's hue but barely present. */
    val HAIRLINE = Color(0x3300FF66)

    /** The mode's palette in kit roles. */
    val kit = KitPalette(
        surface = GROUND, surfaceSelected = GROUND,
        textPrimary = INK, textSecondary = INK_DIM,
        accent = GLOW, hairline = HAIRLINE, tileInk = GROUND,
    )

    // ── Metric ───────────────────────────────────────────────────────────
    // A terminal is a character grid, so the scale is a line-height scale.

    const val STEP = 4
    const val GAP_TIGHT = STEP           // 4  — between lines of one block
    const val GAP = STEP * 2             // 8
    const val GAP_WIDE = STEP * 4        // 16 — the screen gutter
    const val GAP_SECTION = STEP * 6     // 24 — between blocks

    // ── Type ─────────────────────────────────────────────────────────────
    // Three sizes, one face. A terminal that changes typeface is not one.

    const val TYPE_HEAD = 14f
    const val TYPE_BODY = 13f
    const val TYPE_HINT = 11f

    val FACE: FontFamily = FontFamily.Monospace

    /** The shell prompt. This mode's entire decorative budget. */
    const val PROMPT = "$ "

    fun gap(step: Int): Dp = step.dp

    // ── Primitives ───────────────────────────────────────────────────────

    @Composable
    fun Rule() {
        Box(Modifier.fillMaxWidth().height(1.dp).background(HAIRLINE))
    }

    @Composable
    fun Space(height: Int) {
        Box(Modifier.fillMaxWidth().height(height.dp))
    }

    // ── Text ─────────────────────────────────────────────────────────────

    private fun style(size: Float) = TextStyle(fontFamily = FACE, fontSize = size.sp)

    /** A section heading, written the way a shell writes one. */
    @Composable
    fun Head(value: String, modifier: Modifier = Modifier) {
        Text("$PROMPT${value.lowercase()}", color = GLOW, style = style(TYPE_HEAD), modifier = modifier)
    }

    @Composable
    fun Body(value: String, modifier: Modifier = Modifier, colour: Color = INK) {
        Text(value, color = colour, style = style(TYPE_BODY), modifier = modifier)
    }

    @Composable
    fun Hint(value: String, modifier: Modifier = Modifier) {
        Text(value, color = INK_DIM, style = style(TYPE_HINT), modifier = modifier)
    }

    // ── Components ───────────────────────────────────────────────────────

    /** The line's text: tappable lines carry the prompt so that "what can I act on" is legible
     *  without any colour or container to mark it. */
    fun lineText(value: String, tappable: Boolean): String = if (tappable) "$PROMPT$value" else "  $value"

    /** The mode's one list item: a line of output. */
    @Composable
    fun Line(value: String, modifier: Modifier = Modifier, trailing: String = "", onTap: (() -> Unit)? = null) {
        Row(modifier.fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier)
            .padding(vertical = GAP_TIGHT.dp)) {
            Body(lineText(value, onTap != null), Modifier.weight(1f))
            if (trailing.isNotEmpty()) Hint(trailing)
        }
    }

    /** The page scaffold: black to the edges, a single gutter, vertical flow. */
    @Composable
    fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        Column(modifier.fillMaxSize().background(GROUND).padding(horizontal = GAP_WIDE.dp), content = content)
    }
}
