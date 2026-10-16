package com.diegonmarcos.cloudnav.tracker

/** "How this engine works": plain language, generated from the live [EngineConfig] so it can't drift from the rules in [TriggerPlanner] / [SourceRules]. */
object Explanation {
    fun sections(c: EngineConfig): List<Pair<String, String>> {
        val acc = SourceRules.accurateProvider(c)
        val triggers = listOfNotNull(
            if (c.accurateByTime) "${c.accurateEveryHours} h have passed since the last accurate fix" else null,
            if (c.accurateBySteps) "you have walked ${c.accurateEverySteps} steps since it" else null,
        )
        return listOf(
            "When it runs" to
                "Android wakes the engine about every 15 minutes (the shortest interval WorkManager allows). There is no permanent notification and nothing stays awake between wake-ups. " +
                "Each wake-up reads the step counter and the compass heading, then does whatever is due. So every interval below is checked every ~15 minutes, and Android may delay a wake-up in battery saver or Doze.",
            "Accurate fix" to
                (if (triggers.isEmpty()) "Automatic accurate fixes are off; only the first one is taken. "
                else "An accurate (precise) fix is taken when " + triggers.joinToString(" or ") + " — whichever comes first. ") +
                (if (acc == Provider.GPS) "It comes from GPS (also when both sources are on). If GPS gets no fix and cell towers are on, that one fix falls back to cell towers."
                else "GPS is off, so it comes from cell towers (the network location provider), which is coarser but cheaper.") +
                " Each accurate fix restarts the step and time counters.",
            "Cell-tower updates" to
                (if (SourceRules.lowPowerProvider(c) == null) "Cell towers are off, so there are no cell-tower updates."
                else if (!c.cellByTime) "Hourly cell-tower updates are off."
                else "Every ${c.cellEveryHours} h the engine asks the network provider for a rough position (hundreds of metres to kilometres, almost no battery). It is skipped when an accurate fix is due, because that one replaces it.") ,
            "Estimated positions" to
                (if (!c.motionBySteps) "Step-based estimates are off."
                else "Every ${c.motionEverySteps} steps between accurate fixes the engine estimates where you are by dead reckoning: the last accurate fix, plus steps × your step length, in the compass direction recorded at each wake-up. ") +
                "Your step length starts at 0.72 m and is recalibrated every time two accurate fixes are close enough to compare (straight-line distance ÷ steps). " +
                "These points are marked predicted and carry an uncertainty circle: the last fix's own accuracy plus 15% of the distance walked, so it widens with every step. " +
                "The next accurate fix replaces the estimate (snap back), so errors never add up across fixes.",
            "What it needs" to
                "Location (fine or coarse) for fixes; background location so fixes still work with the app closed; physical activity for the step counter. " +
                "Without a step sensor or its permission, step triggers and estimates pause and the time triggers still run. Everything is stored on this device only (tracker_engine.db).",
        )
    }
}
