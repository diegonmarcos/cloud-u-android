package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `SettingsComponents.kt` and `SettingsScreen.kt` as
 */
class SecretFieldRevealWiringTest {

    @Test fun `the reveal state is remembered under a key that falls on both sides of empty`() {
        assertEquals(
            "SettingTextField must hold its reveal state as '$EXPECTED_REMEMBER' — the whole line. " +
                "The key is the fix, not decoration: without it the state survives the field being " +
                "emptied, and the next secret typed into it is shown in clear. rememberSaveable is " +
                "wrong here too — it would bring the revealed state back across a rotation.",
            listOf(EXPECTED_REMEMBER),
            body().filter { "remember" in it },
        )
    }

    @Test fun `the mask is on for a secret field unless the reveal was asked for`() {
        assertEquals(
            "SettingTextField must compute '$EXPECTED_MASKED' — the whole line. The mask depends " +
                "on nothing but the flag and the reveal: a secret is under the dots until someone " +
                "asks to see it, and clearing the field puts it back under.",
            listOf(EXPECTED_MASKED),
            body().filter { it.startsWith("val masked") },
        )
        assertEquals(
            "the field must apply that mask as '$EXPECTED_TRANSFORMATION' — the whole line. " +
                "Anything else here (an extra condition, a swapped branch) silently prints the " +
                "secret on screen.",
            listOf(EXPECTED_TRANSFORMATION),
            body().filter { it.startsWith("visualTransformation") },
        )
    }

    @Test fun `the trailing icon is the executed decision, and is absent when it says no`() {
        val expected = listOf(
            "trailingIcon = if (revealIconOffered(isPassword, value)) {",
            "{",
            "IconButton(onClick = { revealRequested = !revealRequested }) {",
            "Icon(",
            "if (revealRequested) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,",
            "contentDescription = stringResource(",
            "if (revealRequested) R.string.connect_password_hide else R.string.connect_password_show,",
            "),",
            ")",
            "}",
            "}",
            "} else {",
            "null",
            "},",
        )
        val lines = body()
        val at = lines.indexOfFirst { it.startsWith("trailingIcon") }
        assertEquals(
            "SettingTextField must have exactly one trailingIcon argument, and it must ASK " +
                "revealIconOffered — the arguments included. Passing anything but (isPassword, " +
                "value) is #118 with a function call on top. Lines found:\n" +
                lines.filter { it.startsWith("trailingIcon") }.joinToString("\n"),
            1,
            lines.count { it.startsWith("trailingIcon") },
        )
        val found = lines.subList(at, minOf(at + expected.size, lines.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of the trailing icon: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "the trailing icon is pinned WHOLE, line by line and in order: the guarded 'if', the " +
                "toggle, the two icons and the two contentDescriptions that must agree with them, " +
                "and the 'null' branch — the icon must be ABSENT on an empty field, not greyed out " +
                "and not transparent. Nothing in this repo executes these lines. Mismatches:\n" +
                mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /** The negative screen — `contains`, deliberately: it looks for what must be absent. */
    @Test fun `nothing in the field dims or persists the reveal instead of dropping it`() {
        val offenders = body().filter { line ->
            BANNED.any { it in line }
        }
        assertEquals(
            "no line of SettingTextField may mention ${BANNED.joinToString(", ")}: an icon kept but " +
                "disabled or faded is the WYSIWYG lie #118 reported (a control that answers " +
                "nothing), and a saveable reveal state comes back revealed after a rotation. " +
                "Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /** The insertion screen: every rule above filters, so none of them can see an ADDED line. */
    @Test fun `nothing else in the field renders the value a second time`() {
        val expected = listOf(
            EXPECTED_REMEMBER,
            "value = value,",
            "trailingIcon = if (revealIconOffered(isPassword, value)) {",
        )
        assertEquals(
            "these are the ONLY lines of SettingTextField allowed to mention 'value', and they are " +
                "compared whole. An extra one that renders it — 'supportingText = { Text(value) },' " +
                "a placeholder, a caption — prints the secret in clear under its own masked field, " +
                "permanently, and every other rule in this file filters past it without seeing it.",
            expected,
            body().filter { Regex("""\bvalue\b""").containsMatchIn(it) },
        )
    }

    // -- the one call site that types a secret -------------------------------------------------------

    /**
     * `SettingsComponents.kt` decides how a secret field behaves; whether the account screen's token
     * field IS one is a single argument on the far side of the repo, and nothing was watching it.
     */
    @Test fun `the account screen's secret field declares itself a secret`() {
        val block = passwordFieldBlock()
        assertEquals(
            "the password / API-token field of the account screen must be called with " +
                "'$EXPECTED_IS_PASSWORD' — the whole line. Without that flag the field is an " +
                "ordinary text box: the token is typed and left in permanent clear on the settings " +
                "screen, readable over a shoulder and in every screenshot, and there is no eye " +
                "because there is nothing to unmask. Lines found in the block:\n" +
                block.joinToString("\n"),
            listOf(EXPECTED_IS_PASSWORD),
            block.filter { "isPassword" in it },
        )
        assertEquals(
            "'isPassword =' must appear exactly once in the whole of SettingsScreen.kt, on that " +
                "field. A second one is either a secret field this lint does not know about, or " +
                "the flag moved onto a field that is not a secret.",
            listOf(EXPECTED_IS_PASSWORD),
            screenLines().filter { "isPassword =" in it },
        )
    }

    // -- reading the sources -----------------------------------------------------------------------

    /**
     * The body of `if (account.authType != AuthType.OAUTH) {` on the account screen, closed by
     */
    private fun passwordFieldBlock(): List<String> {
        val lines = screenLines()
        val opener = "if (account.authType != AuthType.OAUTH) {"
        val at = lines.indexOfFirst { it == opener }
        check(at >= 0) {
            "no '$opener' in SettingsScreen.kt — the secret field moved, and this lint must be " +
                "taught where it went rather than left green over a block it never read"
        }
        var depth = 1
        val result = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) {
                check(result.any { it == "SettingTextField(" }) {
                    "the '$opener' block no longer calls SettingTextField — the secret is typed " +
                        "into something else now, and nothing here knows how that something masks it"
                }
                return result
            }
            result += line
        }
        error("'$opener' is never closed in SettingsScreen.kt")
    }

    /**
     * The body of `fun SettingTextField(`, and only it: the block is closed by counting braces, so a
     * rule can never be satisfied by a line belonging to the next composable in the file.
     */
    private fun body(): List<String> {
        val lines = codeLines()
        val at = lines.indexOfFirst { it == "fun SettingTextField(" }
        check(at >= 0) {
            "no 'fun SettingTextField(' in SettingsComponents.kt — the composable was renamed or " +
                "reshaped, and this lint must be taught the new shape rather than left green over " +
                "a function it never read"
        }
        val open = at + lines.drop(at).indexOfFirst { it == ") {" }
        check(open > at) { "no signature end ') {' after 'fun SettingTextField(' " }
        var depth = 1
        val result = mutableListOf<String>()
        for (line in lines.drop(open + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return result
            result += line
        }
        error("'fun SettingTextField(' is never closed in SettingsComponents.kt")
    }

    /** The file's lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(): List<String> = codeLinesOf(SETTINGS_COMPONENTS)

    private fun screenLines(): List<String> = codeLinesOf(SETTINGS_SCREEN)

    private fun codeLinesOf(file: File): List<String> =
        file.readLines().map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }

    private companion object {
        const val EXPECTED_REMEMBER =
            "var revealRequested by remember(value.isEmpty()) { mutableStateOf(false) }"
        const val EXPECTED_MASKED = "val masked = isPassword && !revealRequested"
        const val EXPECTED_TRANSFORMATION =
            "visualTransformation = if (masked) PasswordVisualTransformation() " +
                "else VisualTransformation.None,"
        const val EXPECTED_IS_PASSWORD = "isPassword = true,"
        val BANNED = listOf("rememberSaveable", "alpha", "enabled =")

        private const val SETTINGS_COMPONENTS_PATH =
            "app/src/main/kotlin/app/sterna/ui/settings/SettingsComponents.kt"
        private const val SETTINGS_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        val ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_COMPONENTS_PATH).isFile }
                ?: error(
                    "cannot locate $SETTINGS_COMPONENTS_PATH from ${File("").absolutePath} — this " +
                        "lint reads the sources as text and needs a working directory inside the " +
                        "checkout",
                )
        }

        val SETTINGS_COMPONENTS: File by lazy { File(ROOT, SETTINGS_COMPONENTS_PATH) }
        val SETTINGS_SCREEN: File by lazy { File(ROOT, SETTINGS_SCREEN_PATH) }
    }
}
