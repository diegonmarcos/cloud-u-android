package app.sterna.core.data.mail

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class DiscoveryWiringTest {

    @Test fun `autodiscovery asks each host through the probe the tests run`() {
        val body = discoverJmapServerBody()
        assertTrue(
            "MailRepository.discoverJmapServer must ask each candidate through probeHost { … }, " +
                "which is where a session, a 401/403 and a cancellation are turned into a " +
                "HostProbe — and the only version of that translation any test executes. Spelled " +
                "out at this call site instead, it is a decision nothing can hold: replacing it " +
                "with a constant leaves the whole suite green. Body was:\n$body",
            PROBE_WIRING.containsMatchIn(body),
        )
    }

    @Test fun `autodiscovery takes the budget the app ships with, and does not restate it`() {
        val body = discoverJmapServerBody()
        assertTrue(
            "MailRepository.discoverJmapServer must call discoverJmapAmong(hosts) with NO budget " +
                "argument: the shipped value then lives once, in JMAP_DISCOVERY_BUDGET_MS, where a " +
                "test pins it. Restating it here makes a second copy no test can reach. Body was:\n$body",
            BUDGET_LESS_CALL.containsMatchIn(body),
        )
    }

    /**
     * Whole line, never a fragment: `contains("BearerAuth(")` is blind to everything that
     * LENGTHENS the line, and this line's mutation shortens AND lengthens it.
     */
    @Test fun `autodiscovery proves a token as a Bearer, and only falls back on Basic without one`() {
        val body = discoverJmapServerBody()
        assertTrue(
            "MailRepository.discoverJmapServer must build its auth as:\n  $AUTH_LINE\n" +
                "Rewritten to Basic only, the whole suite stays green and #55 comes back in " +
                "silence: the OAuth cascade then probes all four candidates with an EMPTY " +
                "password, every one answers 401, and oauthServerToStore falls back on the " +
                "document's host — one mailbox added by password and by OAuth is two accounts " +
                "again, with nothing to see anywhere. Body was:\n$body",
            body.lines().map { it.trim() }.contains(AUTH_LINE),
        )
    }

    /**
     * Whole line, never a fragment — and the reason it is pinned at all is #188.
     */
    @Test fun `autodiscovery keeps the candidate order, so rank 0 is the domain she typed`() {
        val body = discoverJmapServerBody()
        assertTrue(
            "MailRepository.discoverJmapServer must build its candidates as:\n  $HOSTS_LINE\n" +
                "Rank 0 must stay the domain the reader typed: it is the only rank whose 401/403 " +
                "is her password being refused, every other rank being a guess (#188). Reordered " +
                "or reversed here, that property is silently false and no test in the tree can " +
                "see it. Body was:\n$body",
            body.lines().map { it.trim() }.contains(HOSTS_LINE),
        )
    }

    private fun discoverJmapServerBody(): String {
        val text = MAIL_REPOSITORY.readText()
        val start = text.indexOf(DECLARATION)
        check(start >= 0) {
            "no `$DECLARATION` in MailRepository.kt — autodiscovery was renamed or moved, and the " +
                "rules in this file are now guarding nothing. Follow it, or delete this file."
        }
        // To the next top-level declaration of the class: enough to hold the function, and it does
        // not need brace matching to be right, only to stop somewhere after the call.
        val end = text.indexOf("\n    /**", start).takeIf { it > start } ?: text.length
        return text.substring(start, end)
    }

    private companion object {
        const val DECLARATION = "suspend fun discoverJmapServer("

        /**
         * The one line the OAuth half of #55 rests on, whole. A token is the ONLY credential the
         * OAuth cascade has: `addOAuthAccount` calls this with `password = ""`.
         */
        const val AUTH_LINE =
            """val auth = if (token != null) BearerAuth(token) else BasicAuth(email.trim(), password)"""

        /**
         * The candidate list, whole. Its ORDER is load-bearing since #188: rank 0 is the typed
         * domain, and only rank 0 can make the verdict BadCredentials.
         */
        const val HOSTS_LINE = """val hosts = Jmap.autodiscoverHosts(email)"""

        /** The probe, as a whole call: `probeHost { … fetchSession … }`. */
        val PROBE_WIRING = Regex("""probeHost \{[^}]*client\.fetchSession\(""")

        /** `discoverJmapAmong(hosts)` with exactly one argument before the trailing lambda. */
        val BUDGET_LESS_CALL = Regex("""discoverJmapAmong\(hosts\)\s*\{""")

        /** The shipped file, in the tree that was actually built. */
        val MAIL_REPOSITORY: File by lazy {
            val classes = DiscoveryWiringTest::class.java.protectionDomain?.codeSource?.location
                ?: error("no code source; cannot tell which tree was built")
            var dir: File? = File(classes.toURI()).absoluteFile
            while (dir != null && dir.name != "build") dir = dir.parentFile
            val module = dir?.parentFile
                ?: error("$classes is not under a 'build' directory; cannot locate the module source")
            File(module, "src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").also {
                check(it.isFile) { "no MailRepository.kt in the tree that was built ($module)" }
            }
        }
    }
}
