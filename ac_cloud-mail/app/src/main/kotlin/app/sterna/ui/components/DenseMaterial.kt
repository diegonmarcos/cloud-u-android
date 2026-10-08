package app.sterna.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.sterna.ui.theme.MailMetrics

/**
 * Material's [androidx.compose.material3.Icon], drawn at the app's icon size ([MailMetrics.icon])
 * instead of the fixed 24dp. The caller's modifier comes FIRST, so a call that states its own
 * `size(..)` still wins; only an icon that left its size to the default is resized. Every screen
 * imports THIS `Icon`, which is how the icon size is one token rather than 170 call sites.
 */
@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    androidx.compose.material3.Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier.size(MailMetrics.icon),
        tint = tint,
    )
}

/**
 * Material's [androidx.compose.material3.IconButton], boxed at [MailMetrics.iconButton] instead of
 * 40dp. As with [Icon], the caller's modifier is applied first and so can still override the size.
 */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier.size(MailMetrics.iconButton),
        enabled = enabled,
        content = content,
    )
}
