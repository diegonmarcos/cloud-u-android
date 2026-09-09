package app.sterna.core.imap

import java.io.File

/**
 * Hands a JVM test the body of a function **as it is written in the shipped source** — the
 */
internal object ImapSource {

    private const val DIR = "core/imap/src/main/kotlin/app/sterna/core/imap/"

    private val sources = mutableMapOf<String, String>()

    private fun source(fileName: String): String =
        sources.getOrPut(fileName) { locate("$DIR$fileName.kt").readText() }

    /**
     * The block body of `fun [functionName]` in [fileName] (without its `.kt`), braces included,
     */
    fun functionBody(fileName: String, functionName: String): List<String> {
        val text = source(fileName)
        val declaration = Regex("""\bfun\s+(<[^>()]*>\s*)?$functionName\s*\(""").find(text)
            ?: error("$fileName has no function named '$functionName' — did it get renamed?")
        var i = declaration.range.last
        var parens = 0
        while (i < text.length) {
            when (text[i]) {
                '(' -> parens++
                ')' -> if (--parens == 0) break
            }
            i++
        }
        val open = text.indexOf('{', i).also {
            check(it >= 0) { "'$functionName' in $fileName has no body" }
        }
        var depth = 0
        var j = open
        while (j < text.length) {
            when (text[j]) {
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    return text.substring(open, j + 1).lines().map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
            j++
        }
        error("Unbalanced braces in $fileName.$functionName")
    }

    /**
     * The declaration of `fun [functionName]` in [fileName] — from the `fun` keyword to the closing
     */
    fun functionSignature(fileName: String, functionName: String): List<String> {
        val text = source(fileName)
        val declaration = declarationOf(text, fileName, functionName)
        val close = matchingParen(text, declaration.range.last)
        check(close > 0) { "Unbalanced parentheses in $fileName.$functionName" }
        return text.substring(declaration.range.first, close + 1).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun declarationOf(text: String, fileName: String, functionName: String): MatchResult =
        Regex("""\bfun\s+(<[^>()]*>\s*)?$functionName\s*\(""").find(text)
            ?: error("$fileName has no function named '$functionName' — did it get renamed?")

    /** Index of the `)` that closes the `(` at [open], or -1. */
    private fun matchingParen(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** [relative] resolved from the test's working directory (Gradle runs a module's tests with the
     *  module directory as CWD, so both the repo root and `core/imap` must work). */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/imap/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
