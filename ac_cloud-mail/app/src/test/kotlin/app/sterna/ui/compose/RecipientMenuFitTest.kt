package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — said first, as `SenderRuleDialogFitTest` says it, because the
 */
class RecipientMenuFitTest {

    @Test fun `the recipient field, its measurements and its keyed remember are all still found`() {
        // The rule that keeps the others honest: every rule below reads one of these, and a rule
        // that quietly matches nothing is worse than no rule.
        val body = fieldBody()
        assertTrue(
            "RecipientChipsField no longer calls $CALL — either the #143 fix was removed, or the " +
                "computation moved somewhere this file cannot read, in which case this lint must " +
                "be taught the new shape rather than left green",
            CALL in body,
        )
        assertTrue("RecipientChipsField no longer composes a Popup( — re-read it", "Popup(" in body)
        assertTrue(
            "RecipientChipsField no longer measures itself with .onGloballyPositioned — rules A " +
                "and the window height below read that block",
            POSITIONED in body,
        )
        val keyed = keyedRemember(body)
        assertTrue(
            "the $CALL call must be bound to a name (`val <name> = remember(…) { … }`); the cap " +
                "rule identifies the menu's height by that name",
            keyed.name.isNotEmpty(),
        )
    }

    /**
     * Mutation M1, the one that puts #143 straight back: hand `heightIn` the flat 256.dp again.
     */
    @Test fun `the suggestion menu is capped by the computed height, not by a constant`() {
        val body = fieldBody()
        val name = keyedRemember(body).name
        val caps = HEIGHT_IN.findAll(body).map { it.groupValues[1].trim() }.toList()
        assertEquals(
            "exactly one `heightIn(max = …)` in RecipientChipsField must receive '$name', the " +
                "height computed for the room left under the field with the keyboard up. Found " +
                "these caps instead: $caps. A literal here is #143 itself: on a 432 px tall screen " +
                "a 256 dp menu covers the keyboard and the address can no longer be narrowed down.",
            1,
            caps.count { it == name },
        )
        assertEquals(
            "no flat 256.dp may remain in RecipientChipsField: the menu's own maximum belongs to " +
                "SuggestionMenuFit.kt, where the room actually left is what bounds it. Lines:",
            emptyList<String>(),
            body.lines().map { it.trim() }.filter { "256.dp" in it },
        )
    }

    /**
     * Mutation A: the field measures itself, and this is the block where a wrong number is born
     */
    @Test fun `the field measures its own bottom edge and the root's height, whole`() {
        val block = positionedBlock(fieldBody())
        val assignments = assignments(block)
        listOf(FIELD_BOTTOM_RHS, WINDOW_HEIGHT_RHS).forEach { rhs ->
            assertEquals(
                "exactly one assignment in RecipientChipsField's onGloballyPositioned must read " +
                    "'$rhs', whole. Found instead: $assignments. Anything shorter is measured " +
                    "against a field of zero height (the menu covers the first row of keys again) " +
                    "or against a width, and nothing else in this repo would see it. Block was:\n" +
                    block,
                1,
                assignments.values.count { it == rhs },
            )
        }
    }

    /**
     * Mutation B, half of it: the keyboard is read, once, before the popup — and halved. This
     */
    @Test fun `the keyboard height is read whole, once, in the field, before the popup`() {
        val body = fieldBody()
        val binding = imeBinding(body)
        assertTrue("read as: ${binding.line}", binding.name.isNotEmpty())
        val reads = body.indicesOf(IME_READ)
        assertEquals(
            "RecipientChipsField must read '$IME_READ' exactly once — found ${reads.size}. Two " +
                "reads mean one of them is somewhere this rule did not look; none means the " +
                "keyboard is not measured at all and the cap is computed as if it were down.",
            1,
            reads.size,
        )
        assertTrue(
            "'$IME_READ' must be read BEFORE the Popup( is composed (found at ${reads.single()}, " +
                "popup at ${body.indexOf("Popup(")}). Inside the popup it reads another window's " +
                "insets, which never carry this keyboard.",
            reads.single() < body.indexOf("Popup("),
        )
    }

    /**
     * Mutation A and B together, at the seam neither the pure function nor the lines above can
     */
    @Test fun `each measurement reaches the parameter it belongs to`() {
        val body = fieldBody()
        val block = positionedBlock(body)
        val expected = mapOf(
            "windowHeightPx" to nameHolding(block, WINDOW_HEIGHT_RHS),
            "fieldBottomPx" to nameHolding(block, FIELD_BOTTOM_RHS),
            "imeHeightPx" to imeBinding(body).name,
        )
        val actual = keyedRemember(body).namedArguments
        expected.forEach { (parameter, identifier) ->
            assertEquals(
                "$CALL's '$parameter' must be given '$identifier', the local that holds that " +
                    "measurement. Passing another one compiles, keeps every key a key, and " +
                    "computes the room under the field out of the wrong number. Call site was: " +
                    "$actual",
                identifier,
                actual[parameter],
            )
        }
    }

    /**
     * Mutation M4, the one that is invisible in a diff review: drop the keys.
     */
    @Test fun `every measurement fed to the height computation is a remember key`() {
        val keyed = keyedRemember(fieldBody())
        val opaque = keyed.namedArguments.filterValues { !IDENTIFIER.matches(it) }
        assertEquals(
            "every argument of $CALL must be a bare local, so that this rule can check it is a " +
                "remember key. These are expressions this rule cannot follow: $opaque. Computing " +
                "inside the call hides a quantity from the keys — and would leave this rule with " +
                "nothing to check, reporting success. Put the expression in the local, or teach " +
                "this rule the new shape on purpose.",
            emptyMap<String, String>(),
            opaque,
        )
        val missing = keyed.namedArguments.values.filter { it !in keyed.keys }
        assertEquals(
            "these arguments of $CALL are not keys of the remember that wraps it: $missing " +
                "(keys: ${keyed.keys}). A quantity that moves without being a key is read once " +
                "and never again — the keyboard opens, the menu stays as tall as it was before it, " +
                "and #143 is back with the whole suite green.",
            emptyList<String>(),
            missing,
        )
    }

    /**
     * Mutation C: the menu scrolls, and under a computed cap that is no longer a comfort.
     */
    @Test fun `the menu still scrolls under its cap`() {
        val body = fieldBody()
        val name = keyedRemember(body).name
        val lines = popupContent(body).lines().map { it.trim() }.filter { it.isNotEmpty() }
        // The trailing comma is allowed off the cap line, so that deleting the scroll and moving
        // the comma up — the shortest way to write mutation C — still lands on the message below
        // instead of on "no cap found".
        val cap = lines.indexOfFirst { it.removeSuffix(",") == ".heightIn(max = $name)" }
        assertTrue(
            "the menu's Column must carry '.heightIn(max = $name)' on its own line inside the " +
                "Popup; found none among:\n${lines.joinToString("\n")}",
            cap >= 0,
        )
        assertEquals(
            "the line right after the menu's cap must be exactly '$SCROLL_MODIFIER'. Without it a " +
                "computed cap does not shorten the menu, it TRUNCATES it: four of the six " +
                "suggestions become unreachable, which is worse than the defect being fixed.",
            SCROLL_MODIFIER,
            lines.getOrNull(cap + 1),
        )
    }

    /**
     * The line this whole fix rests on, and it is not in `ComposeScreen.kt`.
     */
    @Test fun `the activity is still told to react to the keyboard`() {
        val lines = MANIFEST.readLines().map { it.trim() }
        assertEquals(
            "AndroidManifest.xml must carry exactly one '$SOFT_INPUT'. The recipient menu's cap " +
                "is computed from WindowInsets.ime, and on API 26-29 (minSdk 26; the #143 " +
                "reporter runs API 27) that inset exists because of this line. Without it the " +
                "keyboard reads as 0 and the menu covers it again. Found: " +
                lines.filter { "windowSoftInputMode" in it },
            1,
            lines.count { it == SOFT_INPUT },
        )
    }

    /**
     * Decision of the fix, at the call site: under one row of room there is NO menu. A cropped menu
     */
    @Test fun `no menu at all is composed when no height came back`() {
        val body = fieldBody()
        val name = keyedRemember(body).name
        val popup = body.indexOf("Popup(")
        val guard = body.lastIndexOf("if (", popup)
        assertTrue("no `if (` guards the Popup in RecipientChipsField", guard in 0 until popup)
        val condition = balanced(body, guard)
        val terms = condition.split("&&").map { it.trim() }
        assertTrue(
            "the Popup must be guarded by '$name != null' as a whole term of its condition — " +
                "found the condition '$condition'. Defaulting the null away (`?: 56.dp`, " +
                "`!= null || …`) draws a menu in a space that holds no whole row.",
            "$name != null" in terms,
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    /** A `remember` with its keys, the name it is bound to, and the arguments of the [CALL] inside
     *  it. Fails loudly at every step: this is what most rules above read. */
    private class Keyed(
        val name: String,
        val keys: List<String>,
        val namedArguments: Map<String, String>,
    )

    private fun keyedRemember(body: String): Keyed {
        val at = body.indexOf(CALL)
        check(at >= 0) { "no '$CALL' in RecipientChipsField" }
        val rememberAt = body.lastIndexOf("remember", at)
        check(rememberAt >= 0) { "no 'remember' before the '$CALL' call" }
        var i = rememberAt + "remember".length
        while (i < body.length && body[i].isWhitespace()) i++
        val keys = if (body[i] == '(') {
            val text = balanced(body, i)
            i = body.indexOf('{', i + text.length)
            text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        check(i in body.indices && body[i] == '{') { "no lambda after the remember at $rememberAt" }
        val lambda = braces(body, i)
        check(CALL in lambda) { "the '$CALL' call is not inside the remember that precedes it" }
        val lineStart = body.lastIndexOf('\n', rememberAt) + 1
        val name = BINDING.find(body.substring(lineStart, rememberAt))?.groupValues?.get(1)
        check(!name.isNullOrEmpty()) {
            "the remember holding the $CALL call is not bound to a `val <name> =` on its own line"
        }
        val callArgs = balanced(lambda, lambda.indexOf(CALL) + CALL.length - 1)
        val parts = splitTopLevel(callArgs)
        val positional = parts.filterNot { '=' in it }
        check(positional.isEmpty()) {
            "$CALL must be called with NAMED arguments — this lint maps each measurement to the " +
                "parameter it must reach, and cannot do it positionally. Found: $positional"
        }
        val named = parts.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        check(named.isNotEmpty()) { "no arguments read out of '$CALL($callArgs)'" }
        return Keyed(name!!, keys, named)
    }

    /** The `val <name> = WindowInsets.ime.getBottom(density)` line, whole, and the name it binds. */
    private class ImeBinding(val name: String, val line: String)

    private fun imeBinding(body: String): ImeBinding {
        val matches = body.lines().map { it.trim() }.mapNotNull { line ->
            IME_BINDING.matchEntire(line)?.let { ImeBinding(it.groupValues[1], line) }
        }
        check(matches.size == 1) {
            "RecipientChipsField must bind the keyboard height with exactly one line matching " +
                "'val <name> = WindowInsets.ime.getBottom(density)', whole — found " +
                "${matches.size}. Lines carrying the read: " +
                body.lines().map { it.trim() }.filter { IME_READ in it }
        }
        return matches.single()
    }

    /** The `.onGloballyPositioned { … }` block of the recipient row. */
    private fun positionedBlock(body: String): String {
        val at = body.indexOf(POSITIONED)
        check(at >= 0) { "no '$POSITIONED' in RecipientChipsField" }
        return braces(body, body.indexOf('{', at))
    }

    /** `name -> right-hand side` for every plain assignment of a block, whole lines. */
    private fun assignments(block: String): Map<String, String> =
        block.lines().map { it.trim() }.mapNotNull { ASSIGNMENT.matchEntire(it) }
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

    /** The single local assigned [rhs] in [block]; fails loudly when it is not exactly one. */
    private fun nameHolding(block: String, rhs: String): String {
        val names = assignments(block).filterValues { it == rhs }.keys
        check(names.size == 1) { "expected exactly one assignment of '$rhs', found $names" }
        return names.single()
    }

    /** The content lambda of the `Popup(…) { … }` call. */
    private fun popupContent(body: String): String {
        val at = body.indexOf("Popup(")
        check(at >= 0) { "no 'Popup(' in RecipientChipsField" }
        var i = body.indexOf('(', at)
        var depth = 0
        do {
            when (body[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        } while (depth > 0 && i < body.length)
        return braces(body, body.indexOf('{', i))
    }

    /** The body of `RecipientChipsField`, comments removed, braces balanced. */
    private fun fieldBody(): String {
        val code = code(COMPOSE_SCREEN)
        val at = code.indexOf(FIELD_FUN)
        check(at >= 0) {
            "no '$FIELD_FUN' in ComposeScreen.kt — the recipient field was renamed or moved out, " +
                "and every rule in this file reads it (ComposeScreenIncognitoTest pins the same " +
                "name for the incognito guarantee)"
        }
        // Past the parameter list — its default values carry braces of their own ({ false }) — to
        // the body's own opening brace.
        var i = code.indexOf('(', at)
        var depth = 0
        do {
            when (code[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        } while (depth > 0 && i < code.length)
        return braces(code, code.indexOf('{', i))
    }

    private fun braces(text: String, open: Int): String {
        check(open >= 0) { "no '{' to balance" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        error("unbalanced braces from $open")
    }

    /** The text inside the parentheses that open at or after [from]. */
    private fun balanced(text: String, from: Int): String {
        val start = text.indexOf('(', from) + 1
        check(start > 0) { "no '(' at or after $from" }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    private fun splitTopLevel(args: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var last = 0
        args.forEachIndexed { i, c ->
            when (c) {
                '(', '{' -> depth++
                ')', '}' -> depth--
                ',' -> if (depth == 0) {
                    out += args.substring(last, i)
                    last = i + 1
                }
            }
        }
        out += args.substring(last)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun String.indicesOf(needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var at = indexOf(needle)
        while (at >= 0) {
            out += at
            at = indexOf(needle, at + 1)
        }
        return out
    }

    /** The file with comment lines and trailing comments removed, so no rule here can be satisfied
     *  — or defeated — by prose. */
    private fun code(file: File): String = file.readLines().joinToString("\n") { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) ""
        else withoutTrailingComment(line)
    }

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
        const val FIELD_FUN = "private fun RecipientChipsField("
        const val CALL = "suggestionMenuMaxHeight("
        const val IME_READ = "WindowInsets.ime.getBottom("
        const val POSITIONED = ".onGloballyPositioned"

        /** The two measurements, whole. Anything else is refused. */
        const val FIELD_BOTTOM_RHS = "(it.positionInRoot().y + it.size.height).roundToInt()"
        const val WINDOW_HEIGHT_RHS = "it.findRootCoordinates().size.height"

        /** The scroll that makes a short menu lose nothing, as the repo's other lints pin theirs. */
        const val SCROLL_MODIFIER = ".verticalScroll(rememberScrollState()),"

        /** The manifest line the IME inset depends on below API 30, whole. */
        const val SOFT_INPUT = "android:windowSoftInputMode=\"adjustResize\""

        val HEIGHT_IN = Regex("""heightIn\(max = ([^)]*)\)""")
        val BINDING = Regex("""val (\w+) =\s*$""")
        val IME_BINDING = Regex("""val (\w+) = WindowInsets\.ime\.getBottom\(density\)""")
        val ASSIGNMENT = Regex("""(\w+) = (.+)""")
        val IDENTIFIER = Regex("""\w+""")

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val COMPOSE_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt")
        }
        val MANIFEST: File by lazy { File(root, "app/src/main/AndroidManifest.xml") }
    }
}
