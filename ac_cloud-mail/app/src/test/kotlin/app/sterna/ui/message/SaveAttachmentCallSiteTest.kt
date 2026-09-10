package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Save" on an attachment row has no decision a JVM test can execute on the app side: the
 */
class SaveAttachmentCallSiteTest {

    /**
     * The bytes come from the repository and go into the picked document, and nothing else happens
     */
    @Test fun `the saved bytes come from the server and go into the picked document`() {
        val body = saveAttachmentBody()
        assertEquals(
            "saveAttachment must fetch the part through MailRepository.downloadAttachment, for the " +
                "message the picker was launched for. Lines found:",
            listOf("val bytes = repo.downloadAttachment(credentials, part, ownerId)"),
            body.filter { "downloadAttachment(" in it },
        )
        assertEquals(
            "saveAttachment must open the picked document in \"wt\" mode and write the BYTES. " +
                "Lines found:",
            listOf("getApplication<Application>().contentResolver.openOutputStream(uri, \"wt\")?.use { it.write(bytes) }"),
            body.filter { "openOutputStream(" in it },
        )
    }

    /**
     * And they are REFUSED when there are none of them. `MimeParser.decodeBytes("", …)` answers a
     */
    @Test fun `zero bytes are refused instead of written and announced as saved`() {
        val body = saveAttachmentBody()
        assertEquals(
            "saveAttachment must ask requireAttachmentBytes about the bytes it just downloaded, " +
                "naming the message and the part the picker was launched for — the two keys a bug " +
                "report has to be able to quote. Lines found:",
            listOf("requireAttachmentBytes(ownerId, partKey, bytes)"),
            body.filter { "requireAttachmentBytes" in it },
        )
        val download = body.indexOf("val bytes = repo.downloadAttachment(credentials, part, ownerId)")
        val guard = body.indexOf("requireAttachmentBytes(ownerId, partKey, bytes)")
        val write = body.indexOf("withContext(Dispatchers.IO) {")
        assertTrue("the download must still be in saveAttachment: $body", download >= 0)
        assertTrue("the write must still be in saveAttachment: $body", write >= 0)
        assertTrue(
            "the refusal must sit BETWEEN the download and the write (download at $download, " +
                "refusal at $guard, write at $write): below the write, her document has already " +
                "been truncated to zero bytes when the refusal fires, and `discardDocument` " +
                "runCatchings its deletion — a provider that cannot delete then leaves behind " +
                "exactly the 0-byte file this guard exists to prevent.",
            guard in (download + 1) until write,
        )
    }

    /**
     * THE hole every trimmed-line assertion is blind to: wrapping the refusal in an
     */
    @Test fun `the refusal sits in the same block as the download and the write`() {
        val raw = saveAttachmentRawLines()
        val refusal = indentOf(raw, "requireAttachmentBytes(ownerId, partKey, bytes)")
        assertEquals(
            "same indentation as the download means same block: nested inside an `if (…) { … }` " +
                "the refusal reads identically, every trimmed-line assertion stays green, and it " +
                "fires on nothing",
            indentOf(raw, "val bytes = repo.downloadAttachment(credentials, part, ownerId)"),
            refusal,
        )
        assertEquals(
            "…and the same indentation as the write it stands in front of",
            indentOf(raw, "withContext(Dispatchers.IO) {"),
            refusal,
        )
    }

    /**
     * And they never touch the app's cache on the way. `storage.cacheAttachment` belongs to the
     */
    @Test fun `saving never writes a copy into the app's cache`() {
        // The open path moved into AttachmentOpen.kt, shared with the message list. The
        // cache write is pinned THERE, whole and exactly once, and MessageViewModel must now
        // carry none at all -- a cacheAttachment line reappearing beside the save path is
        // the copy-into-the-cache this rule exists to stop.
        assertEquals(
            "the open path must cache an attachment in exactly ONE place. Lines found:",
            listOf("val file = storage.cacheAttachment(part.name, bytes)"),
            codeLines(attachmentOpen().readText()).filter { "cacheAttachment(" in it },
        )
        assertEquals(
            "MessageViewModel must not cache an attachment at all: the open path owns that " +
                "write and the save path must never make a second copy. Lines found:",
            emptyList<String>(),
            codeLines(viewModel().readText()).filter { "cacheAttachment(" in it },
        )
        assertEquals(
            "…and saveAttachment itself must name neither the cache nor a FileProvider URI. " +
                "Lines found:",
            emptyList<String>(),
            saveAttachmentBody().filter { "cacheAttachment" in it || "FileProvider" in it },
        )
    }

    /**
     * The owner guard, on TWO keys. The reader is a conversation: the row hands over `msg.id`,
     */
    @Test fun `the picker's result is refused unless it names the very part it was launched for`() {
        val body = saveAttachmentBody()
        assertEquals(
            "saveAttachment must re-resolve the message from _messages by ownerId, the part by " +
                "partId ?: blobId, and refuse anything that does not resolve — including an empty " +
                "key. Lines found:",
            listOf(
                "val part = _messages.value.firstOrNull { it.id == ownerId }",
                "?.body?.fileAttachmentParts()",
                "?.firstOrNull { (it.partId ?: it.blobId) == partKey }",
                "if (ownerId.isEmpty() || partKey.isEmpty() || part == null) {",
            ),
            body.filter {
                "_messages.value" in it || "fileAttachmentParts()" in it ||
                    "blobId) == partKey" in it || "part == null" in it
            },
        )
        val refusal = body.indexOf("if (ownerId.isEmpty() || partKey.isEmpty() || part == null) {")
        val download = body.indexOf("val bytes = repo.downloadAttachment(credentials, part, ownerId)")
        assertTrue(
            "the refusal must come BEFORE the download (refusal at $refusal, download at " +
                "$download): moved after it, a result meant for another part is fetched from the " +
                "server before anyone asks whether it belongs here.",
            refusal in 0 until download,
        )
    }

    /**
     * `CreateDocument` has the provider create the document BEFORE the callback runs. So all three
     */
    @Test fun `every way out of a save deletes the document the picker created`() {
        assertEquals(
            "MessageViewModel must discard the created document on the owner refusal, on the " +
                "too-large refusal and on any failure. One missing and an empty file with a real " +
                "name is left behind in her Downloads. Lines found:",
            listOf(
                // exportSource: owner refusal, then any failure
                "discardDocument(uri)",
                "discardDocument(uri)",
                // saveAttachment: owner refusal, cancellation, too large, any failure
                "discardDocument(uri)",
                "discardDocument(uri)",
                "discardDocument(uri)",
                "discardDocument(uri)",
                "private suspend fun discardDocument(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {",
                "runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }",
            ),
            codeLines(viewModel().readText()).filter { "discardDocument(" in it || "deleteDocument(" in it },
        )
        assertEquals(
            "saveAttachment must discard the document exactly four times — one per way out. " +
                "Lines found:",
            listOf(
                "discardDocument(uri)", "discardDocument(uri)",
                "discardDocument(uri)", "discardDocument(uri)",
            ),
            saveAttachmentBody().filter { "discardDocument(" in it },
        )
    }

    /**
     * The fourth exit, and the one nobody watches: each pager page owns its ViewModel, so swiping
     */
    @Test fun `a save cancelled under its own page still takes the created document with it`() {
        val body = saveAttachmentBody()
        assertEquals(
            "saveAttachment must catch CancellationException on its own, before the generic " +
                "catch, and rethrow it. Swallowed, it is reported as a failure that never " +
                "happened; not caught at all, the created document is never deleted. Lines found:",
            listOf("} catch (cancelled: CancellationException) {", "throw cancelled"),
            body.filter { "CancellationException" in it || "throw cancelled" in it },
        )
        assertEquals(
            "discardDocument must run NonCancellable, or it throws instead of deleting in the " +
                "very case it is there for. Lines found:",
            listOf("private suspend fun discardDocument(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {"),
            codeLines(viewModel().readText()).filter { "fun discardDocument(" in it },
        )
    }

    /**
     * THE mutation that pinning the NUMBER of discards cannot see, and the worst one on this
     */
    @Test fun `no fourth catch turns a refusal back into an announced save`() {
        val body = saveAttachmentBody()
        assertEquals(
            "saveAttachment must carry exactly these three catch clauses, in this order: " +
                "cancellation first (not a failure, and rethrown), then our own too-large refusal " +
                "with its own sentence, then everything else — which discards and reports. A " +
                "fourth clause is a way out that answers the user without either. Lines found:",
            listOf(
                "} catch (cancelled: CancellationException) {",
                "} catch (t: ContentTooLargeException) {",
                "} catch (t: Throwable) {",
            ),
            body.filter { it.startsWith("} catch (") },
        )
        val announcements = body.filter { "message_export_saved" in it }
        assertEquals(
            "saveAttachment must announce a save in exactly ONE place, and it is the line after " +
                "the write. Lines found:",
            listOf("_actionStatus.value = getApplication<Application>().getString(R.string.message_export_saved, name)"),
            announcements,
        )
        val announced = body.indexOf(announcements.first())
        val firstCatch = body.indexOfFirst { it.startsWith("} catch (") }
        assertTrue("saveAttachment must still catch something at all: $body", firstCatch >= 0)
        assertTrue(
            "the save must be announced BEFORE the first catch (announced at $announced, first " +
                "catch at $firstCatch). An announcement emitted from INSIDE a catch says " +
                "\"Saved as rapport.pdf\" about a save that just failed — that is the defect " +
                "itself, not an edge of it.",
            announced in 0 until firstCatch,
        )
    }

    /**
     * And the general catch does not put the exception's own English on her screen. The message
     */
    @Test fun `the failure sentence is translated before the exception's own text is used`() {
        val body = saveAttachmentBody()
        assertEquals(
            "saveAttachment's general catch must consult readFailureStringRes BEFORE falling back " +
                "on the exception's raw text, exactly as exportSource's catch does. Lines found:",
            listOf("_actionStatus.value = readFailureStringRes(t)?.let { getApplication<Application>().getString(it) }"),
            body.filter { "readFailureStringRes" in it },
        )
        val mapped = body.indexOfFirst { "readFailureStringRes" in it }
        val raw = body.indexOfFirst { "status_save_attachment_failed" in it }
        assertTrue("the raw fallback must still exist for everything else: $body", raw >= 0)
        assertTrue(
            "the mapping must come BEFORE the raw fallback (mapped at $mapped, raw at $raw): " +
                "after it, the English text wins and the translation is never reached.",
            mapped in 0 until raw,
        )
        assertEquals(
            "…and the too-large refusal keeps its OWN sentence: a refusal of SIZE is not a missing " +
                "octet, and its catch stays ahead of the general one so it never reaches the " +
                "mapping. Lines found:",
            listOf("_actionStatus.value = getApplication<Application>().getString(R.string.status_attachment_too_large)"),
            body.filter { "status_attachment_too_large" in it },
        )
    }

    /**
     * The screen's half. The picker is registered with a wildcard type, ONCE (the contract freezes
     */
    @Test fun `the row launches a wildcard picker with a sanitised name`() {
        val lines = codeLines(screen().readText())
        assertEquals(
            "the save picker must be created for the wildcard MIME type, exactly once — a " +
                "narrower type and the picker offers the wrong extension for every other kind of " +
                "attachment. Lines found:",
            listOf("ActivityResultContracts.CreateDocument(\"*/*\"),"),
            lines.filter { it == "ActivityResultContracts.CreateDocument(\"*/*\")," },
        )
        assertEquals(
            "the launcher must be declared once and LAUNCHED with the proposed name — without " +
                "the launch the button does nothing at all. Lines found:",
            listOf(
                "val saveAttachmentLauncher = rememberLauncherForActivityResult(",
                "saveAttachmentLauncher.launch(saveAttachmentName)",
            ),
            lines.filter { "saveAttachmentLauncher" in it },
        )
        assertEquals(
            "the proposed name must go through attachmentFileName: `part.name` raw puts a `/` or " +
                "a `:` into the picker's name box, and loses the extension of a long name. " +
                "Lines found:",
            listOf("saveAttachmentName = attachmentFileName(part.name)"),
            lines.filter { "attachmentFileName(" in it },
        )
    }

    /**
     * And the three keys are `rememberSaveable`, not `remember`. The picker is another activity:
     */
    @Test fun `the three picker keys survive an activity recreated behind the picker`() {
        val lines = codeLines(screen().readText())
        assertEquals(
            "the message, the part key and the proposed name must each be a rememberSaveable. " +
                "Lines found:",
            listOf(
                "var saveAttachmentFor by rememberSaveable { mutableStateOf(\"\") }",
                "var saveAttachmentPart by rememberSaveable { mutableStateOf(\"\") }",
                "var saveAttachmentName by rememberSaveable { mutableStateOf(\"\") }",
            ),
            lines.filter { it.startsWith("var saveAttachment") },
        )
    }

    /**
     * The row's own tap is untouched: the name still opens the attachment, the icon saves it —
     */
    @Test fun `the row still opens on its body, and saves on its icon`() {
        val lines = codeLines(screen().readText())
        assertEquals(
            "the attachment row must keep its own open-on-tap and gain one save button. " +
                "Lines found:",
            listOf(".clickable { onOpen(att) }", "IconButton(onClick = { onSave(att) }) {"),
            lines.filter { "onOpen(att)" in it || "onSave(att)" in it },
        )
        assertEquals(
            "the section must be handed both lambdas by name, and the header must pass the " +
                "message id to the save one exactly as it does to the open one. Lines found:",
            listOf(
                "onSaveAttachment = { part, ownerId ->",
                "onSaveAttachment: (EmailBodyPart, String) -> Unit,",
                "msg, full, attachmentStatus, onOpenAttachment, onSaveAttachment, calendar, onRespondToInvite,",
                "onSaveAttachment: (EmailBodyPart, String) -> Unit,",
                "onSave = { part -> onSaveAttachment(part, msg.id) },",
            ),
            lines.filter { "onSaveAttachment" in it },
        )
    }

    /**
     * The code lines of `saveAttachment`, from its declaration to the next member declared at class
     * level. Fails loudly when the function is gone rather than passing on an empty list.
     */
    private fun saveAttachmentBody(): List<String> {
        val body = codeLines(saveAttachmentRawLines().joinToString("\n"))
        assertTrue("saveAttachment must have a body", body.isNotEmpty())
        return body
    }

    /**
     * The same span, RAW — indentation intact. A sibling of [saveAttachmentBody] rather than a
     */
    private fun saveAttachmentRawLines(): List<String> {
        val lines = viewModel().readText().lines()
        val start = lines.indexOfFirst {
            it.trim().startsWith("fun saveAttachment(uri: Uri, proposedName: String, ownerId: String, partKey: String)")
        }
        assertTrue("MessageViewModel must still declare saveAttachment(uri, proposedName, ownerId, partKey)", start >= 0)
        val rest = lines.drop(start + 1)
        // `private ` and not `private fun `: the next member is `private suspend fun
        // discardDocument`, and a narrower marker runs the body straight past the end of it.
        val end = rest.indexOfFirst { it.startsWith("    fun ") || it.startsWith("    private ") }
            .let { if (it < 0) rest.size else it }
        return rest.take(end)
    }

    /** The leading spaces of the one line of [raw] that trims to [needle]. */
    private fun indentOf(raw: List<String>, needle: String): String {
        val line = raw.firstOrNull { it.trim() == needle }
        assertTrue(
            "saveAttachment must still carry the line `$needle`. Lines found: ${raw.map { it.trim() }}",
            line != null,
        )
        return line.orEmpty().takeWhile { it == ' ' }
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun viewModel(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")

    private fun screen(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")

    private fun attachmentOpen(): File =
        locate("app/src/main/kotlin/app/sterna/ui/attachment/AttachmentOpen.kt")

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
