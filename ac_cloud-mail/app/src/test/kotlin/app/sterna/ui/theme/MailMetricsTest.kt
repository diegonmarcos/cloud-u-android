package app.sterna.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The density declaration, EXECUTED: the type scale really is smaller, style by style, and the
 * element tokens really are dense. A source grep (test-mail-density.sh) proves the screens read the
 * tokens; this proves the tokens are worth reading.
 */
class MailMetricsTest {

    private fun Typography.all(): List<Pair<String, TextStyle>> = listOf(
        "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )

    @Test fun `every one of the fifteen styles is smaller than Material's, and none is unreadable`() {
        val base = Typography().all().toMap()
        val dense = denseTypography().all()
        assertEquals(15, dense.size)
        for ((name, style) in dense) {
            val was = base.getValue(name)
            assertTrue("$name: ${style.fontSize} is not smaller than Material's ${was.fontSize}", style.fontSize.value < was.fontSize.value)
            assertTrue("$name: ${style.lineHeight} is not smaller than ${was.lineHeight}", style.lineHeight.value < was.lineHeight.value)
            assertTrue("$name: ${style.fontSize} is below the ${MailMetrics.MIN_TEXT_SP}sp floor", style.fontSize.value >= MailMetrics.MIN_TEXT_SP)
        }
    }

    @Test fun `the app typography is the dense one`() {
        assertEquals(denseTypography().bodyMedium.fontSize, SternaTypography.bodyMedium.fontSize)
        assertTrue(SternaTypography.bodyMedium.fontSize.value < Typography().bodyMedium.fontSize.value)
    }

    @Test fun `the element tokens are dense and the touch floor holds`() {
        assertTrue("icon", MailMetrics.icon.value in 14f..20f)
        assertTrue("icon button", MailMetrics.iconButton.value in 36f..40f)
        assertTrue("tap floor", MailMetrics.tap.value in 36f..40f)
        assertTrue("16 step", MailMetrics.s16.value <= 13.6f)
        assertTrue("48 step (a row / touch band)", MailMetrics.s48.value <= 40f)
        assertTrue("steps keep their order", MailMetrics.s4 < MailMetrics.s8 && MailMetrics.s8 < MailMetrics.s16 && MailMetrics.s16 < MailMetrics.s24)
    }
}
