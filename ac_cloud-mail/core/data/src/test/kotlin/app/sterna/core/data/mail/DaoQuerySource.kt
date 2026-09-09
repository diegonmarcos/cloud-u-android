package app.sterna.core.data.mail

import java.io.File

/**
 * Hands a JVM test the SQL of a Room `@Query` **as it is written in the shipped DAO**.
 */
internal object DaoQuerySource {
    private const val DAO_DIR = "core/data/src/main/kotlin/app/sterna/core/data/db/"
    private const val MAIL_DIR = "core/data/src/main/kotlin/app/sterna/core/data/mail/"

    private val sources = mutableMapOf<String, String>()

    private fun daoSource(daoName: String): String =
        sources.getOrPut(daoName) { locate("$DAO_DIR$daoName.kt").readText() }

    /**
     * One statement a DAO function issues: which `@Query` function carried it, its [sql], and
     */
    data class DaoStatement(
        val function: String,
        val sql: String,
        val guarded: Boolean,
        val fallback: Boolean = false,
    )

    /**
     * What running `EmailDao.[functionName]` does, in the shape a replay has to honour: the [main]
     */
    data class DaoPath(val main: List<DaoStatement>, val fallback: List<DaoStatement>, val atomic: Boolean)

    /**
     * [DaoPath] for `EmailDao.[functionName]`: its own `@Query` when it has one, otherwise the
     */
    fun emailDaoPath(functionName: String): DaoPath = emailDaoPath(functionName, mutableSetOf())

    private fun emailDaoPath(functionName: String, following: MutableSet<String>): DaoPath {
        queryOrNull("EmailDao", functionName)?.let {
            return DaoPath(listOf(DaoStatement(functionName, it, guarded = false)), emptyList(), atomic = false)
        }
        following += functionName
        val body = daoFunctionBody("EmailDao", functionName)
        val main = mutableListOf<DaoStatement>()
        val fallback = mutableListOf<DaoStatement>()
        var atomic = isTransactional("EmailDao", functionName)
        var depth = 0
        var catchDepth = -1
        body.lines().forEach { line ->
            val inCatch = catchDepth >= 0
            val into = if (inCatch) fallback else main
            Regex("""(\w+)\(""").findAll(line).forEach { match ->
                val called = match.groupValues[1]
                if (called in following) return@forEach
                val sql = queryOrNull("EmailDao", called)
                if (sql != null) {
                    into += DaoStatement(called, sql, guarded = "runCatching" in line, fallback = inCatch)
                    return@forEach
                }
                if (!hasBody("EmailDao", called)) return@forEach
                val composed = emailDaoPath(called, following)
                check(composed.fallback.isEmpty()) {
                    "EmailDao.$called carries a fallback of its own; this replay follows one level " +
                        "of composition and would run it in the wrong place"
                }
                into += composed.main.map {
                    it.copy(guarded = it.guarded || "runCatching" in line, fallback = inCatch)
                }
                if (!inCatch && composed.atomic) atomic = true
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
            when {
                "catch (" in line -> catchDepth = depth
                catchDepth >= 0 && depth < catchDepth -> catchDepth = -1
            }
        }
        following -= functionName
        return DaoPath(main, fallback, atomic)
    }

    /** Every statement `EmailDao.[functionName]` can issue, nominal path and fallback alike — for
     *  the rules that ask WHAT a path touches rather than in which order or under which guard. */
    fun emailDaoStatements(functionName: String): List<DaoStatement> =
        emailDaoPath(functionName).let { it.main + it.fallback }

    /**
     * Where `fun [functionName]`'s parameter list opens in [source], type parameters and all.
     */
    private fun declarationOf(source: String, functionName: String): MatchResult? =
        Regex("""\bfun\s+(<[^>()]*>\s*)?$functionName\s*\(""").find(source)

    /** Whether [daoName] declares a `fun [functionName]` with a block body — a composed path a
     *  replay can follow, as opposed to an abstract `@Query` or a function that does not exist. */
    private fun hasBody(daoName: String, functionName: String): Boolean {
        val source = daoSource(daoName)
        val fn = declarationOf(source, functionName) ?: return false
        return bodyBrace(source, fn.range.last) >= 0
    }

    /**
     * Whether `[daoName].[functionName]` carries `@Transaction` — a replay of a composed path has
     */
    fun isTransactional(daoName: String, functionName: String): Boolean {
        val source = daoSource(daoName)
        val fn = declarationOf(source, functionName)
            ?: error("$daoName has no function named '$functionName' — did it get renamed?")
        return source.substring(0, fn.range.first).lines().dropLast(1).asReversed()
            .map { it.trim() }
            .takeWhile { it.startsWith("@") }
            .any { it == "@Transaction" }
    }

    /**
     * The Kotlin body of `fun [functionName]` in a file of the `mail` package ([fileName] without
     */
    fun mailFunctionBody(fileName: String, functionName: String): String =
        functionBody(
            sources.getOrPut("mail/$fileName") { locate("$MAIL_DIR$fileName.kt").readText() },
            fileName,
            functionName,
        )

    /**
     * The whole source text of a file of the `mail` package ([fileName] without its `.kt`) — for
     */
    fun mailSource(fileName: String): String =
        sources.getOrPut("mail/$fileName") { locate("$MAIL_DIR$fileName.kt").readText() }

    /**
     * The SQL of the `@Query` annotating `fun [functionName]` in `EmailDao`, with Room's named
     * parameters left in place (`:accountId`, …) — [bindOrder] turns them into positional `?`.
     */
    fun emailDaoQuery(functionName: String): String = daoQuery("EmailDao", functionName)

    /**
     * The Kotlin body of `fun [functionName]` in [daoName], braces included.
     */
    fun daoFunctionBody(daoName: String, functionName: String): String =
        functionBody(daoSource(daoName), daoName, functionName)

    private fun functionBody(source: String, owner: String, functionName: String): String {
        val fn = declarationOf(source, functionName)
            ?: error("$owner has no function named '$functionName' — did it get renamed?")
        // The brace must be this signature's own, right after its closing ')': an abstract DAO
        // function has none, and taking "the next '{' in the file" would silently hand back some
        // later function's body.
        val open = bodyBrace(source, fn.range.last)
        check(open >= 0) {
            "'$functionName' in $owner has no body — is it still the function that composes " +
                "its statements, or has it gone back to a single abstract @Query?"
        }
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in $owner.$functionName")
    }

    /** [emailDaoQuery] for any DAO of the `db` package, named without its `.kt` ([daoName]). */
    fun daoQuery(daoName: String, functionName: String): String =
        queryOrNull(daoName, functionName)
            ?: error("'$functionName' in $daoName is not annotated with @Query — did it get renamed?")

    /**
     * The SQL of `[daoName].[functionName]`'s `@Query`, or null when that function does not exist
     */
    fun queryOrNull(daoName: String, functionName: String): String? {
        val source = daoSource(daoName)
        val fn = declarationOf(source, functionName) ?: return null
        val head = source.substring(0, fn.range.first)
        val annotation = head.lastIndexOf("@Query(")
        if (annotation < 0 || head.substring(annotation).contains(DECLARATION)) return null
        val sql = annotationSql(daoName, head.substring(annotation + "@Query(".length), functionName)
        INTERPOLATION.find(sql)?.let {
            error(
                "@Query on '$functionName' in $daoName carries a Kotlin interpolation, '${it.value}' — " +
                    "this reads the annotation as TEXT, so the constant is never substituted. SQLite " +
                    "does not reject what is left either: it takes '\$NAME' for a named parameter, " +
                    "leaves it unbound, and reads NULL — a 'LIMIT \$PAGE' would silently become no " +
                    "limit at all and the test would pass on a query the app never runs. Inline the " +
                    "value in the annotation, or teach this to substitute it.",
            )
        }
        return sql
    }

    /**
     * A Kotlin string template surviving in an extracted statement — see [queryOrNull]. Only
     */
    private val INTERPOLATION = Regex("""\$\{?[A-Za-z_]""")

    /** A function declaration — what tells an annotation apart from the one before it. */
    private val DECLARATION = Regex("""\bfun\s+\w+\s*\(""")

    /**
     * The same query with Room's named parameters replaced by positional `?`, plus the order the
     */
    fun bindOrder(sql: String, listParams: Map<String, Int> = emptyMap()): Pair<String, List<String>> {
        val names = Regex(":([A-Za-z_][A-Za-z0-9_]*)").findAll(sql).map { it.groupValues[1] }.toList()
        var out = sql
        val order = mutableListOf<String>()
        names.forEach { name ->
            val count = listParams[name] ?: 1
            order += List(count) { name }
        }
        // Longest names first, so ':threadKey' can't be eaten by a ':thread' prefix.
        names.distinct().sortedByDescending { it.length }.forEach { name ->
            val count = listParams[name] ?: 1
            out = out.replace(":$name", List(count) { "?" }.joinToString(","))
        }
        return out to order
    }

    /**
     * Index of the `{` opening the body of the function whose parameter list starts at [paren],
     */
    private fun bodyBrace(source: String, paren: Int): Int {
        var depth = 0
        var i = paren
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return braceOnLineOf(source, i + 1)
            }
            i++
        }
        return -1
    }

    /** The block-body `{` on the line starting at [from], or -1 — see [bodyBrace]. */
    private fun braceOnLineOf(source: String, from: Int): Int {
        val eol = source.indexOf('\n', from).let { if (it < 0) source.length else it }
        val brace = source.substring(from, eol).indexOf('{')
        if (brace < 0) return -1
        val between = source.substring(from, from + brace).trim()
        if ('=' in between) return -1
        return if (between.isEmpty() || between.startsWith(":")) from + brace else -1
    }

    /**
     * The statement an `@Query(…)` argument spells out: [text] is everything after the opening
     */
    private fun annotationSql(daoName: String, text: String, functionName: String): String {
        val out = StringBuilder()
        var pieces = 0
        var i = 0
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> { i = readLiteral(text, i, out); pieces++ }
                c == '(' -> { depth++; i++ }
                c == ')' -> { if (depth == 0) break; depth--; i++ }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    out.append(constantText(daoName, text.substring(start, i), functionName))
                    pieces++
                }
                else -> i++
            }
        }
        check(pieces > 0) { "@Query on '$functionName' holds no string literal" }
        return out.toString()
    }

    /**
     * The text of `const val [name]` in [daoName] — string literals and other constants of the
     */
    private fun constantText(daoName: String, name: String, functionName: String): String {
        val source = daoSource(daoName)
        val declaration = Regex("""\bconst val $name\b[^=\n]*=""").find(source)
            ?: error(
                "@Query on '$functionName' in $daoName concatenates '$name', which is not a " +
                    "'const val' of that file — this reads the annotation as TEXT, so it cannot " +
                    "resolve a constant declared anywhere else.",
            )
        val out = StringBuilder()
        var i = declaration.range.last + 1
        while (true) {
            while (i < source.length && source[i].isWhitespace()) i++
            check(i < source.length) { "'const val $name' in $daoName has no value" }
            val c = source[i]
            when {
                c == '"' -> i = readLiteral(source, i, out)
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    out.append(constantText(daoName, source.substring(start, i), functionName))
                }
                else -> error(
                    "'const val $name' in $daoName is not written as string literals and " +
                        "constants joined by '+'. Teach this to resolve whatever it is now, " +
                        "rather than letting a test execute a truncated statement.",
                )
            }
            var next = i
            while (next < source.length && source[next].isWhitespace()) next++
            if (next >= source.length || source[next] != '+') return out.toString()
            i = next + 1
        }
    }

    /** Append the double-quoted literal starting at [at] in [text] to [into]; returns the index
     *  just past its closing quote. */
    private fun readLiteral(text: String, at: Int, into: StringBuilder): Int {
        var i = at + 1
        while (i < text.length && text[i] != '"') {
            if (text[i] == '\\' && i + 1 < text.length) i++
            into.append(text[i]); i++
        }
        return i + 1
    }

    /**
     * [relative] resolved from the test's working directory (Gradle runs a module's tests with the
     */
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
