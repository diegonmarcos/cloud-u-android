package com.diegonmarcos.superapp.search

/**
 * Implemented by the host Activity: opens the full-screen search the Home star opens
 * ([SearchSheetFragment]). Cloud ▸ Apps and the Home swipe sheet show the same search inline
 * ([InlineSearch]) and do not need it.
 */
interface SearchOpener {
    fun openSearchSheet()
}
