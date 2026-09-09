package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads source files as text, same instrument and
 */
class OutgoingDateWiringTest {

    @Test fun `the attribution date is formatted in the zone quote() was handed`() {
        assertTrue(
            "quote() must format through its zone parameter: 'val date = " +
                "MailDates.formatFull(o.receivedAt, zone)'. Without the argument the default " +
                "(the device zone) answers, and the switch is inert on every reply.",
            "val date = MailDates.formatFull(o.receivedAt, zone)" in lines(COMPOSE_VIEW_MODEL),
        )
    }

    @Test fun `quote() cannot be called without a zone`() {
        assertTrue(
            "quote()'s zone parameter must have NO default: 'private fun quote(o: Email, zone: " +
                "ZoneId): String {'. A default resurrects the silent third-caller trap the " +
                "parameter exists to close.",
            "private fun quote(o: Email, zone: ZoneId): String {" in lines(COMPOSE_VIEW_MODEL),
        )
    }

    @Test fun `the forwarded header's date resolves the setting`() {
        assertTrue(
            "buildForwarded must pass the resolved zone: 'date = MailDates.formatFull(" +
                "o.receivedAt, resolveOutgoingDateZone(settings.quotedDatesUtc)),'. Without it " +
                "every forward keeps giving away the device's time zone.",
            "date = MailDates.formatFull(o.receivedAt, resolveOutgoingDateZone(settings.quotedDatesUtc))," in
                lines(COMPOSE_VIEW_MODEL),
        )
    }

    @Test fun `the background enrichment path resolves the setting too`() {
        assertTrue(
            "the cache-first prefill's late quote must resolve the zone: '_replyQuote.value = " +
                "replyBody(quote(original, resolveOutgoingDateZone(settings.quotedDatesUtc)))'. " +
                "This is the caller far from quote()'s definition — the offline/cached path — and " +
                "the one a partial fix misses.",
            "_replyQuote.value = replyBody(quote(original, resolveOutgoingDateZone(settings.quotedDatesUtc)))" in
                lines(COMPOSE_VIEW_MODEL),
        )
    }

    @Test fun `buildPrefill resolves once and hands the zone to both quote calls`() {
        val all = lines(COMPOSE_VIEW_MODEL)
        assertTrue(
            "buildPrefill must resolve the zone by suspending on the setting: 'val zone = " +
                "resolveOutgoingDateZone(settings.quotedDatesUtc)'.",
            "val zone = resolveOutgoingDateZone(settings.quotedDatesUtc)" in all,
        )
        assertEquals(
            "reply and reply-all must both hand that zone to quote(): two identical lines " +
                "'body = replyBody(if (quoteBody) quote(original, zone) else \"\"),'",
            2,
            all.count { it == """body = replyBody(if (quoteBody) quote(original, zone) else ""),""" },
        )
    }

    @Test fun `no other formatFull call hides in the composer`() {
        // Exactly the two pinned production lines: a third call site added with the default zone
        // would reopen the leak while every pinned line stays present.
        val calls = lines(COMPOSE_VIEW_MODEL).filter { "MailDates.formatFull(" in it }
        assertEquals(
            "ComposeViewModel must format outgoing dates in exactly two places, both zoned; " +
                "found: $calls",
            listOf(
                "val date = MailDates.formatFull(o.receivedAt, zone)",
                "date = MailDates.formatFull(o.receivedAt, resolveOutgoingDateZone(settings.quotedDatesUtc)),",
            ).sorted(),
            calls.sorted(),
        )
    }

    @Test fun `the setting is exported and restored like every other preference`() {
        val repository = code(SETTINGS_REPOSITORY)
        assertTrue(
            "SettingsRepository.snapshotBackup must carry 'quotedDatesUtc = " +
                "quotedDatesUtc.first()': without it the switch is absent from every export.",
            Regex("""quotedDatesUtc\s*=\s*quotedDatesUtc\.first\(\s*\)""").containsMatchIn(repository),
        )
        assertTrue(
            "restoreBackup must apply it: 'backup.quotedDatesUtc?.let { setQuotedDatesUtc(it) }'. " +
                "Exported and never read back is the same defect one step later: after a restore " +
                "the box unticks itself in silence.",
            Regex("""backup\.quotedDatesUtc\?\.let\s*\{\s*setQuotedDatesUtc\(\s*it\s*\)\s*}""")
                .containsMatchIn(repository),
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** [file]'s code as trimmed WHOLE lines, comments cut. Rules compare with equality, never
     *  contains: a mutation that lengthens a line must change the line. */
    private fun lines(file: File): List<String> = code(file).lines().map { it.trim() }

    /** [file]'s code as one string, comments cut — same reader as ReplyBarWiringTest. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    companion object {
        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        private const val SETTINGS_REPOSITORY_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"

        /** Repo root, walked up from the module's working directory — the rules read BOTH modules. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
        private val SETTINGS_REPOSITORY: File by lazy { File(root, SETTINGS_REPOSITORY_PATH) }
    }
}
