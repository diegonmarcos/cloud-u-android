package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the instrument of [SubscribedFoldersSurfaceWiringTest],
 */
class LostRoleClaimBordersTest {

    @Test fun `the field is named in five places and no others`() {
        assertEquals(
            "a source line naming Mailbox.lostRoleClaim appeared, moved or changed shape. This " +
                "field is the surviving trace of a role a folder CLAIMED AND LOST: it is declared " +
                "once, filled once from the stored role, and read TWICE — by visibleFolders (does " +
                "it stay in the list?) and by defaultCollapsedFolderIds (does it fold itself on " +
                "the first run?). Both are visibility questions on the folder itself. A reader " +
                "anywhere else is either a routing decision taken on a folder that is the junk of " +
                "nothing, or a second copy of the reading.",
            listOf(
                "app/src/main/kotlin/app/sterna/ui/inbox/CollapsedFolders.kt: " +
                    ".filter { it.role == null && !isRoutingTargetByName(it) && " +
                    "!it.lostRoleClaim && it.id in parents }",
                "app/src/main/kotlin/app/sterna/ui/inbox/SubscribedFolders.kt: " +
                    "isRoutingTargetByName(mailbox) || mailbox.lostRoleClaim",
                "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt: " +
                    "internal fun isLostRoleClaim(role: String?): Boolean =",
                "core/data/src/main/kotlin/app/sterna/core/data/mail/MailboxMapper.kt: " +
                    "lostRoleClaim = isLostRoleClaim(role),",
                "core/jmap/src/main/kotlin/app/sterna/core/jmap/model/Mailbox.kt: " +
                    "val lostRoleClaim: Boolean = false,",
            ),
            mentions("lostroleclaim"),
        )
    }

    /**
     * The guard itself, whole lines. The sweep above sees the ONE line that names the field; this
     */
    @Test fun `the survivor test is the five families, OR-ed, and nothing else`() {
        assertEquals(
            "visibleFolders' survivor test changed shape.",
            listOf(
                "val survives = mailbox.isSubscribed || mailbox.role != null ||",
                "isRoutingTargetByName(mailbox) || mailbox.lostRoleClaim",
                "if (!survives) return@forEach",
            ),
            block(SUBSCRIBED_FOLDERS, "val survives =", 3),
        )
    }

    /** The reading of the mark has ONE caller, which is what its purity is there to buy:
     * a second call site is a second answer to give the day they disagree. The declaration is
     *  screened out by WHOLE-LINE equality, never by `contains`, which would also swallow a
     *  one-line `internal fun` that happens to call it. */
    @Test fun `the reading of the mark is called exactly once in the whole app`() {
        val declaration = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt: " +
            "internal fun isLostRoleClaim(role: String?): Boolean ="
        assertEquals(
            "isLostRoleClaim gained or lost a call site. It answers for the crossing to the UI " +
                "model and for nothing else; inside :core:data the mark is read by the search " +
                "filters, on the STORED role, and that must not be routed through here.",
            listOf(
                "core/data/src/main/kotlin/app/sterna/core/data/mail/MailboxMapper.kt: " +
                    "lostRoleClaim = isLostRoleClaim(role),",
            ),
            mentions("islostroleclaim(").filterNot { it == declaration },
        )
    }

    /**
     * THE SEAM, and the one mutation everything else here misses. The two halves of this fix are
     */
    @Test fun `the cache row reaches the crossing with its role untouched`() {
        assertEquals(
            "MailRepository.observeMailboxes changed shape. It is the ONE producer of the mailbox " +
                "list the screens see, and nothing executes it: a row whose role is unmarked " +
                "BEFORE toMailbox() carries no lost claim, and a folder that lost its election " +
                "leaves the drawer, both move pickers and the unread view while it is already out " +
                "of search — reachable from nowhere, its mail still on the server.",
            // ONE crossing since #247 collapsed the IMAP and JMAP branches into a single combine.
            // Matched on `.toMailbox()` rather than `it.toMailbox()`: the receiver is now a named
            // `row`, and a needle carrying the old implicit `it` would match nothing and pass this
            // rule over a repository that had stopped calling the mapper at all.
            listOf("val mailbox = row.toMailbox()"),
            codeLines(MAIL_REPOSITORY).filter { ".toMailbox()" in it },
        )
    }

    // ── instrument (copied from SubscribedFoldersSurfaceWiringTest) ──────────────────────────────

    /** Every main-source code line of every module containing [needle], case-insensitively, as
     *  "<repo-relative path>: <trimmed line>", sorted so the answer does not depend on walk order. */
    private fun mentions(needle: String): List<String> = MAIN_SOURCES
        .flatMap { file ->
            val path = file.absolutePath.removePrefix(root.absolutePath + "/")
            codeLines(file).filter { needle in it.lowercase() }.map { "$path: $it" }
        }
        .sorted()

    /** [count] consecutive code lines from the ONE line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val hits = lines.indices.filter { lines[it].startsWith(prefix) }
        val at = hits.singleOrNull()
            ?: error(
                "${hits.size} code lines start with `$prefix` — this lint reads the shipped source " +
                    "and must be taught the new shape rather than left green over something it " +
                    "never read",
            )
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val SUBSCRIBED_FOLDERS_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/SubscribedFolders.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SUBSCRIBED_FOLDERS_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val SUBSCRIBED_FOLDERS: File by lazy { File(root, SUBSCRIBED_FOLDERS_PATH) }

        val MAIL_REPOSITORY: File by lazy {
            File(root, "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        }

        /** EVERY module, not just `:app`: the field is declared in `:core:jmap`, filled in
         *  `:core:data` and read in `:app`, so a sweep of one module proves nothing about it. */
        val MAIN_SOURCES: List<File> by lazy {
            listOf(
                "app/src/main/kotlin",
                "core/jmap/src/main/kotlin",
                "core/imap/src/main/kotlin",
                "core/data/src/main/kotlin",
                "libs/openpgp-api/src/main",
                // The test-app and bench source sets too: they are compiled into a real APK,
                // and "one reader" has to mean one reader there as well.
                "app/src/testApp",
                "app/src/benchShared",
            ).map { File(root, it) }.filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
        }
    }
}
