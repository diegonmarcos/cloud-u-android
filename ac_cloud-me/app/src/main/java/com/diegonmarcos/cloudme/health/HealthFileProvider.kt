package com.diegonmarcos.cloudme.health

import androidx.core.content.FileProvider

/** A named subclass so its component name cannot collide with another module's FileProvider (the updater's has the same reason). */
class HealthFileProvider : FileProvider()
