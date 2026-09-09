package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `SyncWindow`'s KDoc once promised that the window "only bounds what stays cached offline"
 */
class ImapStopsAtTheWindowIsDocumentedTest {

    @Test fun `the shipped KDoc carries every sentence the bench paid for`() {
        assertEquals(
            "SyncWindow's KDoc no longer says what the bench measured",
            emptyList<String>(),
            syncWindowDocVerdict(shippedEnumKdoc()),
        )
    }

    @Test fun `dropping or negating a required sentence is refused`() {
        val block = REQUIRED.joinToString(" ")

        // The mutation that survived the keyword version of this file, word for word: it says IMAP,
        // it says ceiling, and it promises the reader can reach past the window all the same.
        val negated = block.replace(
            "On IMAP the window is where the reader's mail ends.",
            "On IMAP it is a hard ceiling on what is KEPT and never on what reading can reach.",
        )
        assertTrue(
            "the negation of the first sentence passes — a keyword rule cannot tell a claim from " +
                "its contrary, which is why the sentences themselves are required",
            syncWindowDocVerdict(negated).any { it.startsWith(MISSING) },
        )

        // And one at a time: no required sentence may be quietly dropped.
        REQUIRED.forEach { sentence ->
            assertTrue(
                "the KDoc rule accepts a block with «$sentence» removed",
                syncWindowDocVerdict(block.replace(sentence, "")).any { it.startsWith(MISSING) },
            )
        }
    }

    @Test fun `an on-demand promise added next to them is refused, however it is worded`() {
        val block = REQUIRED.joinToString(" ")
        listOf(
            // The sentence this volet removed, verbatim.
            "A folder whose newest N messages collapse into few conversations is not truncated " +
                "to those: the scroll mediator pages older mail in on demand (thread-aware fill), " +
                "the window only bounds what stays cached offline.",
            // Lengthened mid-claim — invisible to a `contains` of the sentence above.
            "The scroll mediator pages much older mail in on demand.",
            // Qualified for both protocols, which is the lie with a JMAP badge on it.
            "On IMAP as on JMAP the scroll mediator pages older mail in on demand.",
            // The repo's own name for the object, hyphens and all (MailRepository).
            "On IMAP the scroll-to-load-more mediator pages past it on demand.",
            // The public wording of README/FEATURES, unqualified.
            "Large IMAP folders page in as you scroll, fetching older mail from the server.",
        ).forEach { claim ->
            assertTrue(
                "an added on-demand promise passes: «$claim»",
                syncWindowDocVerdict("$block $claim").any { it.startsWith(SCROLL_ARRIVAL) },
            )
        }
    }

    @Test fun `the same claim is allowed when it is about JMAP alone`() {
        val block = REQUIRED.joinToString(" ") +
            " On JMAP the scroll mediator pages older mail in on demand."
        assertEquals(emptyList<String>(), syncWindowDocVerdict(block))
    }

    // -- the decision, as a function ----------------------------------------------------------

    /**
     * What is wrong with [doc], read as the KDoc block of `SyncWindow` — empty when nothing is.
     */
    private fun syncWindowDocVerdict(doc: String): List<String> {
        val normalized = normalize(doc)
        val out = mutableListOf<String>()
        REQUIRED.filterNot { normalize(it) in normalized }.forEach {
            out += "$MISSING: «$it» — if this sentence was reworded, reword it here too, and " +
                "check the new wording against the bench result quoted at the top of this file."
        }
        out += scrollArrivalClaims(normalized)
        return out
    }

    /**
     * Sentences of an already-normalized [block] that claim mail arrives by scrolling while
     */
    private fun scrollArrivalClaims(block: String): List<String> =
        Regex("""(?<=\.)\s+""").split(block)
            .filter { it.isNotBlank() && ARRIVAL_ON_SCROLL.containsMatchIn(it) }
            .filter { "jmap" !in it || IMAP.containsMatchIn(it) }
            .map { "$SCROLL_ARRIVAL: «$it»" }

    /** One lower-case line: comment markers dropped, every run of whitespace folded to one space. */
    private fun normalize(doc: String): String =
        doc.lines().joinToString(" ") {
            it.trim().removePrefix("/**").removePrefix("*/").removePrefix("*").removeSuffix("*/").trim()
        }.replace(Regex("""\s+"""), " ").lowercase()

    /**
     * The KDoc block on `enum class SyncWindow`, read out of THIS module's source tree.
     */
    private fun shippedEnumKdoc(): String {
        val classes = javaClass.protectionDomain?.codeSource?.location
            ?: error("no code source for ${javaClass.name}; cannot tell which tree was built")
        var dir: File? = File(classes.toURI()).absoluteFile
        while (dir != null && dir.name != "build") dir = dir.parentFile
        val module = dir?.parentFile
            ?: error("$classes is not under a 'build' directory; cannot locate the module source")
        val source = File(module, "src/main/kotlin/app/sterna/core/data/account/SyncWindow.kt")
        check(source.isFile) { "no SyncWindow.kt in the tree that was built ($module) — did it move?" }
        val text = source.readText()
        check("enum class SyncWindow" in text) { "SyncWindow is no longer an enum class — did it move?" }
        val head = text.substringBefore("enum class SyncWindow")
        val open = head.lastIndexOf("/**")
        val close = head.lastIndexOf("*/")
        check(open >= 0 && close > open) {
            "no KDoc block above 'enum class SyncWindow' — the documentation this test defends is gone"
        }
        return head.substring(open, close + 2)
    }

    private companion object {
        const val MISSING = "a required sentence is not in the KDoc"
        const val SCROLL_ARRIVAL = "mail claimed to arrive by scrolling, on IMAP or on no protocol"

        /**
         * THE CONTRACT. These sentences must be in `SyncWindow`'s KDoc, word for word. Three
         */
        val REQUIRED = listOf(
            "On IMAP the window is where the reader's mail ends.",
            "the IMAP Archive stayed flat at 1 000 messages while it was scrolled to the bottom, " +
                "where the JMAP Archive grew from 1 000 to 1 150 under the same gesture",
            "WHY it does not hold on IMAP is NOT established.",
            "its IMAP branch does ask the server for older pages",
            "The unified inbox is paged with no mediator at all ([pagedMailbox]), so it stops at " +
                "the window on both protocols.",
        )

        /**
         * A claim that mail ARRIVES because the reader scrolled — the promise, not the machinery.
         */
        val ARRIVAL_ON_SCROLL = Regex(
            """on\s+demand|(as|when|while)\s+you\s+scroll|\bon\s+scroll\b|pages?\s+past\s+it""",
        )

        /** IMAP named as such; "jmap" is a different word and does not match. */
        val IMAP = Regex("""\bimap\b""")
    }
}
