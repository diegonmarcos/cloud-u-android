package com.diegonmarcos.superapp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards for "one source": a Notify card must not define its own action list, and a badge's notification must take
 * its buttons from BadgeModel (so the card, which reads the notification, sees every one with its icon).
 */
class NotifyCardGuardTest {
    private val main = File(System.getProperty("user.dir"), "src/main/java/com/diegonmarcos/superapp")

    @Test
    fun `the card defines no action list of its own`() {
        val src = File(main, "launcher/BadgeCards.kt").readText()
        for (bad in listOf("BadgeModel.Action(", "addAction(", "R.drawable.", "Intent(", "PendingIntent.get", "listOf(\"")) {
            assertFalse("BadgeCards.kt must not contain '$bad': actions come from BadgeCardData.Data.actions only", src.contains(bad))
        }
        assertTrue("the card must render d.actions", src.contains("d.actions"))
        val data = File(main, "notificationcenter/BadgeCardData.kt").readText()
        assertFalse("BadgeCardData must not build actions", data.contains("BadgeModel.Action("))
        assertTrue("BadgeCardData must read them from the notification", data.contains("BadgeModel.of("))
    }

    @Test
    fun `badge notifications add actions only through BadgeModel`() {
        val offenders = mutableListOf<String>()
        for (dir in listOf("notificationcenter", "floatingnav")) File(main, dir).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "BadgeModel.kt" }
            .forEach { f ->
                f.readLines().forEachIndexed { i, l ->
                    // IntentFilter.addAction(Intent.ACTION_...) is not a notification button.
                    if (Regex("""\baddAction\(""").containsMatchIn(l) && !Regex("""addAction\((Intent\.|[A-Z_]+\))""").containsMatchIn(l))
                        offenders += "${f.name}:${i + 1}: ${l.trim()}"
                }
            }
        assertEquals("notification buttons must go through BadgeModel.add (icon + label + intent, shown on the card too): $offenders",
            emptyList<String>(), offenders)
    }
}
