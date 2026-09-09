package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, and it has to be: [Notifications] is an Android object — every function here
 */
class NoBannerWithoutItsChannelWiringTest {

    @Test fun `every function that posts a new-mail banner ensures its channel first`() {
        val sites = postingSites()
        assertEquals(
            "a function of Notifications.kt builds a NotificationCompat.Builder on CHANNEL_MAIL " +
                "without ensuring the channel first. On an install whose push was never armed in " +
                "direct mode nothing else creates 'new_mail', and Android throws the banner away " +
                "in silence. The first code line of each posting site must be " +
                "'$ENSURE' — exactly as notifySendFailed already does.",
            sites.map { it.name to ENSURE },
            sites.map { it.name to it.firstCodeLine },
        )
    }

    /**
     * The banner path must ensure the MAIL channel ALONE. `ensureChannels` also creates
     */
    @Test fun `the mail channel is created alone, at the importance a banner needs`() {
        assertEquals(
            "ensureMailChannel is the body every banner runs before posting, and no test reaches " +
                "past its NAME. Pinned whole: the channel id (CHANNEL_SERVICE here would leave " +
                "'new_mail' uncreated and the original silence intact), the importance " +
                "(IMPORTANCE_HIGH — anything lower ships banners with no heads-up and no sound, " +
                "the reported symptom minus its logcat signature), and the absence of any second " +
                "channel.",
            listOf(
                "context.getSystemService(NotificationManager::class.java).createNotificationChannel(",
                "NotificationChannel(",
                "CHANNEL_MAIL,",
                "context.getString(R.string.notif_channel_mail),",
                "NotificationManager.IMPORTANCE_HIGH,",
                "),",
                ")",
            ),
            memberBody("ensureMailChannel"),
        )
    }

    @Test fun `the scan still sees the three posting sites this lint was written for`() {
        assertEquals(
            "the source scan no longer finds the known posting sites of Notifications.kt — a " +
                "rename or a refactor moved them, and the rule above is now comparing two empty " +
                "lists. Fix the scan (or this list) before trusting it again.",
            listOf("notifySendFailed", "notifyNewMail", "notifyGroupSummary"),
            postingSites().map { it.name }.filter { it in KNOWN_SITES },
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    private data class PostingSite(val name: String, val firstCodeLine: String)

    /** The code lines of `fun [name]`'s body in [NOTIFICATIONS]. */
    private fun memberBody(name: String): List<String> = membersOf(NOTIFICATIONS)
        .firstOrNull { member -> member.any { FUN_DECL.find(it)?.groupValues?.get(1) == name } }
        ?.let { bodyOf(it) }
        ?: error("Notifications.kt has no function named '$name' — did it get renamed?")

    /** Every function of [NOTIFICATIONS] whose body builds a notification on [CHANNEL_MAIL]. */
    private fun postingSites(): List<PostingSite> = membersOf(NOTIFICATIONS).mapNotNull { member ->
        val name = FUN_DECL.find(member.firstOrNull { FUN_DECL.containsMatchIn(it) } ?: "")
            ?.groupValues?.get(1) ?: return@mapNotNull null
        val body = bodyOf(member)
        val posts = body.any { "NotificationCompat.Builder(" in it } && body.any { "CHANNEL_MAIL" in it }
        if (posts) PostingSite(name, body.firstOrNull().orEmpty()) else null
    }

    /**
     * The code lines of a member's body. A block body opens on the first declaration line ending
     */
    private fun bodyOf(member: List<String>): List<String> {
        val decl = member.indexOfFirst { FUN_DECL.containsMatchIn(it) }
        val open = (decl until member.size).firstOrNull { member[it].endsWith("{") }
        if (open != null) return member.drop(open + 1).dropLastWhile { it == "}" }
        return (listOf(member[decl].substringAfter("=").trim()) + member.drop(decl + 1))
            .filter { it.isNotBlank() }
    }

    /**
     * The members of the single `object` in [file], each as its trimmed code lines, comments cut.
     */
    private fun membersOf(file: File): List<List<String>> {
        val lines = file.readLines()
        val starts = lines.indices.filter { MEMBER_START.containsMatchIn(lines[it]) }
        return starts.mapIndexed { i, start ->
            lines.subList(start, starts.getOrElse(i + 1) { lines.size })
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
                    else withoutTrailingComment(trimmed).takeIf { it.isNotBlank() }
                }
        }
    }

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

    private companion object {
        const val ENSURE = "ensureMailChannel(context)"
        val KNOWN_SITES = listOf("notifySendFailed", "notifyNewMail", "notifyGroupSummary")

        /** A member of the object: four spaces, then something that is not a continuation. */
        val MEMBER_START = Regex("""^ {4}[^\s)}]""")
        val FUN_DECL = Regex("""^(?:private |internal |public )?(?:inline )?fun ([A-Za-z_]\w*)""")

        const val NOTIFICATIONS_PATH = "app/src/main/kotlin/app/sterna/push/Notifications.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, NOTIFICATIONS_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val NOTIFICATIONS: File by lazy { File(root, NOTIFICATIONS_PATH) }
    }
}
