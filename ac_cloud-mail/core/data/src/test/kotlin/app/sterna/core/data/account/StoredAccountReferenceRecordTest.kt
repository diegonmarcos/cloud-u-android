package app.sterna.core.data.account

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TRIGGER, not the consequence. Volets A–C stop an unreadable account list from being
 * overwritten; this file stops it from becoming unreadable in the first place.
 *
 * The whole account list is ONE JSON blob, kotlinx serialises enums BY NAME, and `ignoreUnknownKeys`
 * does not cover a name — so renaming or dropping ONE enum member stops the WHOLE list from
 * decoding, including the accounts that never carried that member. On the device that reads as
 * **no accounts at all** (`AccountStore.accounts()` answers a decode failure with an empty list),
 * and every write is then refused until a build that knows the name is installed ([AccountBlobGate]).
 * It happened twice on the bench on 2026-08-06. The same is true of a field that loses its default:
 * every record already on disk was written without it.
 *
 * And the rename COMPILES. An IDE "rename symbol" updates every call site, the build is green,
 * review sees a tidy diff, and the damage is only visible on a phone that already has accounts.
 * Nothing but this file stands between that refactor and the account screens of every install.
 *
 * How it is built, and why it is not a list of names someone has to remember to extend:
 *  - [enumFields] holds, per persisted key, the members read OFF THE ENUM (which move with a
 *    rename) next to the names written BY HAND below (which do not). A rename makes the two sets
 *    disagree in one direction, a NEW member makes them disagree in the other — so a member added
 *    tomorrow and left uncovered fails this file instead of slipping through it;
 *  - the no-default fields are not listed at all: [minimalAccount] carries only `id`/`server`/
 *    `username`, so ANY field that stops having a default — a new one, or an old one that loses it —
 *    makes that record stop decoding, whatever it is called;
 *  - [enumFields] is itself checked against the SERIALIZER'S DESCRIPTOR, so a FIFTH enum added to
 *    the record cannot arrive uncovered. Without that, the list of keys below was hand-written and
 * a new enum-valued field would land on every user's disk with no on it and nothing pinning
 *    its names — the original trigger, rebuilt underneath the net that was meant to stop it.
 *
 * The parser is the store's own (`Json { ignoreUnknownKeys = true }`, pinned against the shipped
 * source by the last two tests below — the backup codec decodes the SAME records with its own
 * `Json` and feeds them straight back into the store): with `coerceInputValues` or an enum default
 * either of them would silently substitute a value instead of failing, which would change the
 * stored format under everyone and make every conclusion here false.
 *
 * WHAT THIS NET DOES NOT CATCH — read this before trusting it.
 *
 *  1. **New name → older build.** It catches a name LEAVING this tree: rename or delete, and a
 *     record already on disk stops decoding here. The other direction is invisible — a member added
 *     today, written to a device, then an earlier build installed over it (an APK downgrade, a
 *     bench shared between branches, a rollback) hands that older build a name it has never heard
 *     of, and that build has no test that could ever have known about it. Inobservable from a
 *     single tree, still true after this volet, and exactly what the bench hit on 2026-08-06. The
 *     only protection there is [AccountBlobGate] refusing to write: it keeps the records, not the
 *     screen.
 *  2. **A COORDINATED edit.** Renaming a member *and* updating `covered` in the same commit is
 *     green, by construction: no test can tell that apart from a legitimate new member. The failure
 *     message is the whole barrier — it exists to be read by someone who has just been stopped, and
 *     to make them put the name back instead of updating the list.
 *  3. **Key renames outside the enum-valued ones.** The five keys in [enumFields] are pinned;
 *     `StoredAccount` has some thirty. Renaming `watchedFolders` compiles, passes everything here,
 *     and silently drops every user's watched folders (`ignoreUnknownKeys` eats the old key). Out of
 *     this volet's scope on purpose, and said rather than half-done.
 *  4. **Enums persisted ELSEWHERE** (`SettingsRepository`'s `SwipeAction`, `PreviewLines`,
 *     `NotificationContent`, …). Those live in their own prefs keys, so a rename there loses one
 *     setting, not the account list.
 */
@OptIn(ExperimentalSerializationApi::class) // SerialDescriptor's element accessors; test-only.
class StoredAccountReferenceRecordTest {

    /** Not a `Json` of my own: the configuration [AccountStore] declares (pinned below). */
    private val json = Json { ignoreUnknownKeys = true }

    // ── The reference records: JSON exactly as a device has it in its prefs file ────────────────

    /**
     * The SMALLEST record a device can hold — the three fields that have no default and nothing
     */
    private val minimalAccount =
        """{"id":"a1","server":"https://mail.example.org","username":"alex"}"""

    /** Same, for the identities nested inside a record. */
    private val minimalIdentity =
        """{"id":"i1","name":"Alex Doe","email":"alex@example.org"}"""

    /**
     * A whole record as a configured IMAP account has it: every persisted enum at once, and every
     */
    private val wholeAccount =
        """{"id":"a1","server":"https://mail.example.org","username":"alex",""" +
            """"authType":"OAUTH","protocol":"IMAP","imapSecurity":"STARTTLS",""" +
            """"smtpSecurity":"NONE","syncWindow":"COUNT_500",""" +
            """"imapPort":143,"inboxName":"INBOX",""" +
            """"identities":[$minimalIdentity]}"""

    /** One persisted enum-valued key of an account record. */
    private class EnumField(
        /** The JSON key, as written on disk. */
        val key: String,
        /** Read off the enum: MOVES when a member is renamed. */
        val members: List<String>,
        /** Written by hand: does NOT move when a member is renamed. The on-disk truth. */
        val covered: List<String>,
        /** The name the decoded record came back with, for this key. */
        val read: (StoredAccount) -> String,
    )

    private val enumFields = listOf(
        EnumField("authType", AuthType.entries.map { it.name }, listOf("BASIC", "OAUTH", "API_TOKEN")) { it.authType.name },
        EnumField("protocol", MailProtocol.entries.map { it.name }, listOf("JMAP", "IMAP")) { it.protocol.name },
        EnumField("imapSecurity", ConnectionSecurity.entries.map { it.name }, listOf("TLS", "STARTTLS", "NONE")) { it.imapSecurity.name },
        EnumField("smtpSecurity", ConnectionSecurity.entries.map { it.name }, listOf("TLS", "STARTTLS", "NONE")) { it.smtpSecurity.name },
        EnumField(
            "syncWindow",
            SyncWindow.entries.map { it.name },
            listOf(
                "DAYS_30", "DAYS_90", "YEAR_1",
                "COUNT_100", "COUNT_1000", "COUNT_10000",
                "COUNT_50", "COUNT_200", "COUNT_500", "ALL",
            ),
        ) { it.syncWindow.name },
    )

    /** [minimalAccount] with one enum key added — a record a device really could hold. */
    private fun record(key: String, name: String) =
        """{"id":"a1","server":"https://mail.example.org","username":"alex","$key":"$name"}"""

    // ── The net ────────────────────────────────────────────────────────────────────────────────

    /**
     * The rename catcher. Sets, not lists: the DECLARATION ORDER of an enum is not persisted
     */
    @Test fun `every enum name a device may have on disk is still a member, and every member is covered`() {
        enumFields.forEach { field ->
            val gone = field.covered - field.members.toSet()
            assertEquals(
                "STOP — you renamed or deleted $gone from the enum behind \"${field.key}\", and " +
                    "that name is written in the account records on users' phones. It is not an " +
                    "identifier, it is a stored format: the account list is one JSON blob, kotlinx " +
                    "reads enums by name, and one name this build cannot resolve stops EVERY " +
                    "account from decoding — the accounts screen goes empty and stays empty until " +
                    "a build that knows the name is installed. Put the name back exactly as it " +
                    "was. You may change what it MEANS, and you may retire it from the pickers " +
                    "(SyncWindow.COUNT_50 and friends are retired and still decode); you may not " +
                    "change what it is CALLED.",
                emptyList<String>(),
                gone,
            )
            val uncovered = field.members - field.covered.toSet()
            assertEquals(
                "\"${field.key}\" gained $uncovered and no reference record here mentions it. Add " +
                    "it to `covered` above — and know what that costs: from the first device that " +
                    "stores this name, it can never be renamed or removed, because doing so wipes " +
                    "that device's whole account list off its screen.",
                emptyList<String>(),
                uncovered,
            )
        }
    }

    /**
     * Exhaustive over the ENUMS, not only over their members — the hole the check above cannot
     */
    @Test fun `every enum-valued key of the stored record is covered by this file`() {
        assertEquals(
            "the enum-valued keys of a stored account record are no longer the ones this file " +
                "covers. If one appeared, a new enum is about to be written to every user's disk " +
                "with nothing pinning its member names and no ⛔ on its declaration — the exact " +
                "shape that lost the bench's accounts twice on 2026-08-06. Add it to enumFields " +
                "below (one reference record per member) and put the ⛔ comment on the enum, next " +
                "to AuthType's. If one disappeared, the field was renamed or dropped and every " +
                "device's stored value for it is now silently discarded.",
            enumFields.map { it.key }.sorted(),
            enumKeys(StoredAccount.serializer().descriptor),
        )
    }

    @Test fun `a stored identity still holds no enum of its own`() {
        assertEquals(
            "StoredIdentity gained an enum-valued field. Identities are nested INSIDE the account " +
                "blob, so its member names are just as permanent as the account's — and this file " +
                "only knows how to cover ACCOUNT-level keys, so it would not have pinned them. " +
                "Cover it here before this ships.",
            emptyList<String>(),
            enumKeys(StoredIdentity.serializer().descriptor),
        )
    }

    /** The names of [descriptor]'s elements that are serialized as an enum, sorted. */
    private fun enumKeys(descriptor: SerialDescriptor): List<String> =
        (0 until descriptor.elementsCount)
            .filter { descriptor.getElementDescriptor(it).kind == SerialKind.ENUM }
            .map { descriptor.getElementName(it) }
            .sorted()

    /**
     * THE DRAMA ITSELF, executed for every persisted enum and not only for `SyncWindow`
     */
    @Test fun `one impossible name in one record takes the WHOLE account list down`() {
        enumFields.forEach { field ->
            val blob = """[{"id":"a1","server":"s","username":"alex","${field.key}":"NOT_A_MEMBER_OF_THIS_ENUM"},""" +
                """{"id":"a2","server":"s","username":"jordan"}]"""

            val gate = AccountBlobGate(
                decode = { json.decodeFromString<List<StoredAccount>>(it) },
                encode = { json.encodeToString(ListSerializer(StoredAccount.serializer()), it) },
            )
            assertEquals(
                "a bad \"${field.key}\" no longer costs the whole list — if kotlinx has started " +
                    "salvaging the records that parse, the premise of this whole file is gone and " +
                    "so is the reason the ⛔ comments exist.",
                emptyList<StoredAccount>(),
                gate.read(blob),
            )
            assertTrue("the gate did not notice that \"${field.key}\" broke the decode", gate.blobUnreadable)
            assertFalse(
                "and the loss would be PERMANENT: the empty list this read handed back is about " +
                    "to be written over jordan's account too, by the first setter the user touches.",
                gate.writeGuarded(emptyList()) {},
            )
        }
    }

    /**
     * The same names, EXECUTED: one record per member, decoded through the store's parser. The
     */
    @Test fun `every name on disk decodes, one reference record each`() {
        enumFields.forEach { field ->
            field.covered.forEach { name ->
                val raw = record(field.key, name)
                val decoded = runCatching { json.decodeFromString(StoredAccount.serializer(), raw) }
                assertTrue(
                    "an account record carrying \"${field.key}\":\"$name\" no longer decodes. That " +
                        "member was renamed or removed; on a device that carries it, every account " +
                        "disappears at once — not just this one, and with no error on screen.",
                    decoded.isSuccess,
                )
                assertEquals(
                    "\"${field.key}\":\"$name\" decoded into another member: the stored format no " +
                        "longer means what it meant.",
                    name,
                    field.read(decoded.getOrThrow()),
                )
            }
        }
    }

    /**
     * THE ANTI-VACUITY CHECK, and the reason the rest of this file means anything. If a key were
     */
    @Test fun `each key is genuinely read, so an impossible name is refused and not shrugged off`() {
        enumFields.forEach { field ->
            val decoded = runCatching {
                json.decodeFromString(StoredAccount.serializer(), record(field.key, "NOT_A_MEMBER_OF_THIS_ENUM"))
            }
            assertTrue(
                "\"${field.key}\" is no longer read from the record: the key was renamed or removed " +
                    "in StoredAccount and ignoreUnknownKeys now discards whatever devices stored " +
                    "under it — silently, on every install, losing that setting for everyone. (If " +
                    "instead the parser gained coerceInputValues or an enum default, an unknown " +
                    "name now becomes the default value instead of failing, which is the stored " +
                    "format changing under every user without a migration.) ⚠ This guards the " +
                    "FIVE enum-valued keys and nothing else: renaming a key like watchedFolders " +
                    "is just as silent and no test here would see it.",
                decoded.isFailure,
            )
        }
    }

    /**
     * The no-default catcher, and it needs no list of fields: a record with only the three
     */
    @Test fun `a record holding only the three mandatory fields still decodes`() {
        val decoded = runCatching { json.decodeFromString(StoredAccount.serializer(), minimalAccount) }
        assertTrue(
            "a field of StoredAccount has no default value any more. Every account record ever " +
                "written is missing it, so the whole list stops decoding on the update that ships " +
                "this — every account gone from the screen, on every install. Give the field a " +
                "default; a new persisted field is ALWAYS optional.",
            decoded.isSuccess,
        )
        val account = decoded.getOrThrow()
        assertEquals("a1", account.id)
        assertEquals("https://mail.example.org", account.server)
        assertEquals("alex", account.username)
    }

    @Test fun `an identity holding only its three mandatory fields still decodes`() {
        val decoded = runCatching { json.decodeFromString(StoredIdentity.serializer(), minimalIdentity) }
        assertTrue(
            "a field of StoredIdentity has no default value any more. Identities are nested INSIDE " +
                "the account blob, so this does not lose an identity: it takes the whole account " +
                "list down with it. Give the field a default.",
            decoded.isSuccess,
        )
        assertEquals("alex@example.org", decoded.getOrThrow().email)
    }

    /**
     * The other direction, so the check above cannot pass by being vacuous: these three, and
     */
    @Test fun `and exactly three account keys are fatal when absent`() {
        assertEquals(
            "id/server/username are no longer exactly the account fields whose absence is fatal: " +
                "one of them became optional, so a record can now decode into an account with no " +
                "server or no identity of its own. (This assertion only ever removes keys that ARE " +
                "in the reference record, so it cannot see a NEW mandatory field appearing — that " +
                "is what `a record holding only the three mandatory fields still decodes` catches, " +
                "and it is the dangerous direction of the two.)",
            listOf("id", "server", "username"),
            fatalWhenAbsent(minimalAccount) { json.decodeFromJsonElement(StoredAccount.serializer(), it) },
        )
    }

    @Test fun `and exactly three identity keys are fatal when absent`() {
        assertEquals(
            "id/name/email are no longer exactly the identity fields whose absence is fatal — see " +
                "the account version of this message; an identity is decoded as part of the " +
                "account blob, so it takes the whole list with it.",
            listOf("email", "id", "name"),
            fatalWhenAbsent(minimalIdentity) { json.decodeFromJsonElement(StoredIdentity.serializer(), it) },
        )
    }

    /** The keys of [record] whose removal makes [decode] fail, sorted. */
    private fun fatalWhenAbsent(record: String, decode: (JsonObject) -> Any): List<String> {
        val whole = json.parseToJsonElement(record).jsonObject
        return whole.keys
            .filter { key -> runCatching { decode(JsonObject(whole - key)) }.isFailure }
            .sorted()
    }

    /**
     * One whole record, every persisted enum at once, each on a non-default member — the shape a
     * real IMAP account has on disk, decoded in one go rather than one key at a time.
     */
    @Test fun `a whole configured account record decodes, every enum included`() {
        val account = json.decodeFromString(StoredAccount.serializer(), wholeAccount)

        assertEquals(AuthType.OAUTH, account.authType)
        assertEquals(MailProtocol.IMAP, account.protocol)
        assertEquals(ConnectionSecurity.STARTTLS, account.imapSecurity)
        assertEquals(ConnectionSecurity.NONE, account.smtpSecurity)
        assertEquals(SyncWindow.COUNT_500, account.syncWindow)
        assertEquals(listOf("alex@example.org"), account.identities.map { it.email })
        // Two NON-enum keys, both stored at a value that is not their default (993 / "Inbox"), so
        // this really shows the record being read rather than reconstructed from defaults.
        assertEquals(143, account.imapPort)
        assertEquals("INBOX", account.inboxName)
    }

    /**
     * A SOURCE READ, deliberately, and the only one here: `AccountStore` needs a `Context` and the
     */
    @Test fun `the store still parses with the codec these records were read with`() {
        assertEquals(
            "AccountStore's Json is no longer `Json { ignoreUnknownKeys = true }`. If it gained " +
                "coerceInputValues or a default enum member, an unknown name no longer fails: it " +
                "becomes some other value, the record is rewritten with it on the next save, and " +
                "the user's setting is quietly replaced instead of protected. Every record in this " +
                "file is then read by a parser the app does not use.",
            listOf("private val json = Json { ignoreUnknownKeys = true }"),
            codeBlock(
                "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt",
                "private val json",
                1,
            ),
        )
    }

    /**
     * THE SECOND DECODER OF THE SAME RECORDS. `SettingsBackupCodec` reads `List<StoredAccount>`
     */
    @Test fun `the backup codec still refuses a name it cannot resolve instead of substituting one`() {
        assertEquals(
            "SettingsBackupCodec's Json changed. If it gained coerceInputValues or a default enum " +
                "member, importing an old backup no longer fails on an unknown name: it silently " +
                "rewrites that account's protocol/security/auth to a default and stores it. That " +
                "is the tolerance AccountStore deliberately does not have, entering by the back " +
                "door — the accounts survive the import and come back WRONG.",
            listOf(
                "private val json = Json {",
                "prettyPrint = true",
                "ignoreUnknownKeys = true",
                "encodeDefaults = true",
                "}",
            ),
            codeBlock(
                "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsBackup.kt",
                "private val json",
                5,
            ),
        )
    }

    /**
     * [count] consecutive CODE lines of [path] from the single one starting with [prefix], trimmed;
     */
    private fun codeBlock(path: String, prefix: String, count: Int): List<String> {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        val code = java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines of $path start with `$prefix`»")
        return code.subList(start, minOf(start + count, code.size))
    }
}
