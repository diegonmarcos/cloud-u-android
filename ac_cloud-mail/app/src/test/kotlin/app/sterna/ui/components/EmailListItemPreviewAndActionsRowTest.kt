package app.sterna.ui.components

import app.sterna.core.data.settings.PreviewLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #500: the list row's preview grew from 3 lines to 4 and gained an indent and an italic, and the
 * row grew a third line of icons in a fixed order. Every one of those is the kind of change that
 * still compiles, still shows SOME preview and SOME icons, and reads green on a dashboard that
 * only asked "does maxLines exist" or "is the Resume icon present" — both true before AND after
 * this ticket. So this pins the VALUE (4, not "some positive number") and the ORDER (source
 * position, not "all four are present somewhere").
 */
class EmailListItemPreviewAndActionsRowTest {

    /** The setting is stored by NAME (`SettingsRepository.setPreviewLines`), so #500 could not
     *  rename [PreviewLines.THREE] without stranding every phone already on it — only its line
     *  count moved, 3 -> 4. */
    @Test
    fun `THREE preview tier carries 4 lines, not 3`() {
        assertEquals(4, PreviewLines.THREE.lines)
    }

    @Test
    fun `preview text is indented and italic, at the previewLines value`() {
        val body = previewBlock()
        assertTrue(
            "preview Text must set fontStyle = FontStyle.Italic:\n$body",
            body.contains("fontStyle = FontStyle.Italic"),
        )
        assertTrue(
            "preview Text must be indented via Modifier.padding(start = ...):\n$body",
            Regex("""modifier = Modifier\.padding\(start = \d+\.dp\)""").containsMatchIn(body),
        )
        assertTrue(
            "preview Text must still cap at the previewLines value, not a literal number:\n$body",
            body.contains("maxLines = previewLines"),
        )
    }

    /**
     * The new row's children, in the order #500 specified: Resume Mail, Copy Code, the literal
     * `|`, then the attachment chips. A mutation that swaps any two of these compiles and still
     * draws all four — only their SOURCE ORDER tells them apart.
     */
    @Test
    fun `list row actions draw Resume, Copy Code, the bar, then chips - in that order`() {
        val body = listRowActionsBlock()
        val resume = body.indexOf("TextTool.RESUME")
        val copyCode = body.indexOf("Icons.Filled.ContentCopy")
        val bar = body.indexOf("ReadingGroupSeparator()")
        val chips = body.indexOf("AttachmentChips(")
        val positions = listOf(
            "TextTool.RESUME" to resume,
            "Icons.Filled.ContentCopy" to copyCode,
            "ReadingGroupSeparator()" to bar,
            "AttachmentChips(" to chips,
        )
        positions.forEach { (name, index) -> assertTrue("$name not found in ListRowActions:\n$body", index >= 0) }
        assertTrue(
            "expected order Resume < Copy Code < '|' < chips, got $positions",
            resume < copyCode && copyCode < bar && bar < chips,
        )
    }

    /** Everything from `if (previewLines > 0) {` up to the row's next declared step. */
    private fun previewBlock(): String {
        val lines = SOURCE.readLines()
        val start = lines.indexOfFirst { it.contains("if (previewLines > 0) {") }
        require(start >= 0) { "could not locate the preview block in $SOURCE" }
        val end = lines.withIndex().first { (i, line) -> i > start && line.contains("ListRowActions(") }.index
        return lines.subList(start, end).joinToString("\n")
    }

    /** The whole body of the top-level `private fun ListRowActions(...) { ... }` declaration. */
    private fun listRowActionsBlock(): String {
        val lines = SOURCE.readLines()
        val start = lines.indexOfFirst { it.contains("private fun ListRowActions(") }
        require(start >= 0) { "could not locate ListRowActions in $SOURCE" }
        // The function is declared at column 0, so its own closing brace is too.
        val end = lines.withIndex().first { (i, line) -> i > start && line == "}" }.index
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private companion object {
        val SOURCE: File by lazy {
            val relative = "app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt"
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, relative) }
                .firstOrNull { it.isFile }
                ?: error(
                    "cannot locate $relative from ${File("").absolutePath} — this lint reads the " +
                        "composable as text and needs a working directory inside the checkout",
                )
        }
    }
}
