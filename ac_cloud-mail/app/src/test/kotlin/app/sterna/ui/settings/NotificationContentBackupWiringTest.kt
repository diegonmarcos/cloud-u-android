package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `SettingsRepository.kt` as text and proves nothing
 */
class NotificationContentBackupWiringTest {

    @Test fun `the position is exported like every other preference`() {
        assertEquals(
            "snapshotBackup must carry 'notificationContent = notificationContent.first().name,' " +
                "— without it the position is absent from every export, and restoring on a new " +
                "device silently puts a reader who had asked for less back on sender + subject.",
            listOf("notificationContent = notificationContent.first().name,"),
            codeLines(SETTINGS_REPOSITORY).filter { it.startsWith("notificationContent = notificationContent") },
        )
    }

    @Test fun `the import applies the position it read, and nothing else`() {
        assertEquals(
            "restoreBackup must apply exactly " +
                "'notificationContentOrNull(backup.notificationContent)?.let { setNotificationContent(it) }'. " +
                "The whole line matters, argument by argument: 'setNotificationContent(" +
                "NotificationContent.BODY_PREVIEW)' there makes every settings import turn the body " +
                "preview on, 'backup.deliveryMode' inside the call restores a delivery mode as a " +
                "notification position, and no line at all means the position is exported forever " +
                "and never read back. An empty list below means this lint matched NOTHING, which " +
                "is itself the failure — the line moved and this rule must be taught the new shape.",
            listOf("notificationContentOrNull(backup.notificationContent)?.let { setNotificationContent(it) }"),
            codeLines(SETTINGS_REPOSITORY).filter { "notificationContentOrNull(" in it && "backup" in it },
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val SETTINGS_REPOSITORY_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"

        /** Repo root, walked up from the module's working directory — the rules read another module. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_REPOSITORY_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this lint reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_REPOSITORY: File by lazy { File(root, SETTINGS_REPOSITORY_PATH) }
    }
}
