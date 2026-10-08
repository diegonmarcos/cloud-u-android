package com.diegonmarcos.cloudsearch.ui

import com.diegonmarcos.cloudsearch.R

/**
 * #797 the icon VOCABULARY a declaration may name (a vertical's, an engine's or a calculator's
 * `icon`), each a Phosphor icon as the mockup draws it (app/tools/phosphor.json, generated into
 * res/drawable/ph_*). test/test-search-shell.sh holds every declared icon to a branch here, so a
 * misspelt name fails the build instead of drawing [fallback].
 */
object IconCatalog {
    val fallback: Int = R.drawable.ph_package

    /** The island's icon while Saved Items is showing (not a declared name). */
    const val SAVED = "saved"

    fun res(name: String): Int = when (name) {
        "house" -> R.drawable.ph_house
        "jobs" -> R.drawable.ph_briefcase
        "assistant" -> R.drawable.ph_shooting_star
        "groceries" -> R.drawable.ph_shopping_cart
        "things" -> R.drawable.ph_package
        "globe" -> R.drawable.ph_globe
        "cloud" -> R.drawable.ph_share_network
        "agents" -> R.drawable.ph_robot
        "reports" -> R.drawable.ph_list_dashes
        "bird" -> R.drawable.ph_bird
        "shield-check" -> R.drawable.ph_shield_check
        "magnifying-glass" -> R.drawable.ph_magnifying_glass
        "percent" -> R.drawable.ph_percent
        "wallet" -> R.drawable.ph_wallet
        "calculator" -> R.drawable.ph_calculator
        SAVED -> R.drawable.ph_star
        else -> fallback
    }

    /** The assistant vertical's icon is drawn in the .ai-nav-icon gradient wherever it appears. */
    fun gradient(name: String): Boolean = name == "assistant"
}
