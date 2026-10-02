package com.diegonmarcos.cloudsearch.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icon VOCABULARY a vertical's `icon` may name. test/test-search-shell.sh holds every declared
 * icon to a branch here, so a misspelt name fails the build instead of drawing [fallback].
 */
object IconCatalog {
    val fallback: ImageVector = Icons.Filled.Apps

    fun vector(name: String): ImageVector = when (name) {
        "house" -> Icons.Filled.Home
        "jobs" -> Icons.Filled.Work
        "assistant" -> Icons.Filled.AutoAwesome
        "groceries" -> Icons.Filled.ShoppingCart
        "things" -> Icons.Filled.Category
        else -> fallback
    }
}
