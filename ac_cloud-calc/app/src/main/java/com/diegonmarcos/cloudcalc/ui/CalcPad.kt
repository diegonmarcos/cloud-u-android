@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.Edit
import com.diegonmarcos.cloudcalc.Editor
import com.diegonmarcos.cloudcalc.R
import kotlinx.coroutines.awaitCancellation

/** What a key is for; it picks the key's colours (CalcPad.kt PadPalette), and is exposed as the key's semantics. */
enum class PadRole { DIGIT, FUNCTION, OPERATOR, CLEAR, DELETE, EVALUATE }

object CalcPad {
    private const val OPERATORS = "+-*/^%÷×−"

    fun role(k: Declarations.Key): PadRole = when {
        k.action == Declarations.Action.CLEAR -> PadRole.CLEAR
        k.action == Declarations.Action.DELETE -> PadRole.DELETE
        k.action == Declarations.Action.EVALUATE -> PadRole.EVALUATE
        k.label.length == 1 && (k.label[0].isDigit() || k.label == ".") -> PadRole.DIGIT
        k.label.length == 1 && k.label[0] in OPERATORS -> PadRole.OPERATOR
        else -> PadRole.FUNCTION
    }
}

val PadRoleKey = SemanticsPropertyKey<String>("PadRole")
private var SemanticsPropertyReceiver.padRole by PadRoleKey

/** The pad's colours per role, read from res/values/colors.xml (pad_* dark, pad_light_* light); no Kotlin literal. */
class PadPalette(private val fills: Map<PadRole, Color>, private val inks: Map<PadRole, Color>, val panel: Color, val rim: Color) {
    fun fill(r: PadRole): Color = fills.getValue(r)
    fun ink(r: PadRole): Color = inks.getValue(r)
    /** The key's gradient: the declared fill on top, the same hue darker underneath (the depth). */
    fun brush(r: PadRole): Brush = fill(r).let { Brush.verticalGradient(listOf(it, lerp(it, Color.Black, 0.22f))) }

    companion object {
        private val names = mapOf(PadRole.DIGIT to "digit", PadRole.FUNCTION to "function", PadRole.OPERATOR to "operator",
            PadRole.CLEAR to "clear", PadRole.DELETE to "delete", PadRole.EVALUATE to "evaluate")

        fun of(ctx: android.content.Context, dark: Boolean): PadPalette {
            val p = if (dark) "pad_" else "pad_light_"
            fun col(n: String): Color {
                val id = ctx.resources.getIdentifier(p + n, "color", ctx.packageName)
                require(id != 0) { "colors.xml declares no $p$n" }
                return Color(ContextCompat.getColor(ctx, id))
            }
            return PadPalette(names.mapValues { col(it.value) }, names.mapValues { col("on_" + it.value) }, col("panel"), col("rim"))
        }
    }
}

/** One round key: gradient fill, soft shadow, rim light, ripple and a small press-in. */
@Composable
fun PadKey(k: Declarations.Key, palette: PadPalette, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val role = CalcPad.role(k)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = if (pressed) 0.94f else 1f
    Box(modifier.height(CalcMetrics.padKey), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxHeight().aspectRatio(1f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(if (pressed) CalcMetrics.hairline else CalcMetrics.padElevation, CircleShape)
                .background(palette.brush(role), CircleShape)
                .border(BorderStroke(CalcMetrics.hairline, palette.rim), CircleShape)
                .clickable(interactionSource = source, indication = ripple(color = palette.ink(role)), role = Role.Button, onClick = onClick)
                .testTag(CalcTags.key(k.label))
                .semantics { padRole = role.name },
            contentAlignment = Alignment.Center,
        ) {
            Text(k.label, color = palette.ink(role), maxLines = 1, softWrap = false,
                style = TextStyle(fontSize = if (k.label.length > 2) 16.sp else 22.sp, fontWeight = FontWeight.Medium))
        }
    }
}

/** How many times the platform asked this app for a soft-keyboard session and was refused (read by the tests). */
object NoSoftKeyboard {
    @Volatile var refused: Int = 0
}

/**
 * Inside [content] a text field can be focused, given a cursor and a selection (tap, drag, long-press, handles, the
 * cut / copy / paste / select-all toolbar) but never gets an input-method session, so the soft keyboard does not open.
 * The pad is the keyboard; the field's value is still edited through onValueChange (pad, paste, accessibility SetText).
 */
@Composable
fun WithoutSoftKeyboard(content: @Composable () -> Unit) {
    val interceptor = remember {
        object : PlatformTextInputInterceptor {
            override suspend fun interceptStartInputMethod(request: PlatformTextInputMethodRequest, nextHandler: PlatformTextInputSession): Nothing {
                NoSoftKeyboard.refused++
                awaitCancellation()
            }
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

/** The expression field: no soft keyboard, a pasted text is cut down to what a calc reads, the selection kept. */
@Composable
fun ExpressionField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, palette: PadPalette, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    WithoutSoftKeyboard {
        BasicTextField(
            value = value,
            onValueChange = { v ->
                val s = Editor.sanitize(Edit.of(v.text, v.selection.start, v.selection.end))
                onValueChange(if (s.text == v.text) v else TextFieldValue(s.text, androidx.compose.ui.text.TextRange(s.start, s.end)))
            },
            modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) keyboard?.hide() }.testTag(CalcTags.INPUT),
            textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.End),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().background(palette.panel, RoundedCornerShape(CalcMetrics.corner))
                    .border(BorderStroke(CalcMetrics.hairline, palette.rim), RoundedCornerShape(CalcMetrics.corner))
                    .padding(CalcMetrics.gutter), contentAlignment = Alignment.CenterEnd) {
                    if (value.text.isEmpty()) Text(androidx.compose.ui.res.stringResource(R.string.expression_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    inner()
                }
            },
        )
    }
}
