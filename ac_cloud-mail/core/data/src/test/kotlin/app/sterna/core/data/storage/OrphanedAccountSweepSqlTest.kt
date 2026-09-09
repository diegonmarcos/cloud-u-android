package app.sterna.core.data.storage

import app.sterna.core.data.mail.DaoQuerySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The orphan sweep of [StorageRepository.purgeOrphanedAccounts] (#121), replayed against in-memory
 */
class OrphanedAccountSweepSqlTest {

    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE emails(id TEXT, accountId TEXT, mailboxId TEXT, PRIMARY KEY(accountId, id))")
            st.executeUpdate("CREATE TABLE mailboxes(id TEXT, accountId TEXT, name TEXT, PRIMARY KEY(accountId, id))")
            st.executeUpdate("CREATE TABLE email_fts(emailId TEXT, accountId TEXT, subject TEXT)")
            st.executeUpdate("CREATE TABLE email_bodies(id TEXT, accountId TEXT, html TEXT, PRIMARY KEY(accountId, id))")
        }
    }

    @After fun tearDown() = db.close()

    // ---- the shipped sweep, as this file replays it -----------------------------------------------

    /** The account ids the shipped inventory statements report, unioned as the repository unions them. */
    private fun cachedAccountIds(): List<String> = SweepShape.inventory.flatMap { call ->
        val sql = call.sql()
        assertTrue(
            "an inventory statement must READ. `${call.text}` runs:\n$sql\nwhich is not a SELECT — a " +
                "statement standing before the decision is taken for part of the inventory, so a " +
                "delete written there is judged by none of the rules below. Put it after the " +
                "decision, where every delete is checked for being per-account.",
            sql.trimStart().startsWith("SELECT", ignoreCase = true),
        )
        assertEquals(
            "an inventory statement must ask for the whole table, not for one account: it is what " +
                "tells the sweep which accounts exist in the cache at all. `${call.text}` binds " +
                "${DaoQuerySource.bindOrder(sql).second}, in:\n$sql",
            emptyList<String>(), DaoQuerySource.bindOrder(sql).second,
        )
        db.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString("accountId") else null }.toList()
            }
        }
    }

    /** Runs one of the shipped deletes for [accountId], after checking it is scoped to an account. */
    private fun runDelete(call: SweepShape.Call, accountId: String) {
        val sql = call.sql()
        val (statement, order) = DaoQuerySource.bindOrder(sql)
        assertEquals(
            "⛔ every delete the sweep runs after its decision must be scoped to ONE account. " +
                "`${call.text}` runs:\n$sql\nwhich binds $order — an unscoped delete here empties " +
                "the cached mail of EVERY account (the sweep runs on every cold start), not just " +
                "the orphan's. This is the one mistake in #121 that costs the user her mail. It " +
                "counts wherever it stands after the decision, inside the loop over the orphans or " +
                "on the way to it.",
            listOf("accountId"), order,
        )
        assertEquals(
            "`${call.text}` must be passed the orphan the loop is on — the name that loop binds is " +
                "`${SweepShape.loopVariable}` — and nothing else. A delete handed anything wider " +
                "than the id being swept reaches accounts that are not orphans.",
            SweepShape.loopVariable, call.args.trim(),
        )
        db.prepareStatement(statement).use { it.setString(1, accountId); it.executeUpdate() }
    }

    /**
     * The decision, with its two arguments in the order the SHIPPED call passes them — the one
     */
    private fun decide(known: Collection<String>, cached: Collection<String>): List<String> {
        val values = mapOf(SweepShape.knownValue to known, SweepShape.cachedValue to cached)
        val args = SweepShape.decisionArgs
        assertEquals(
            "OrphanedAccountCache.orphans takes the known accounts and the cached ones, in that " +
                "order. The sweep passes ${args.size} argument(s): $args",
            2, args.size,
        )
        assertEquals(
            "the sweep hands the decision something this replay cannot follow. It knows two values: " +
                "the account list (`${SweepShape.knownValue}`) and the inventory " +
                "(`${SweepShape.cachedValue}`). Anything else — `listOf(${SweepShape.knownValue}." +
                "first())`, a filtered copy, a fresh read — is a narrowing of what counts as a known " +
                "account, and every account it drops loses its whole cache on the next cold start. " +
                "Pass the two values as read, or teach this replay the new shape on purpose.",
            emptyList<String>(), args.filterNot { it in values },
        )
        return OrphanedAccountCache.orphans(values.getValue(args[0]), values.getValue(args[1]))
    }

    /**
     * The whole sweep — inventory → account list → decision → the per-account deletes — in the
     */
    private fun sweep(knownAccountIds: () -> Collection<String>, afterFirstRead: () -> Unit = {}): List<String> {
        val cached: List<String>
        val known: Collection<String>
        if (SweepShape.inventoryIsReadFirst) {
            cached = cachedAccountIds()
            afterFirstRead()
            known = knownAccountIds()
        } else {
            known = knownAccountIds()
            afterFirstRead()
            cached = cachedAccountIds()
        }
        val orphans = decide(known, cached)
        orphans.forEach { accountId -> SweepShape.deletes.forEach { runDelete(it, accountId) } }
        return orphans
    }

    private fun sweep(knownAccountIds: Collection<String>): List<String> = sweep({ knownAccountIds })

    // ---- fixtures --------------------------------------------------------------------------------

    private fun insertEmail(accountId: String, id: String = "m1") =
        exec("INSERT INTO emails VALUES(?, ?, 'inbox')", id, accountId)

    private fun insertMailbox(accountId: String, id: String = "inbox") =
        exec("INSERT INTO mailboxes VALUES(?, ?, 'Inbox')", id, accountId)

    private fun insertIndexRow(accountId: String, id: String = "m1") =
        exec("INSERT INTO email_fts VALUES(?, ?, 'subject')", id, accountId)

    private fun insertBody(accountId: String, id: String = "m1") =
        exec("INSERT INTO email_bodies VALUES(?, ?, '<p>hi</p>')", id, accountId)

    private fun exec(sql: String, vararg args: String) =
        db.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeUpdate()
        }

    private fun rowCount(table: String, accountId: String): Int =
        db.prepareStatement("SELECT COUNT(*) FROM $table WHERE accountId = ?").use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { it.next(); it.getInt(1) }
        }

    // ---- what the sweep must see -----------------------------------------------------------------

    @Test fun `an orphan with only folders is swept`() {
        insertMailbox("gone")
        insertEmail("kept"); insertMailbox("kept")

        val swept = sweep(listOf("kept"))

        assertEquals(
            "an account left with folders and no message is the residue a failed first sync leaves, " +
                "and an inventory taken from `emails` alone never sees it: it reports no cached " +
                "account at all for `gone`, so nothing is ever swept and the rows stay for good",
            listOf("gone"), swept,
        )
        assertEquals("the orphan's folders are still there", 0, rowCount("mailboxes", "gone"))
        assertEquals("the live account lost its folders", 1, rowCount("mailboxes", "kept"))
        assertEquals("the live account lost its mail", 1, rowCount("emails", "kept"))
    }

    @Test fun `an orphan known only to the index or to the body cache is swept too`() {
        insertIndexRow("indexed-only")
        insertBody("body-only")
        insertEmail("kept")

        val swept = sweep(listOf("kept"))

        assertEquals(
            "every table the sweep deletes from must also be a table it looks in, or a residue " +
                "living in only one of them is invisible to it",
            listOf("body-only", "indexed-only"), swept.sorted(),
        )
        assertEquals(0, rowCount("email_fts", "indexed-only"))
        assertEquals(0, rowCount("email_bodies", "body-only"))
    }

    @Test fun `an orphan with mail is swept out of all four tables`() {
        insertEmail("gone"); insertMailbox("gone"); insertIndexRow("gone"); insertBody("gone")
        insertEmail("kept"); insertMailbox("kept"); insertIndexRow("kept"); insertBody("kept")

        assertEquals(listOf("gone"), sweep(listOf("kept")))

        listOf("emails", "mailboxes", "email_fts", "email_bodies").forEach { table ->
            assertEquals("$table still holds the orphan's rows", 0, rowCount(table, "gone"))
            assertEquals("$table lost the live account's rows", 1, rowCount(table, "kept"))
        }
    }

    @Test fun `an account listed by several tables is swept once`() {
        insertEmail("gone"); insertMailbox("gone"); insertIndexRow("gone"); insertBody("gone")
        assertEquals(
            "the union must collapse to one id — the deletes are idempotent, but a repeated id " +
                "would be reported to the caller as several accounts swept",
            listOf("gone"), sweep(listOf("kept")),
        )
    }

    /**
     * The whole point of reading the known accounts as a LIST rather than a filter: every account
     */
    @Test fun `nothing is swept while every cached account is a known one`() {
        val known = listOf("a", "b", "c")
        known.forEach { insertEmail(it); insertMailbox(it); insertIndexRow(it); insertBody(it) }

        assertEquals(
            "an account the store lists is never an orphan, however many of them there are",
            emptyList<String>(), sweep(known),
        )
        known.forEach { account ->
            listOf("emails", "mailboxes", "email_fts", "email_bodies").forEach { table ->
                assertEquals("$table lost $account's rows", 1, rowCount(table, account))
            }
        }
    }

    // ---- the guard --------------------------------------------------------------------------

    @Test fun `an empty list of known accounts sweeps nothing`() {
        insertEmail("acct"); insertMailbox("acct"); insertIndexRow("acct"); insertBody("acct")

        assertEquals(
            "no known account means the account list could not be read — never that every cached " +
                "row is an orphan. This is the one place in the sweep where a mistake costs the " +
                "user her mail.",
            emptyList<String>(), sweep(emptyList()),
        )
        listOf("emails", "mailboxes", "email_fts", "email_bodies").forEach {
            assertEquals("$it was swept with an empty list of known accounts", 1, rowCount(it, "acct"))
        }
    }

    // ---- the deletes, as the repository names them ---------------------------------------------

    /**
     * The replay above runs whatever `purgeOrphanedAccounts` calls after its decision, so an
     */
    @Test fun `every delete the sweep runs is scoped to one account`() {
        assertTrue(
            "no delete found after the decision in purgeOrphanedAccounts — the loop that removes " +
                "the orphans' rows is what this whole file guards. Body was:\n${SweepShape.body}",
            SweepShape.deletes.isNotEmpty(),
        )
        // Running them on an empty database is enough: runDelete refuses anything unscoped.
        SweepShape.deletes.forEach { runDelete(it, "some-orphan") }
    }

    @Test fun `the sweep looks in exactly the tables it deletes from`() {
        assertEquals(
            "a table swept but not inventoried leaves an orphan nothing can ever see again; a table " +
                "inventoried but not swept reports the same orphan on every start, for ever. " +
                "Inventory: ${SweepShape.inventory.map { it.text }}, deletes: " +
                "${SweepShape.deletes.map { it.text }}",
            SweepShape.deletes.map { it.dao }.toSortedSet(),
            SweepShape.inventory.map { it.dao }.toSortedSet(),
        )
    }

    // ---- the decision, as the repository calls it ----------------------------------------------

    /**
     * THE CALL ITSELF, ARGUMENTS INCLUDED. Pinning only the function name left two mutations
     */
    @Test fun `the decision is handed the account list first and the inventory second`() {
        assertEquals(
            "⛔ the sweep must hand OrphanedAccountCache.orphans the account list it read " +
                "(`${SweepShape.knownValue}`) and then the inventory it took " +
                "(`${SweepShape.cachedValue}`), unaltered and in that order. The two arguments have " +
                "the same type, so the compiler accepts them either way round: swapped, the sweep " +
                "stops sweeping anything (#121 returns); narrowed, it sweeps the accounts it " +
                "narrowed away. Body was:\n${SweepShape.body}",
            listOf(SweepShape.knownValue, SweepShape.cachedValue), SweepShape.decisionArgs,
        )
    }

    // ---- the order of the two reads ------------------------------------------------------------

    /**
     * The account created while the sweep runs. The trigger used to hand the sweep an already-read
     */
    @Test fun `an account created between the two reads is not swept`() {
        insertEmail("kept"); insertMailbox("kept")
        // The account store, as it changes under the sweep: the new account is added to it and
        // starts caching at the same moment, which is what "created" means here.
        val store = mutableListOf("kept")

        val swept = sweep(
            knownAccountIds = { store.toList() },
            afterFirstRead = { store.add("fresh"); insertMailbox("fresh"); insertEmail("fresh") },
        )

        assertEquals(
            "an account that appeared while the sweep was between its two reads was swept as an " +
                "orphan. Read the inventory FIRST: rows written after it belong to an account the " +
                "list read afterwards is guaranteed to contain.",
            emptyList<String>(), swept,
        )
        assertEquals("the new account lost the mail it had just cached", 1, rowCount("emails", "fresh"))
        assertEquals("the new account lost its folders", 1, rowCount("mailboxes", "fresh"))
    }

    @Test fun `the repository reads its inventory before it reads the account list`() {
        assertTrue(
            "purgeOrphanedAccounts must take the account list as a function and call it AFTER the " +
                "inventory — a list evaluated by the caller is read before the sweep even starts, " +
                "which is the window above. Body was:\n${SweepShape.body}",
            SweepShape.inventoryIsReadFirst,
        )
    }

    // ---- the wiring, read out of the shipped source ----------------------------------------------

    @Test fun `the repository takes its inventory from all four tables`() {
        val missing = listOf(
            "emailDao.countsByAccount()",
            "mailboxDao.accountIds()",
            "emailFtsDao.accountIds()",
            "emailBodyDao.accountIds()",
        ).filterNot { it in SweepShape.code }
        assertEquals(
            "purgeOrphanedAccounts must inventory every table it deletes from. Dropping one back " +
                "out leaves a residue that lives only there unreachable forever (#121). Body was:\n" +
                SweepShape.body,
            emptyList<String>(), missing,
        )
        assertTrue(
            "and the decision must stay OrphanedAccountCache's — a `NOT IN (:known)` in SQL would " +
                "delete the whole cache the day the account list fails to decode. Body was:\n" +
                SweepShape.body,
            SweepShape.DECISION in SweepShape.code,
        )
        assertTrue(
            "no `NOT IN` may appear in the sweep: the day the account list is empty, that statement " +
                "deletes everything. (Comments are stripped before this looks, so prose may say " +
                "\"not in\" freely — only code counts.) Code was:\n${SweepShape.code}",
            !SweepShape.code.contains("NOT IN", ignoreCase = true),
        )
    }

    /**
     * The sweep's two callers, in the app module — the cold start and the sign-out. Neither can be
     */
    @Test fun `both callers hand the sweep a reader of the account store`() {
        val callers = mapOf(
            "app/src/main/kotlin/app/sterna/SternaApplication.kt" to "accountStore",
            "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt" to "store",
        )
        callers.forEach { (path, storeName) ->
            val source = withoutComments(locate(path).readText())
            val callSites = Regex("""\bpurgeOrphanedAccounts\s*[({]""").findAll(source)
                .map { source.substring(it.range.first, minOf(source.length, it.range.first + 120)) }
                .map { it.replace(Regex("""\s+"""), " ") }
                .toList()
            assertTrue(
                "$path no longer calls purgeOrphanedAccounts — it is one of only two places the " +
                    "sweep ever runs from, so deleting the call silently retires the fix (#121). " +
                    "Move this rule rather than dropping it.",
                callSites.isNotEmpty(),
            )
            val wrong = callSites.filterNot {
                it.startsWith("purgeOrphanedAccounts {") && "$storeName.accounts()" in it
            }
            assertEquals(
                "$path must call purgeOrphanedAccounts { $storeName.accounts()… } — passing an " +
                    "already-read list restores the window where an account created during the " +
                    "sweep loses its cache.",
                emptyList<String>(), wrong,
            )
        }
    }

    /**
     * The shape of the shipped sweep, parsed once from `StorageRepository.kt`: which DAO calls take
     */
    private object SweepShape {

        const val DECISION = "OrphanedAccountCache.orphans("

        /** One `someDao.someCall(args)` in the sweep: [text] as written, [dao] as its class. */
        data class Call(val text: String, val dao: String, val function: String, val args: String) {
            fun sql(): String = runCatching {
                if (dao == "EmailDao") DaoQuerySource.emailDaoQuery(function)
                else DaoQuerySource.daoQuery(dao, function)
            }.getOrElse { error("the sweep calls $text, which has no readable @Query: ${it.message}") }
        }

        /** The text of `purgeOrphanedAccounts`, declaration to closing brace — for failure messages. */
        val body: String by lazy { functionText(SOURCE) }

        /**
         * The same, with the comments taken out — what every rule here actually reads.
         */
        val code: String by lazy { functionText(withoutComments(SOURCE)) }

        private val SOURCE: String by lazy {
            locate("core/data/src/main/kotlin/app/sterna/core/data/storage/StorageRepository.kt").readText()
        }

        /** `purgeOrphanedAccounts`, declaration to closing brace, in [source]. */
        private fun functionText(source: String): String {
            val start = source.indexOf("suspend fun purgeOrphanedAccounts(")
            check(start >= 0) { "purgeOrphanedAccounts is gone from StorageRepository — rename it here too" }
            val open = source.indexOf('{', start)
            var depth = 0
            for (i in open until source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return source.substring(start, i + 1)
                }
            }
            error("Unbalanced braces in purgeOrphanedAccounts")
        }

        /**
         * Where the sweep makes up its mind. Everything before it is the inventory; everything
         */
        val decisionAt: Int by lazy {
            code.indexOf(DECISION).also {
                check(it >= 0) {
                    "purgeOrphanedAccounts no longer calls $DECISION. That decision is the whole " +
                        "reason the sweep is not a `DELETE … WHERE accountId NOT IN (…)`: it is " +
                        "what refuses to treat an unreadable account list as \"everything is an " +
                        "orphan\". Body was:\n$body"
                }
            }
        }

        val inventory: List<Call> by lazy { calls(code.substring(0, decisionAt)) }

        /** Every DAO call standing after the decision — see [decisionAt]. */
        val deletes: List<Call> by lazy { calls(code.substring(decisionAt)) }

        /** The name of the parameter the account list is read through. */
        val listParameter: String by lazy {
            Regex("""fun\s+purgeOrphanedAccounts\s*\(\s*(\w+)\s*:""").find(code)?.groupValues?.get(1)
                ?: error("cannot read the parameter of purgeOrphanedAccounts. Body was:\n$body")
        }

        /** `val <name> = <listParameter>()`: where the account list lands. */
        val knownValue: String by lazy {
            bindings.firstOrNull { it.initializer.trim() == "$listParameter()" }?.name
                ?: error(
                    "the sweep must read the account list into a local val (`val known = " +
                        "$listParameter()`) before the decision — this file reads the source as " +
                        "text and follows that name into the call. Body was:\n$body",
                )
        }

        /** `val <name> = emailDao.countsByAccount() + …`: where the inventory lands. */
        val cachedValue: String by lazy {
            val first = inventory.firstOrNull()
                ?: error("no DAO call before the decision — the sweep takes no inventory. Body was:\n$body")
            bindings.firstOrNull { first.text in it.initializer }?.name
                ?: error(
                    "the sweep must read its inventory into a local val before the decision — this " +
                        "file follows that name into the call. Body was:\n$body",
                )
        }

        /** `val <name> = OrphanedAccountCache.orphans(…)`: what the loop iterates. */
        val orphansValue: String by lazy {
            Regex("""\bval\s+(\w+)\s*=\s*${Regex.escape(DECISION)}""").find(code)?.groupValues?.get(1)
                ?: error(
                    "the decision's result must go into a local val — the rules below follow that " +
                        "name to the loop that deletes. Body was:\n$body",
                )
        }

        /**
         * The two things the decision is handed, as written. Read with balanced parentheses, so a
         * `listOf(known.first())` is reported whole instead of being cut at its first `)`.
         */
        val decisionArgs: List<String> by lazy {
            argumentsAt(code, decisionAt + DECISION.length - 1)
        }

        /**
         * The name the loop binds each orphan to — `forEach { id ->`, a bare `forEach { … it … }`,
         */
        val loopVariable: String by lazy {
            val after = code.substring(decisionAt)
            val name = Regex("""\b$orphansValue\s*\.\s*forEach\s*\{\s*(\w+)\s*->""").find(after)
                ?: Regex("""\bfor\s*\(\s*(\w+)\s+in\s+$orphansValue\b""").find(after)
            when {
                name != null -> name.groupValues[1]
                Regex("""\b$orphansValue\s*\.\s*forEach\s*\{""").containsMatchIn(after) -> "it"
                else -> error(
                    "the deletes must run inside a loop over `$orphansValue`, one account at a " +
                        "time: that loop is what scopes them to the orphans. Body was:\n$body",
                )
            }
        }

        /**
         * Whether the account list is read after the inventory. A list evaluated by the caller
         */
        val inventoryIsReadFirst: Boolean by lazy {
            val listAt = code.indexOf("$listParameter()")
            val lastInventoryAt = inventory.lastOrNull()?.let { code.indexOf(it.text) } ?: -1
            listAt >= 0 && lastInventoryAt >= 0 && listAt > lastInventoryAt && listAt < decisionAt
        }

        /** The local `val`s declared before the decision, each with the text it is assigned. */
        private data class Binding(val name: String, val initializer: String)

        private val bindings: List<Binding> by lazy {
            val region = code.substring(0, decisionAt)
            val declarations = Regex("""\bval\s+(\w+)\s*=""").findAll(region).toList()
            declarations.mapIndexed { i, match ->
                val end = declarations.getOrNull(i + 1)?.range?.first ?: region.length
                Binding(match.groupValues[1], region.substring(match.range.last + 1, end))
            }
        }

        /** The top-level arguments of the call whose `(` sits at [open]. */
        private fun argumentsAt(text: String, open: Int): List<String> {
            val args = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            for (i in open until text.length) {
                val c = text[i]
                when {
                    c == '(' -> { depth++; if (depth > 1) current.append(c) }
                    c == ')' -> {
                        if (--depth == 0) {
                            if (current.isNotBlank()) args += current.toString().trim()
                            return args
                        }
                        current.append(c)
                    }
                    c == ',' && depth == 1 -> { args += current.toString().trim(); current.clear() }
                    else -> current.append(c)
                }
            }
            error("Unbalanced parentheses in the call to $DECISION. Body was:\n$body")
        }

        private fun calls(segment: String): List<Call> =
            DAO_CALL.findAll(segment)
                .map {
                    Call(
                        text = it.value,
                        dao = it.groupValues[1].replaceFirstChar(Char::uppercase),
                        function = it.groupValues[2],
                        args = it.groupValues[3],
                    )
                }
                .distinct()
                .toList()

        /** `emailFtsDao.clearAccount(accountId)` and friends: the property, the call, its arguments. */
        private val DAO_CALL = Regex("""\b(\w*[dD]ao)\.(\w+)\(([^)]*)\)""")
    }

    private companion object {
        /** [relative] resolved from the module's working directory, walking up to the repo root. */
        fun locate(relative: String): File {
            val fromModule = relative.substringAfter("core/data/")
            val cwd = System.getProperty("user.dir").orEmpty()
            var dir: File? = File(cwd).absoluteFile
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { return it }
                File(dir, fromModule).takeIf { it.isFile }?.let { return it }
                dir = dir.parentFile
            }
            error("Cannot find $relative from $cwd")
        }

        /**
         * [source] with its Kotlin comments removed, string literals left alone.
         */
        fun withoutComments(source: String): String {
            val out = StringBuilder(source.length)
            var i = 0
            while (i < source.length) {
                when {
                    source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') i++
                    source.startsWith("/*", i) -> {
                        val end = source.indexOf("*/", i + 2)
                        i = if (end < 0) source.length else end + 2
                        out.append(' ')
                    }
                    source.startsWith("\"\"\"", i) -> {
                        val end = source.indexOf("\"\"\"", i + 3)
                        val stop = if (end < 0) source.length else end + 3
                        out.append(source, i, stop)
                        i = stop
                    }
                    source[i] == '"' -> {
                        out.append(source[i]); i++
                        while (i < source.length && source[i] != '"') {
                            if (source[i] == '\\' && i + 1 < source.length) { out.append(source[i]); i++ }
                            out.append(source[i]); i++
                        }
                        if (i < source.length) { out.append(source[i]); i++ }
                    }
                    else -> { out.append(source[i]); i++ }
                }
            }
            return out.toString()
        }
    }
}
