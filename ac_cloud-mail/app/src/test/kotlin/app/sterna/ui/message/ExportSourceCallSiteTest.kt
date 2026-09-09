package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * "Save as .eml" has no decision a JVM test can execute on the app side: the ViewModel needs an
 */
class ExportSourceCallSiteTest {

    /**
     * The ViewModel asks the repository for the BYTES, and writes those bytes. Rewrite the first
     */
    @Test fun `the ViewModel writes the repository's bytes, truncating the document first`() {
        val lines = codeLines(viewModel().readText())
        assertEquals(
            "exportSource must fetch the message as bytes through MailRepository.rawSource — " +
                "anything else writes something that is not the server's message. Lines found:",
            listOf("val bytes = repo.rawSource(credentials, id)"),
            lines.filter { "rawSource(" in it },
        )
        assertEquals(
            "exportSource must open the picked document in \"wt\" mode (truncate: a re-saved " +
                "file must not keep the tail of a longer one) and write the BYTES. The second " +
                "line is `saveAttachment`'s, pinned in the same shape by " +
                "[SaveAttachmentCallSiteTest]; this rule reads the whole file, so both appear. " +
                "Lines found:",
            listOf(
                "getApplication<Application>().contentResolver.openOutputStream(uri, \"wt\")?.use { it.write(bytes) }",
                "getApplication<Application>().contentResolver.openOutputStream(uri, \"wt\")?.use { it.write(bytes) }",
            ),
            lines.filter { "openOutputStream(" in it },
        )
        assertEquals(
            "the picker CREATES the document before the callback runs: every failure, and the " +
                "owner-mismatch refusal, must delete it again, or an empty `Subject.eml` sits in " +
                "Downloads looking like a saved message. Lines found:",
            listOf(
                // exportSource: the owner refusal, then any failure
                "discardDocument(uri)",
                "discardDocument(uri)",
                // saveAttachment's four, pinned as such by [SaveAttachmentCallSiteTest]
                "discardDocument(uri)",
                "discardDocument(uri)",
                "discardDocument(uri)",
                "discardDocument(uri)",
                "private suspend fun discardDocument(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {",
                "runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }",
            ),
            lines.filter { "discardDocument(" in it || "deleteDocument(" in it },
        )
    }

    /**
     * The screen declares the picker with the message MIME type — `text/plain` or a wildcard type and the
     */
    @Test fun `the menu entry launches a message-rfc822 document picker`() {
        val lines = codeLines(screen().readText())
        assertEquals(
            "the picker must be created for the message/rfc822 MIME type. The second line is the " +
                "attachment picker's wildcard, pinned by [SaveAttachmentCallSiteTest]; this rule " +
                "reads the whole file, so both appear, and the ORDER is part of the rule — the " +
                "wildcard on the .eml launcher would offer a message a name with no extension. " +
                "Lines found:",
            listOf(
                "ActivityResultContracts.CreateDocument(\"message/rfc822\"),",
                "ActivityResultContracts.CreateDocument(\"*/*\"),",
            ),
            lines.filter { "CreateDocument(" in it },
        )
        assertEquals(
            "the launcher must be declared once, and launched by the menu entry with the proposed " +
                "name — without the launch the entry writes nothing. Lines found:",
            listOf(
                "val exportLauncher = rememberLauncherForActivityResult(",
                "onClick = { menuOpen = false; exportFor = active.emailId; exportLauncher.launch(exportName) },",
            ),
            lines.filter { "exportLauncher" in it },
        )
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun viewModel(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")

    private fun screen(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
