package com.diegonmarcos.cloudcalc.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icon VOCABULARY a build.json `icon` may name. test/test-calc-shell.sh holds every declared
 * icon to a branch here, so a misspelt name fails the build instead of drawing [fallback].
 */
object IconCatalog {
    val fallback: ImageVector = Icons.Filled.Apps

    fun vector(name: String): ImageVector = when (name) {
        "calculate" -> Icons.Filled.Calculate
        "swap" -> Icons.Filled.SwapHoriz
        "chart" -> Icons.Filled.ShowChart
        "tools" -> Icons.Filled.Build
        "history" -> Icons.Filled.History
        "clock" -> Icons.Filled.AccessTime
        "speed" -> Icons.Filled.Speed
        "psychology" -> Icons.Filled.Psychology
        "mic" -> Icons.Filled.Mic
        "camera" -> Icons.Filled.PhotoCamera
        "ask" -> Icons.Filled.QuestionAnswer
        "settings" -> Icons.Filled.Settings
        else -> fallback
    }
}
