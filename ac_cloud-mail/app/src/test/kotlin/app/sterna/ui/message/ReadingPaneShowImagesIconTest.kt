package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The reading pane's "show images" affordance, after #472: ONE icon in the reading row under the
 * tags and ONE named entry in the overflow — and no TEXT strip above the body. The text strip
 * (`ImagesStrip`, a TextButton reading "Show images") became redundant the day the icon row landed
 * (#293/#308): both unblock the same message, and the pane showed both at once.
 *
 * This is a SOURCE LINT, not a behaviour test, in the established style of this module: nothing in
 * the JVM lays the Compose tree out, so the only execution these lines get is the one that reads
 * them.
 */
class ReadingPaneShowImagesIconTest {

    @Test fun `the text show-images strip is gone from the reading pane`() {
        val source = messageScreenSource()
        assertFalse(
            "the redundant text strip must not exist any more: 'ImagesStrip(' drew a TextButton " +
                "labelled 'Show images' above the body while the icon row already offered the " +
                "same action. A mutation that brings it back must go red here.",
            "ImagesStrip(" in source,
        )
        assertFalse(
            "the strip's wiring must be gone with it: 'imagesStrip' would be a second answer to " +
                "'are pictures blocked?' that no view reads.",
            "imagesStrip" in source,
        )
    }

    @Test fun `the show-images ICON still exists in the reading row, and the overflow still names it`() {
        val source = messageScreenSource()
        // The reading row: IconButton(onClick = viewModel::showImagesOnce) { Icon(Icons.Filled.Image,
        // contentDescription = stringResource(R.string.message_show_images)) } — the icon's
        // accessible name. The overflow: its named entry. Exactly these two uses of the string
        // survive: the reader's ICON and the menu's NAME (#293).
        assertEquals(
            "the only two uses of R.string.message_show_images must be the icon's contentDescription " +
                "and the overflow entry's text. Fewer = the affordance lost a surface; more = a " +
                "redundant label is back.",
            2,
            Regex("R\\.string\\.message_show_images").findAll(source).count(),
        )
        assertEquals(
            "the one-time show must still be reachable from the reading row's icon",
            1,
            Regex("""IconButton\(onClick = viewModel::showImagesOnce\)""").findAll(source).count(),
        )
    }

    private fun messageScreenSource(): String =
        File(repoRoot(), "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").readText()

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
}