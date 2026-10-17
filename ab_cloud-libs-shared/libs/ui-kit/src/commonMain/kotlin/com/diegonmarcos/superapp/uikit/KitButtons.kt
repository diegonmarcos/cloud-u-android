package com.diegonmarcos.superapp.uikit

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * THE fleet's buttons. Material3's Button carries `defaultMinSize(58.dp, 40.dp)` and 24dp x 8dp content padding, and
 * neither is a theme value - no MaterialTheme / CompositionLocal can shrink them, which is why wrapping a screen in
 * KitDenseMaterial never made a single button smaller (the owner's "buttons are STILL huge", 4th report).
 *
 * These pass `heightIn(min = KitDensity.buttonHeight)` BEFORE Material's defaultMinSize: defaultMinSize only applies
 * when the incoming min constraint is 0, so the 40dp/58dp defaults no longer apply and the button is 30dp tall
 * (28..32 dp with the font scale), 10dp x 4dp padded, labelMedium text. Use these, never a bare Material button.
 */

private val KitButtonPadding = PaddingValues(horizontal = KitDensity.buttonPadH, vertical = KitDensity.buttonPadV)

private fun Modifier.kitButtonSize(): Modifier =
    this.heightIn(min = KitDensity.buttonHeight).widthIn(min = 36.dp)

@Composable
fun KitButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) = Button(onClick, modifier.kitButtonSize(), enabled, colors = colors, contentPadding = KitButtonPadding) {
    ProvideTextStyle(MaterialTheme.typography.labelMedium) { content() }
}

@Composable
fun KitTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = FilledTonalButton(onClick, modifier.kitButtonSize(), enabled, contentPadding = KitButtonPadding) {
    ProvideTextStyle(MaterialTheme.typography.labelMedium) { content() }
}

@Composable
fun KitOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(onClick, modifier.kitButtonSize(), enabled, contentPadding = KitButtonPadding) {
    ProvideTextStyle(MaterialTheme.typography.labelMedium) { content() }
}

@Composable
fun KitTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = TextButton(onClick, modifier.kitButtonSize(), enabled, contentPadding = KitButtonPadding) {
    ProvideTextStyle(MaterialTheme.typography.labelMedium) { content() }
}
