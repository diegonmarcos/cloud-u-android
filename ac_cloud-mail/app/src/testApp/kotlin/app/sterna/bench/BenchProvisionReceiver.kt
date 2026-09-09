package app.sterna.bench

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.sterna.container
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.settings.SettingsBackupCodec
import app.sterna.ui.connect.ConnectState
import app.sterna.ui.connect.ConnectViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * POSES the state of a bench device over adb: adds one account, puts manual identities on it,
 * restores a block of settings, and READS BACK what it wrote.
 *
 * Why this exists: on 2026-08-06 both bench phones lost their accounts and every screen was retyped
 * by hand — about 34 verified gestures per device (16 of them the IMAP form alone), times three
 * devices. A bench whose repair costs a hundred gestures does not get repaired.
 *
 * ⛔ THE BOUNDARY, NON-NEGOTIABLE: this pipe poses state, it NEVER performs the gesture under
 * measurement. The day an account is added by broadcast in order to validate the add-account
 * screen, the bench proves nothing. Provision the state a scenario STARTS from; drive the screen
 * being tested through the UI like a user.
 *
 * ⛔ EXPOSURE, WRITTEN PLAINLY: this receiver is `android:exported="true"` and carries NO
 * permission. It cannot be otherwise — a non-exported receiver refuses an explicit broadcast from
 * the shell uid, which is exactly how it is driven. So ANY app installed on the device can call it
 * and make it add an account or overwrite settings. Nothing here protects it; what makes that
 * acceptable is that the class only exists in `app.sterna.test`, built behind `-PtestApp`, never
 * shipped: the production package contains no such component. Do not claim a permission guards it,
 * because none does — and do not move this file out of `src/testApp/kotlin`.
 *
 * It declares no intent-filter either, so an implicit broadcast can never land here: the driver
 * must name the component explicitly.
 *
 * ---------------------------------------------------------------------------------------------
 * HOW IT IS DRIVEN
 *
 *   adb push bench.json /data/local/tmp/bench.json
 *   adb shell 'touch /data/local/tmp/bench.out && chmod 666 /data/local/tmp/bench.out'
 *   adb shell am broadcast -n app.sterna.test/app.sterna.bench.BenchProvisionReceiver \
 *       -a app.sterna.test.bench.PROVISION \
 *       --es spec /data/local/tmp/bench.json --es out /data/local/tmp/bench.out
 *
 * ⛔ THE SECRET NEVER TRAVELS ON THE BROADCAST. The password is read from the JSON file; the
 * broadcast carries only its PATH. An `--es` password would leak through at least three certain
 * channels: the `argv` of `docker exec` visible in `ps` on the host, the failure output of the
 * driving tool (which reprints the whole command line), and `am`'s own `Broadcasting: Intent { … }`
 * echo. This receiver never prints a password either — not in a report, not in a failure message,
 * not truncated.
 *
 * The out file must be PRE-CREATED world-writable by the pushing script, as above: `/data/local/tmp`
 * is `shell:shell` and this app's uid may traverse it but not create files in it. If the file is
 * missing or unwritable the run still produces its verdict — see "WHERE THE VERDICT GOES".
 *
 * ---------------------------------------------------------------------------------------------
 * THE SPEC FILE (one JSON object; all three blocks are optional, but not all three at once)
 *
 * {
 *   "account": {
 *     "mode": "jmap",                          // "jmap" | "jmap-auto" | "imap"
 *     "server": "https://mail.example.com",    // jmap only; ignored (may be absent) for jmap-auto
 *     "username": "alex@example.com",
 *     "password": "…",                         // the ONLY place a secret ever appears
 *     "accountName": "Alex JMAP",
 *     "imapHost": "mail.example.com",          // imap only, from here down
 *     "imapPort": 993,
 *     "imapSecurity": "TLS",                   // TLS | STARTTLS | NONE
 *     "smtpHost": "mail.example.com",
 *     "smtpPort": 587,
 *     "smtpSecurity": "STARTTLS"
 *   },
 *   "identities": [
 *     { "id": "bench-alias", "name": "Alex Alias", "email": "alias@example.com",
 *       "signature": "-- \nAlex", "signatureHtml": "", "default": true }
 *   ],
 *   "settings": { "themeMode": "DARK", "conversationView": true, "unreadTint": true }
 * }
 *
 * `settings` is a `SettingsBackup` object — the very same JSON the app's own settings export
 * writes, so a bench is described by ONE file. A whole exported backup can be pasted in here,
 * minus its `accounts` block (see below).
 *
 * NO `account` BLOCK = pose the identities and the settings on the CURRENT account. That is how an
 * already-provisioned device is topped up: a device that still has its accounts but lost a setting,
 * or a persona that needs one more alias. Adding the same account twice is refused as a duplicate,
 * so this is the only way in on such a device. Identities with no account block and no current
 * account is an explicit failure, and a spec with none of the three blocks is refused outright.
 *
 * ⛔ AN UNKNOWN KEY IS REFUSED — in the spec, in `account`, in an identity entry. `"smptPort": 465`
 * used to fall through to the 587 default and report success: a plausible-looking wrong port on the
 * very form (16 gestures) this whole pipe exists to stop retyping. What is posed is also read back
 * and compared field by field, so a right key with a wrong value fails too.
 *
 * ⛔ `settings.accounts` IS REFUSED, not ignored. That field belongs to the settings-import path,
 * which is a DIFFERENT account creator; this receiver does not borrow it. A spec carrying it fails
 * loudly rather than quietly creating accounts by another route.
 *
 * ⚠ TWO SETTINGS ARE NOT POSED, because `SettingsRepository.restoreBackup` does not apply them:
 * `language` and `pushAllAccounts`. This receiver does not guess them by writing DataStore behind
 * the repository's back — and ASKING FOR EITHER FAILS THE RUN: they land in the report's `manual`
 * list, `manual` is not empty, so `ok` is false and the broadcast returns 1. A bench spec simply
 * does not carry those two keys; one that does is asking for a device state this pipe will not
 * produce, and being told "yes" would be a lie. Set them by hand.
 *
 * ⛔ THE WELCOME SCREEN *IS* SKIPPED — measured on the bench, 2026-08-06, and stated here because
 * this file used to claim the opposite. `hasSeenWelcome` is indeed left alone (it is not part of a
 * settings backup), but leaving it alone preserves nothing: `SternaApp.kt` only consults that flag
 * under `RootState.NeedAccount`, and an account posed here makes the state `Authenticated`, so the
 * welcome branch is never evaluated. A freshly provisioned device lands straight on the message
 * list. Witness: the same `pm clear` WITHOUT provisioning does show the welcome screen.
 * Consequence, and it is the whole point of the boundary above: the welcome screen is one more
 * screen this pipe short-circuits, so it needs a DELIBERATE hand pass in the test plans — it does
 * not get one for free.
 *
 * ---------------------------------------------------------------------------------------------
 * WHAT IT REFUSES TO DO — each of these bought something
 *
 *  - It invents NO add path. It builds a [ConnectViewModel] and calls the same three functions the
 *    connect screen calls, then watches its `state`. It does not copy their probe, their validate /
 *    persist / prime lambdas, their inbox-metadata write, nor the store's own account creator —
 *    that ordering is what closed #121 (priming a cache before the account exists wrote a page of
 *    inbox rows under `accountId = ""`, rows no account owns and no label can name). A fourth copy
 *    of it here would re-open exactly that.
 *  - It NEVER undoes its own add. A failed priming keeps the account, by design in the ViewModel;
 *    taking it back out moves the current account and leaves folder rows behind.
 *  - It REMOVES NOTHING, ever. No account deletion, no purge, no reset. Starting from zero is
 *    `adb shell pm clear app.sterna.test`, which is the shell's job, not this receiver's.
 *  - It REFUSES A DUPLICATE instead of overwriting it. An account with the same (endpoint,
 *    username, protocol) already present is an explicit failure and nothing is written. The bench
 *    has already carried six homonym accounts nobody could tell apart on screen.
 *  - Manual identities are posed with the store's manual identity setter, never the server-owned
 *    one: the server list is rewritten on every connect, so an identity posed there would vanish at
 *    the first sync. Existing manual identities are merged, never replaced wholesale.
 *
 * ---------------------------------------------------------------------------------------------
 * WHERE THE VERDICT GOES — and why it is a read-back
 *
 * The verdict is computed by RE-READING the store and the settings snapshot after writing, never
 * from "the broadcast was delivered". A recent false green was a `print` of a constant that nothing
 * measured. Every field the spec asked for is compared against what came back: the account's own
 * fields, the identities, the settings. The `readback` action does the same read on its own, with
 * no write at all (what its `ok` means is documented on that function).
 *
 * Three channels, all carrying the same JSON report, none of them ever carrying a password:
 *  1. logcat, tag [TAG], one line per report line (`adb logcat -d -s BenchProvision`);
 *  2. the file named by the `out` extra, when it is under `/data/local/tmp/` and writable — a
 *     script reads it without parsing logcat. A path anywhere else is REFUSED, not written: see
 *     `isBenchOutPathAllowed`, and note that writing truncates whatever it lands on;
 *  3. the ordered-broadcast result: code 0 on success, 1 on failure, with a one-line summary as
 *     result data, which `am broadcast` prints on the shell's stdout by itself.
 * Channel 3 is what a script should branch on; 1 and 2 are what a human reads afterwards.
 */
class BenchProvisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The receiver is exported and filterless, but an explicit intent can still name any
        // action at all — so check what actually arrived (same reflex as BootReceiver).
        val action = intent.action
        if (action != ACTION_PROVISION && action != ACTION_READBACK) {
            Log.w(TAG, "ignoring unknown action: $action")
            return
        }
        val app = context.applicationContext as? Application ?: return
        val specPath = intent.getStringExtra(EXTRA_SPEC)
        val outPath = intent.getStringExtra(EXTRA_OUT)

        // goAsync: everything below is disk + network I/O and onReceive runs on the main thread.
        // The deadline matters as much as the async hop — a broadcast that never finishes is
        // killed by the system with no verdict at all, which reads exactly like a silent success.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val report = try {
                withTimeout(DEADLINE_MS) {
                    when (action) {
                        ACTION_READBACK -> readBack(app, note = null)
                        else -> provision(app, specPath)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                // Deliberately NOT rethrown as cancellation: the deadline is a verdict.
                failure(
                    "timed out after ${DEADLINE_MS} ms. The account may still be being added in the " +
                        "background — nothing is undone here. Read the state back before retrying, " +
                        "or a retry will be refused as a duplicate.",
                    timeout,
                )
            } catch (t: Throwable) {
                // ⛔ A JSONException quotes the text it choked on, and that text IS the spec file —
                // which holds the password. Its message never reaches the log, the report, or the
                // shell; only its type does.
                if (t is JSONException) {
                    failure("the spec file is not the JSON this receiver expects (message withheld: it quotes file content)")
                } else {
                    failure("${t.javaClass.simpleName}: ${t.message.orEmpty()}", t)
                }
            }
            publish(report, outPath, pending)
        }
    }

    // -- actions ----------------------------------------------------------------------------------

    private suspend fun provision(app: Application, specPath: String?): JSONObject {
        if (specPath.isNullOrBlank()) {
            return failure("no --es $EXTRA_SPEC extra: the spec file path is how the secret stays off the command line")
        }
        val file = File(specPath)
        if (!file.isFile) return failure("spec file not found: $specPath (push it first)")
        // Same reason as the catch in onReceive: a parse error quotes the document, and the
        // document holds the password. Report the failure, never the parser's message.
        val root = runCatching { JSONObject(file.readText()) }.getOrNull()
            ?: return failure("spec file $specPath is not valid JSON (message withheld: it would quote the file, which holds the password)")

        // ⛔ UNKNOWN KEYS ARE REFUSED, at every level of the file. A misspelling used to be the
        // quietest failure this receiver had: "smptPort" fell through to the 587 default and an
        // IMAP account was born on the wrong port with the report saying ok. A key nobody reads is
        // a key nobody posed, so it fails here, before anything is written.
        unknownKeys(root, ROOT_KEYS, "the spec")?.let { return failure(it) }

        val settingsRequest = root.optJSONObject("settings")
        if (settingsRequest != null && settingsRequest.has("accounts")) {
            return failure(
                "the settings block carries an \"accounts\" field. That is the settings-import " +
                    "creator, a different add path this receiver does not borrow; strip it and put " +
                    "the account in the \"account\" block.",
            )
        }
        val identitiesRequest = root.optJSONArray("identities")
        (0 until (identitiesRequest?.length() ?: 0)).forEach { i ->
            val entry = identitiesRequest?.optJSONObject(i)
                ?: return failure("identity entry #$i is not a JSON object")
            unknownKeys(entry, IDENTITY_KEYS, "identity entry #$i")?.let { return failure(it) }
        }
        val accountRequest = root.optJSONObject("account")
        accountRequest?.let { unknownKeys(it, ACCOUNT_KEYS, "the account block")?.let { m -> return failure(m) } }
        if (accountRequest == null && identitiesRequest == null && settingsRequest == null) {
            return failure("the spec poses nothing: no \"account\", no \"identities\", no \"settings\"")
        }

        val store = app.container.accountStore
        val problems = mutableListOf<String>()
        val spec = accountRequest?.let { parseAccount(it) }

        // -- the account, when one is asked for -----------------------------------------------------
        // No account block = pose identities/settings on the account already there. That is the
        // COMMON case: a bench device that still has its accounts but lost a setting, or a persona
        // that needs one more alias. Requiring an account block made those unreachable, since a
        // second add of the same account is (rightly) refused as a duplicate.
        val targetId: String? = if (spec == null) {
            store.currentId()
        } else {
            val before = store.accounts()
            duplicateOf(before, spec)?.let {
                return failure(
                    "an account with the same endpoint, username and protocol is already there " +
                        "(id=${it.id}, name=\"${it.label()}\"). Refusing to add a homonym and refusing " +
                        "to overwrite it. To pose identities or settings on it, send a spec with no " +
                        "\"account\" block. To start over: adb shell pm clear app.sterna.test",
                )
            }
            // The one add path: the ViewModel's own entry points, then its state. No probe, no
            // persist/prime lambdas, no store write of our own.
            val vm = withContext(Dispatchers.Main) { ConnectViewModel(app) }
            withContext(Dispatchers.Main) {
                when (spec.mode) {
                    Mode.JMAP ->
                        vm.connect(server = spec.server, username = spec.username, password = spec.password, accountName = spec.accountName)
                    Mode.JMAP_AUTO ->
                        vm.connectAuto(email = spec.username, password = spec.password, accountName = spec.accountName)
                    Mode.IMAP ->
                        vm.connectImap(username = spec.username, password = spec.password, accountName = spec.accountName, imapHost = spec.imapHost, imapPort = spec.imapPort, imapSecurity = spec.imapSecurity, smtpHost = spec.smtpHost, smtpPort = spec.smtpPort, smtpSecurity = spec.smtpSecurity)
                }
            }
            val outcome = vm.state.first { it !is ConnectState.Idle && it !is ConnectState.Connecting && it !is ConnectState.Discovering }
            when (outcome) {
                is ConnectState.Connected -> Unit
                is ConnectState.Error -> return failure("the connect flow refused it: ${outcome.message}")
                is ConnectState.NeedsServer -> return failure(
                    "autodiscovery found no server for ${spec.username}; use mode \"jmap\" with an explicit server",
                )
                is ConnectState.AwaitingApproval -> return failure(
                    "the flow went to OAuth device approval, which needs a browser — the bench cannot pose that",
                )
                else -> return failure("unexpected connect state: $outcome")
            }
            val fresh = store.accounts().filter { new -> before.none { it.id == new.id } }
            (fresh.firstOrNull { it.loginId == null } ?: fresh.firstOrNull())?.id
                ?: return failure("the connect flow reported success but no new account is in the store")
        }

        if (identitiesRequest != null && identitiesRequest.length() > 0 && targetId == null) {
            return failure(
                "identities were asked for, but the spec has no \"account\" block and the device has " +
                    "no current account to put them on",
            )
        }

        // -- the writes ------------------------------------------------------------------------------
        val posedIdentities = targetId?.let { poseIdentities(app, it, identitiesRequest, problems) }.orEmpty()
        val settingsBackup = settingsRequest?.let {
            SettingsBackupCodec.decode(it.toString())
                ?: return failure("the settings block is not a valid Sterna backup (SettingsBackupCodec refused it)")
        }
        settingsBackup?.let { app.container.settingsRepository.restoreBackup(it) }

        // -- the verdict, read back off the device, never from "we called the setter" -----------------
        val stored = targetId?.let { id ->
            store.account(id) ?: return failure("account $id is not in the store any more")
        }
        if (stored != null && spec != null) {
            // The account's OWN fields, compared to what was asked. Printing them was not enough:
            // a wrong port reads perfectly plausible in a report nobody diffs by eye, and this is
            // the sixteen-gesture IMAP form the whole branch exists for.
            problems += accountMismatches(spec, stored)
        }
        if (stored != null) {
            val storedIdentityIds = stored.identities.map { it.id }
            posedIdentities.forEach { identity ->
                if (identity.id !in storedIdentityIds) {
                    problems += "identity \"${identity.id}\" was posed but reads back missing"
                }
            }
            val wantedDefault = defaultIdentityId(identitiesRequest)
            if (wantedDefault != null && stored.defaultIdentityId != wantedDefault) {
                problems += "default identity reads back \"${stored.defaultIdentityId}\", asked \"$wantedDefault\""
            }
        }
        val manual = mutableListOf<String>()
        if (settingsRequest != null) {
            val snapshot = JSONObject(SettingsBackupCodec.encode(app.container.settingsRepository.snapshotBackup()))
            problems += settingsMismatches(settingsRequest, snapshot)
            NOT_APPLIED_BY_RESTORE.forEach { key ->
                if (settingsRequest.has(key) && settingsRequest.opt(key) != JSONObject.NULL) {
                    manual += "$key (restoreBackup does not apply it — it is NOT posed; set it by hand)"
                }
            }
        }

        val report = readBack(app, note = targetId?.let { "provisioned account $it" } ?: "settings only")
        // ⛔ `manual` COUNTS AGAINST `ok`. Asking for something this receiver cannot pose is not a
        // success: the state a script would believe in would not be the state of the device. A
        // bench spec simply does not carry those keys; one that does learns it loudly.
        report.put("ok", problems.isEmpty() && manual.isEmpty())
        report.put("accountId", targetId ?: JSONObject.NULL)
        report.put("problems", JSONArray(problems))
        report.put("manual", JSONArray(manual))
        report.put(
            "summary",
            when {
                problems.isNotEmpty() ->
                    "${problems.size} thing(s) did not read back: ${problems.first()}"
                manual.isNotEmpty() ->
                    "${manual.size} requested setting(s) this receiver cannot pose: ${manual.first()}"
                else -> "posed ${stored?.label() ?: "settings"}${targetId?.let { " ($it)" }.orEmpty()}"
            },
        )
        return report
    }

    /**
     * The requested account fields that do NOT read back off the stored account. Only what the
     * spec actually asked is compared: a blank server (autodiscovery resolves it) or the IMAP
     * endpoint of a JMAP spec are not claims, so they are not checked.
     *
     * Safe as exact equality because the store only ever trims these on the way in, and the inbox
     * metadata write leaves the account name alone (its `accountName` parameter is unused).
     */
    private fun accountMismatches(spec: AccountSpec, stored: StoredAccount): List<String> {
        val out = mutableListOf<String>()
        fun check(field: String, asked: Any, read: Any) {
            if (asked.toString() != read.toString()) out += "account \"$field\": asked $asked, device reads $read"
        }
        check("protocol", spec.protocol.name, stored.protocol.name)
        check("username", spec.username.trim(), stored.username)
        if (spec.accountName.isNotBlank()) check("accountName", spec.accountName.trim(), stored.accountName)
        if (spec.mode == Mode.IMAP) {
            check("imapHost", spec.imapHost.trim(), stored.imapHost)
            check("imapPort", spec.imapPort, stored.imapPort)
            check("imapSecurity", spec.imapSecurity.name, stored.imapSecurity.name)
            check("smtpHost", spec.smtpHost.trim(), stored.smtpHost)
            check("smtpPort", spec.smtpPort, stored.smtpPort)
            check("smtpSecurity", spec.smtpSecurity.name, stored.smtpSecurity.name)
        } else if (spec.server.isNotBlank()) {
            check("server", spec.server.trim(), stored.server)
        }
        return out
    }

    /** The complaint about keys [o] carries that [allowed] does not name, or null when it has none. */
    private fun unknownKeys(o: JSONObject, allowed: Set<String>, what: String): String? {
        val unknown = o.keys().asSequence().filterNot { it in allowed }.sorted().toList()
        if (unknown.isEmpty()) return null
        // Key NAMES only — never their values, one of which is the password.
        return "unknown key(s) in $what: ${unknown.joinToString(", ")}. Accepted: " +
            "${allowed.sorted().joinToString(", ")}. Refusing rather than silently falling back to " +
            "a default that looks plausible in the report."
    }

    /**
     * Reads the device's state; writes nothing. Passwords are never read, let alone printed.
     *
     * What `ok` means HERE, and only here: the read itself completed. It is not a claim about the
     * device's contents — zero accounts is an honest `ok: true`. It is not a constant either: any
     * throw on the way lands in onReceive's catch, which reports `ok: false`. A PROVISION run
     * overwrites this field with a verdict that compares what it asked for to what it read.
     */
    private suspend fun readBack(app: Application, note: String?): JSONObject {
        val store = app.container.accountStore
        val accounts = JSONArray()
        store.accounts().forEach { a ->
            accounts.put(
                JSONObject()
                    .put("id", a.id)
                    .put("accountName", a.accountName)
                    .put("username", a.username)
                    .put("protocol", a.protocol.name)
                    .put("server", a.server)
                    .put("imap", "${a.imapHost}:${a.imapPort}/${a.imapSecurity.name}")
                    .put("smtp", "${a.smtpHost}:${a.smtpPort}/${a.smtpSecurity.name}")
                    .put("loginId", a.loginId ?: JSONObject.NULL)
                    .put("current", a.id == store.currentId())
                    .put("importPending", a.importPending)
                    .put("notificationsEnabled", a.notificationsEnabled)
                    .put("defaultIdentityId", a.defaultIdentityId ?: JSONObject.NULL)
                    .put("manualIdentities", JSONArray(a.identities.map { "${it.id}|${it.display()}" }))
                    .put("serverIdentities", JSONArray(a.serverIdentities.map { "${it.id}|${it.display()}" })),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("note", note ?: "read-only")
            .put("accounts", accounts)
            .put("settings", JSONObject(SettingsBackupCodec.encode(app.container.settingsRepository.snapshotBackup())))
            .put("summary", "${accounts.length()} account(s) on device")
    }

    // -- identities -------------------------------------------------------------------------------

    /**
     * Merges the requested identities into the account's MANUAL identity list (the one the server
     * does not rewrite on connect) and applies the chosen default. Existing entries are kept;
     * an entry whose id repeats is replaced by the requested one.
     */
    private fun poseIdentities(
        app: Application,
        accountId: String,
        requested: JSONArray?,
        problems: MutableList<String>,
    ): List<StoredIdentity> {
        if (requested == null || requested.length() == 0) return emptyList()
        val store = app.container.accountStore
        val posed = (0 until requested.length()).map { i ->
            val o = requested.getJSONObject(i)
            val id = o.optString("id").ifBlank { o.optString("email") }
            if (id.isBlank()) problems += "an identity entry has neither id nor email"
            StoredIdentity(
                id = id,
                name = o.optString("name"),
                email = o.optString("email"),
                signature = o.optString("signature"),
                signatureHtml = o.optString("signatureHtml"),
            )
        }
        val existing = store.account(accountId)?.identities.orEmpty()
        val merged = existing.filterNot { kept -> posed.any { it.id == kept.id } } + posed
        store.setIdentities(accountId, merged)
        defaultIdentityId(requested)?.let { store.setDefaultIdentity(accountId, it) }
        return posed
    }

    private fun defaultIdentityId(requested: JSONArray?): String? {
        if (requested == null) return null
        for (i in 0 until requested.length()) {
            val o = requested.getJSONObject(i)
            if (o.optBoolean("default")) return o.optString("id").ifBlank { o.optString("email") }
        }
        return null
    }

    // -- the spec ---------------------------------------------------------------------------------

    private enum class Mode { JMAP, JMAP_AUTO, IMAP }

    private class AccountSpec(
        val mode: Mode,
        val server: String,
        val username: String,
        val password: String,
        val accountName: String,
        val imapHost: String,
        val imapPort: Int,
        val imapSecurity: ConnectionSecurity,
        val smtpHost: String,
        val smtpPort: Int,
        val smtpSecurity: ConnectionSecurity,
    ) {
        val protocol: MailProtocol get() = if (mode == Mode.IMAP) MailProtocol.IMAP else MailProtocol.JMAP

        /** What identifies the endpoint for the duplicate check: the IMAP host, or the JMAP server. */
        val endpoint: String get() = if (mode == Mode.IMAP) imapHost else server
    }

    private fun parseAccount(o: JSONObject): AccountSpec {
        val mode = when (val raw = o.optString("mode", "jmap").lowercase()) {
            "jmap" -> Mode.JMAP
            "jmap-auto", "auto" -> Mode.JMAP_AUTO
            "imap" -> Mode.IMAP
            else -> error("unknown account mode \"$raw\" (jmap | jmap-auto | imap)")
        }
        val password = o.optString("password")
        require(password.isNotEmpty()) { "the spec file carries no password for this account" }
        return AccountSpec(
            mode = mode,
            server = o.optString("server"),
            username = o.optString("username"),
            password = password,
            accountName = o.optString("accountName"),
            imapHost = o.optString("imapHost"),
            imapPort = o.optInt("imapPort", 993),
            imapSecurity = security(o.optString("imapSecurity"), ConnectionSecurity.TLS),
            smtpHost = o.optString("smtpHost"),
            smtpPort = o.optInt("smtpPort", 587),
            smtpSecurity = security(o.optString("smtpSecurity"), ConnectionSecurity.STARTTLS),
        )
    }

    private fun security(raw: String, fallback: ConnectionSecurity): ConnectionSecurity =
        if (raw.isBlank()) fallback else ConnectionSecurity.valueOf(raw.uppercase())

    /**
     * The already-present account this spec would duplicate, if any. Matched on protocol +
     * username + endpoint, the same triple the settings importer dedupes on.
     *
     * A blank endpoint (autodiscovery, where the server is only known after the probe) matches ANY
     * stored endpoint: erring towards "refuse" costs a re-read, while erring the other way puts a
     * second indistinguishable copy of the persona on the bench.
     */
    private fun duplicateOf(existing: List<StoredAccount>, spec: AccountSpec): StoredAccount? =
        existing.firstOrNull {
            it.protocol == spec.protocol &&
                it.username.equals(spec.username, ignoreCase = true) &&
                (spec.endpoint.isBlank() || endpointOf(it).equals(spec.endpoint, ignoreCase = true))
        }

    private fun endpointOf(a: StoredAccount): String =
        if (a.protocol == MailProtocol.IMAP) a.imapHost else a.server

    // -- settings read-back -------------------------------------------------------------------------

    /**
     * Every requested settings key whose value does NOT read back off the device. Generic on
     * purpose — it walks the keys the spec actually asked for instead of a hand-copied field list,
     * so a field added to `SettingsBackup` tomorrow is covered without touching this file, and a
     * misspelled key shows up as a mismatch instead of being silently dropped by the decoder.
     */
    private fun settingsMismatches(requested: JSONObject, snapshot: JSONObject): List<String> {
        val out = mutableListOf<String>()
        for (key in requested.keys()) {
            if (key == "version" || key == "accounts" || key in NOT_APPLIED_BY_RESTORE) continue
            val want = requested.opt(key)?.takeIf { it != JSONObject.NULL } ?: continue
            val got = snapshot.opt(key)?.takeIf { it != JSONObject.NULL }
            if (!sameJson(want, got)) out += "setting \"$key\": asked $want, device reads $got"
        }
        return out
    }

    private fun sameJson(a: Any?, b: Any?): Boolean = when {
        a is JSONArray && b is JSONArray ->
            (0 until a.length()).map { a.get(it).toString() }.sorted() ==
                (0 until b.length()).map { b.get(it).toString() }.sorted()
        a == null || b == null -> a == null && b == null
        else -> a.toString() == b.toString()
    }

    // -- reporting ---------------------------------------------------------------------------------

    private fun failure(message: String, cause: Throwable? = null): JSONObject {
        cause?.let { Log.w(TAG, "bench provisioning failed", it) }
        return JSONObject()
            .put("ok", false)
            .put("problems", JSONArray(listOf(message)))
            .put("summary", message)
    }

    private fun publish(report: JSONObject, outPath: String?, pending: PendingResult) {
        try {
            val ok = report.optBoolean("ok")
            val text = report.toString(2)
            // One log line per report line: logcat truncates a long single entry.
            Log.i(TAG, if (ok) "RESULT ok" else "RESULT FAILED")
            text.lines().forEach { Log.i(TAG, it) }
            // ⛔ THE ONLY FILE THIS RECEIVER MAY TOUCH. `out` arrives on an exported,
            // permission-less broadcast and writeText TRUNCATES, so an unjudged path lets any app
            // on the device have a file of its choosing replaced by this report — through a
            // receiver whose KDoc above promises it removes nothing. The judgement is
            // isBenchOutPathAllowed() in src/benchShared/kotlin, where a unit test can execute it.
            // A refusal only skips the write: the log lines above and the broadcast result below
            // still carry the verdict.
            val out = outPath.orEmpty()
            when {
                out.isBlank() -> Unit
                !isBenchOutPathAllowed(out) -> Log.e(
                    TAG,
                    "refusing to write the report to $out: the out file must be a plain file " +
                        "under $BENCH_OUT_DIR (e.g. ${BENCH_OUT_DIR}bench.out), pre-created " +
                        "world-writable from the shell. Writing truncates, so this receiver will " +
                        "not touch a path outside it; the report above and the broadcast result " +
                        "still carry the verdict",
                )
                else -> runCatching { File(out).writeText(text) }.onFailure {
                    Log.e(
                        TAG,
                        "could not write $out (pre-create it world-writable from the shell); " +
                            "the report above and the broadcast result still carry the verdict",
                        it,
                    )
                }
            }
            // Ordered-broadcast result: what `am broadcast` prints on the shell's stdout.
            runCatching {
                pending.resultCode = if (ok) 0 else 1
                pending.resultData = report.optString("summary")
            }
        } finally {
            // Exactly once on every path: never finishing leaks the broadcast and gets the process
            // killed; finishing twice throws.
            pending.finish()
        }
    }

    private companion object {
        const val TAG = "BenchProvision"
        const val ACTION_PROVISION = "app.sterna.test.bench.PROVISION"
        const val ACTION_READBACK = "app.sterna.test.bench.READBACK"
        const val EXTRA_SPEC = "spec"
        const val EXTRA_OUT = "out"

        /**
         * Hard deadline for the whole run. The system gives a background broadcast ~60 s before it
         * kills the receiver, and a kill produces NO verdict at all — which is indistinguishable
         * from a success unless we beat it to it. Adding an account is a network round-trip plus a
         * first inbox page, seconds on the bench's own server.
         */
        const val DEADLINE_MS = 45_000L

        /**
         * Fields of a settings backup that `SettingsRepository.restoreBackup` does not apply.
         * Asking for one is reported under `manual` AND makes the run fail: this receiver does not
         * pose them, so calling that a success would describe a device that does not exist.
         */
        val NOT_APPLIED_BY_RESTORE = setOf("language", "pushAllAccounts")

        // Whitelists. Every key of the spec file is named here or the run is refused — see
        // unknownKeys(). `settings` is not whitelisted key by key on purpose: it is a
        // SettingsBackup, whose own decoder owns that vocabulary, and a key it does not know
        // surfaces as a read-back mismatch instead.
        val ROOT_KEYS = setOf("account", "identities", "settings")
        val ACCOUNT_KEYS = setOf(
            "mode", "server", "username", "password", "accountName",
            "imapHost", "imapPort", "imapSecurity", "smtpHost", "smtpPort", "smtpSecurity",
        )
        val IDENTITY_KEYS = setOf("id", "name", "email", "signature", "signatureHtml", "default")
    }
}
