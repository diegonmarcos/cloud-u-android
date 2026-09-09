package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [ProtectedSubjectTest] executes the decision; this pins WHAT IT IS CALLED WITH, in the one place
 */
class DecryptedSubjectCallSiteTest {

    @Test fun `the decrypted branch takes its subject from the decrypted entity`() {
        assertEquals(
            "the PGP_ENCRYPTED branch must assign the subject from the DECRYPTED ENTITY, " +
                "on exactly one line and with exactly these arguments, and no other code line " +
                "of the branch may touch a subject at all. Lines found:",
            listOf("subject = decryptedSubject(subject, entity),"),
            encryptedBranch().lines().map { it.trim() }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filter { it.contains("subject", ignoreCase = true) },
        )
    }

    /**
     * The body of `buildDecrypted` up to the `PGP_INLINE` branch that follows the encrypted one.
     */
    private fun encryptedBranch(): String {
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()
        val start = source.indexOf("private fun buildDecrypted(")
        check(start >= 0) { "MailRepository no longer declares buildDecrypted(...)" }
        val end = source.indexOf("CryptoKind.PGP_INLINE -> {", start)
        check(end > start) { "buildDecrypted no longer has a CryptoKind.PGP_INLINE branch after the encrypted one" }
        return source.substring(start, end)
    }

    /** [relative] resolved from the test's working directory, walking up — as [DaoQuerySource] does. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
