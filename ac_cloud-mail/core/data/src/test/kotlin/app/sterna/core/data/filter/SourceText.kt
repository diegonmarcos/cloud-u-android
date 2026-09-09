package app.sterna.core.data.filter

import java.io.File

/**
 * Reading the SHIPPED source as text — the instrument of the source lints, in one place.
 */
internal object SourceText {

    /** [text] as trimmed code lines: comment lines dropped whole, trailing comments cut. */
    fun codeLines(text: String): List<String> = text.lines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).takeIf { it.isNotBlank() }
    }

    /** [line] up to its `//` comment, ignoring `//` inside a string literal. */
    fun withoutTrailingComment(line: String): String {
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

    /**
     * The source of [relative], resolved from the test's working directory by walking up (Gradle
     * runs the tests from the module directory, a bare `java` run from the repository root).
     */
    fun read(relative: String): String = locate(relative).readText()

    /**
     * The slice of [source] running from [signature] to the next KDoc block at the same indent —
     */
    fun functionSource(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "the source no longer declares `$signature`" }
        val end = source.indexOf("\n    /**", start).takeIf { it > start } ?: source.lastIndexOf("\n}")
        check(end > start) { "no declaration follows `$signature` — the slice would be the rest of the file" }
        return source.substring(start, end)
    }

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
