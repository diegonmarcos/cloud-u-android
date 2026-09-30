package com.diegonmarcos.cloudc3.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.diegonmarcos.cloudc3.Declarations

/**
 * #648 iconography FROM DECLARATIONS. build.json::ui names every tab, page and Apps tile
 * glyph by a short name; this is the ONE vocabulary that turns a name into a Material
 * glyph. A name that is not here renders [Declarations.iconDefault] — and
 * test/test-c3-shell.sh + DeclarationsTest fail the build, because a declared icon that
 * silently falls back is the #170/#380 defect shape (two lists agreeing by luck).
 *
 * One name per line on purpose: the tester reads the vocabulary off this `when`.
 */
object IconCatalog {

    fun vector(name: String): ImageVector? = when (name) {
        "stack" -> Icons.Filled.Schema
        "workflows" -> Icons.Filled.Timeline
        "home" -> Icons.Filled.Home
        "robot" -> Icons.Filled.SmartToy
        "settings" -> Icons.Filled.Settings
        "health" -> Icons.Filled.MonitorHeart
        "reports" -> Icons.Filled.BarChart
        "vms" -> Icons.Filled.Computer
        "logs" -> Icons.Filled.Description
        "apps" -> Icons.Filled.Apps
        "info" -> Icons.Filled.Info
        "cloud" -> Icons.Filled.Cloud
        "lan" -> Icons.Filled.Lan
        "dns" -> Icons.Filled.Dns
        "storage" -> Icons.Filled.Storage
        "terminal" -> Icons.Filled.Terminal
        "tree" -> Icons.Filled.AccountTree
        "code" -> Icons.Filled.Code
        "circle" -> Icons.Filled.Circle
        "heart" -> Icons.Filled.Favorite
        else -> null
    }

    /** True when [name] is in the vocabulary — what the testers hold every declaration to. */
    fun knows(name: String): Boolean = vector(name) != null

    /** The declared glyph, or the declared default when the name is unknown. */
    fun vectorOrDefault(name: String): ImageVector =
        vector(name) ?: vector(Declarations.iconDefault) ?: Icons.Filled.Circle

    @Composable
    fun painter(name: String): Painter = rememberVectorPainter(vectorOrDefault(name))
}
