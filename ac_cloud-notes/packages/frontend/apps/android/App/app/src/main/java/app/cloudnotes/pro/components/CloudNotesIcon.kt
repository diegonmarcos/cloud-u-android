package app.cloudnotes.pro.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.cloudnotes.pro.theme.CloudNotesTheme

@Composable
fun CloudNotesIcon(
    @DrawableRes resId: Int,
    modifier: Modifier = Modifier,
    tint: Color = CloudNotesTheme.colors.iconPrimary,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(resId),
        tint = tint,
        contentDescription = contentDescription,
        modifier = modifier.size(24.dp)
    )
}

@Composable
fun CloudNotesIconButton(
    @DrawableRes resId: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors().copy(
        contentColor = CloudNotesTheme.colors.iconPrimary,
        disabledContentColor = CloudNotesTheme.colors.iconDisable,
    ),
    interactionSource: MutableInteractionSource? = null,
) {
    IconButton(onClick, modifier.size(24.dp), enabled, colors, interactionSource) {
        CloudNotesIcon(resId)
    }
}