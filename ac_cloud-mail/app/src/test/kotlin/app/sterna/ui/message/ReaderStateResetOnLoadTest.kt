package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE RULE, NOT A BEHAVIOUR TEST — and it exists because the behaviour cannot be tested here.
 */
class ReaderStateResetOnLoadTest {

    @Test
    fun `every per-message flow the reader holds is rewritten by load, before it suspends`() {
        val source = viewModelSource()
        val declared = DECLARATION.findAll(source).map { it.groupValues[1] }.toList()
        assertTrue(
            "no MutableStateFlow found in MessageViewModel.kt — did the file move?",
            declared.size >= 15,
        )

        val prologue = loadPrologue(source)
        val unwritten = declared.filterNot { name -> "$name.value" in prologue }

        assertEquals(
            "state left over from the previous message in the pager (not reset in load()'s " +
                "prologue, i.e. still showing the previous message's answer while the new one loads)",
            emptyList<String>(),
            unwritten,
        )
    }

    /**
     * The unsubscribe state, again, in the branch that runs when the message does NOT open.
     */
    @Test
    fun `a failed open clears the unsubscribe the reader could otherwise still press`() {
        val catch = loadCatchBlock(viewModelSource())

        listOf(
            "_unsubscribe.value = null",
            "_unsubscribeState.value = UnsubscribeState.Idle",
            "_unsubscribeConfirm.value = null",
        ).forEach { line ->
            assertTrue(
                "load()'s catch branch does not clear the unsubscribe: `$line` is missing",
                line in catch,
            )
        }
    }

    /** The text of `fun load(…)` up to `viewModelScope.launch` — everything that runs at once. */
    private fun loadPrologue(source: String): String {
        val body = loadBody(source)
        val launch = body.indexOf("viewModelScope.launch")
        assertTrue("load() no longer launches a coroutine — this test's shape is wrong", launch > 0)
        return body.substring(0, launch)
    }

    /** The text of load()'s `catch` branch, from the catch to the end of the function. */
    private fun loadCatchBlock(source: String): String {
        val body = loadBody(source)
        val catch = body.indexOf("} catch (t: Throwable) {")
        assertTrue("load() has no catch branch any more", catch > 0)
        return body.substring(catch)
    }

    /** The text of `fun load(…)`, up to the next declaration at class level. */
    private fun loadBody(source: String): String {
        val start = source.indexOf("fun load(")
        assertTrue("MessageViewModel has no load() any more", start > 0)
        val end = NEXT_MEMBER.find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    private companion object {
        val DECLARATION = Regex("""private val (_\w+)\s*=\s*MutableStateFlow""")

        /** The next member declared at class indentation, which is where load() ends. */
        val NEXT_MEMBER = Regex("""\n {4}(private )?(suspend )?fun \w""")

        val source: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt") }
                .firstOrNull { it.isFile }
                ?: error("cannot locate MessageViewModel.kt from ${File("").absolutePath}")
        }
    }

    private fun viewModelSource(): String = source.readText()
}
