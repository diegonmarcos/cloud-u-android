package app.sterna.ui.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.account.SyncWindow
import app.sterna.core.data.account.identitiesToCreate
import app.sterna.core.data.account.mayCreateIdentities
import app.sterna.core.data.account.pgpPublicKeyCacheValue
import app.sterna.core.data.account.subscriptionSyncOnToggle
import app.sterna.core.data.account.syncWindowChanged
import app.sterna.core.data.mail.OAuthDeniedException
import app.sterna.core.data.mail.OAuthProvider
import app.sterna.core.data.pgp.PgpResult
import app.sterna.pgp.PgpProviders
import app.sterna.push.NewMailNotifier
import app.sterna.push.Notifications
import app.sterna.push.PushController
import app.sterna.ui.connect.oauthFailureMessage
import app.sterna.ui.inbox.prunedFreshness
import app.sterna.ui.inbox.prunedView
import app.sterna.widget.RecentMailWidgetDraw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backs the Accounts list and per-account detail screens in Settings. */
class AccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val storage = application.container.storageRepository
    private val mail = application.container.mailRepository

    /** Outlives this screen: `viewModelScope` belongs to the `NavBackStackEntry` and dies with the
     *  back gesture, so work that must survive it runs here (#174). */
    private val appScope = application.container.appScope
    private val pgp = application.container.pgpEngine
    private val settings = application.container.settingsRepository

    /** The "Separator line above the signature" setting (#90), so the identity editor's preview
     *  shows what the composer will write. */
    val signatureDelimiter: StateFlow<Boolean> = settings.signatureDelimiter
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Live from the store, not a snapshot: this screen must follow writes it did not make, such as
     *  discovery adding or pruning a shared account under a login (#31). */
    val accounts: StateFlow<List<StoredAccount>> = store.accountsFlow

    private val _currentId = MutableStateFlow(store.currentId())
    val currentId = _currentId.asStateFlow()

    /** Imported accounts still awaiting their one-time sign-in; derived from [accounts]. */
    val pendingImportAccounts: StateFlow<List<StoredAccount>> = accounts
        .map { list -> list.filter { it.importPending && store.credentials(it.id) == null } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.pendingImportAccounts())

    /** Whether an account has a stored credential (false == inert, awaiting sign-in). */
    fun isSignedIn(id: String): Boolean = store.credentials(id) != null

    /** Swipe-dismiss: disconnect and remove the imported account entirely. Undoable via
     *  [restoreImport]. */
    fun dismissImport(id: String) {
        store.remove(id)
        refresh()
    }

    fun restoreImport(account: StoredAccount) {
        store.readdImportedAccount(account)
        refresh()
    }

    private val _cacheCount = MutableStateFlow(0)
    val cacheCount = _cacheCount.asStateFlow()

    sealed interface ConnTest {
        data object Idle : ConnTest
        data object Testing : ConnTest
        data object Ok : ConnTest
        data class Failed(val message: String) : ConnTest
    }

    private val _connTest = MutableStateFlow<ConnTest>(ConnTest.Idle)
    val connTest = _connTest.asStateFlow()

    fun clearConnTest() { _connTest.value = ConnTest.Idle }

    /** Try the (possibly edited) server settings without saving. A blank password falls back to the
     *  stored one, so testing after only changing the host works. */
    fun testConnection(
        accountId: String,
        server: String,
        username: String,
        password: String,
        isImap: Boolean,
        imapHost: String,
        imapPort: Int?,
        imapSecurity: ConnectionSecurity,
        smtpHost: String,
        smtpPort: Int?,
        smtpSecurity: ConnectionSecurity,
    ) {
        _connTest.value = ConnTest.Testing
        viewModelScope.launch {
            val stored = store.credentials(accountId)
            val pw = password.ifBlank { stored?.password.orEmpty() }
            val credentials = AccountCredentials(
                server = server.trim(),
                username = username.trim(),
                password = pw,
                id = accountId,
                protocol = if (isImap) MailProtocol.IMAP else MailProtocol.JMAP,
                imap = if (isImap) MailEndpoint(imapHost.trim(), imapPort ?: 0, imapSecurity) else null,
                smtp = if (isImap) MailEndpoint(smtpHost.trim(), smtpPort ?: 0, smtpSecurity) else null,
                // Keep the stored auth mode so API-token accounts test with Bearer, not Basic.
                authType = store.account(accountId)?.authType ?: AuthType.BASIC,
                // Carry the stored OAuth tokens: without them an OAuth account tests with an empty
                // password and always fails, a false negative (audit A5).
                oauth = stored?.oauth,
            )
            _connTest.value = mail.testConnection(credentials).fold(
                onSuccess = { ConnTest.Ok },
                onFailure = { ConnTest.Failed(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    /** Re-read the selected account after any change ([accounts] itself follows the store). */
    fun refresh() {
        _currentId.value = store.currentId()
    }

    fun account(id: String): StoredAccount? = store.account(id)

    /** Whether this account is watched for new mail (the current account, or push-for-all is on).
     *  An unwatched account's notifications toggle is inert, so the UI says so under it. */
    fun isWatched(id: String): Boolean =
        PushController.isWatched(id, store.currentId(), store.pushAllAccounts())

    fun syncWindow(id: String): SyncWindow = store.syncWindow(id)

    /**
     * Write the account's "Messages to sync" window, dropping its sync cursors when it actually
     */
    fun setSyncWindow(id: String, window: SyncWindow) {
        val changed = syncWindowChanged(store.syncWindow(id), window)
        store.setSyncWindow(id, window)
        if (changed) mail.dropSyncCursors(id)
        refresh()
    }

    /**
     * Set the account's accent colour (ARGB), or null for auto, and redraw the home screen, which
     * has no timer (`updatePeriodMillis="0"`) and would otherwise keep the old one.
     * The arrival trigger does not cover it: `RecentMailWidgetPush.redraws` carries no colour.
     * `forget()` before `refresh()`: on timeout the row factory falls back to the last snapshot
     * it read, and that one holds the old colour.
     */
    fun setColor(id: String, color: Int?) {
        store.setColor(id, color)
        refresh()
        viewModelScope.launch {
            RecentMailWidgetDraw.forget()
            RecentMailWidgetDraw.refresh(getApplication())
        }
    }

    /** Enable/disable new-mail notifications for an account; re-arm push to apply. */
    fun setNotificationsEnabled(id: String, enabled: Boolean) {
        store.setNotificationsEnabled(id, enabled)
        if (enabled) {
            // The account's baselines froze while it was excluded from every diff pass;
            // drop them so re-enabling reseeds silently instead of bursting weeks of mail.
            NewMailNotifier.clear(getApplication(), id)
        }
        refresh()
        PushController.apply(getApplication(), userInitiated = true)
    }

    /** The changing half of what the relay-address block draws, read in one pass so its two lines
     *  cannot describe two different moments. Not a flow: nothing here is observable. */
    data class RelayAddress(
        /** The user asked for an address (it may not have arrived yet). */
        val requested: Boolean,
        /** The published address, or null while none has arrived. */
        val endpoint: String?,
    )

    /**
     * What the relay-address block should draw for this account right now.
     */
    fun relayAddress(id: String): RelayAddress {
        val up = getApplication<Application>().container.unifiedPushManager
        return RelayAddress(
            requested = up.relayRequested(id),
            endpoint = up.relayEndpoint(id),
        )
    }

    /** Is any UnifiedPush app installed? Read once per visit, never on the block's tick. */
    fun distributorInstalled(): Boolean =
        getApplication<Application>().container.unifiedPushManager.distributorInstalled()

    /** The user asked for a relay address on this IMAP account. It arrives asynchronously from the
     *  distributor, so the block says it is waiting until re-entering the screen settles it. */
    fun requestRelayAddress(id: String) {
        val credentials = store.credentials(id) ?: return
        getApplication<Application>().container.unifiedPushManager.requestRelayAddress(credentials)
    }

    /** The user withdrew the relay address. Talks to no server (a relay account never held a
     *  PushSubscription) and hands the account back to its own IMAP connection. */
    fun dropRelayAddress(id: String) {
        val credentials = store.credentials(id) ?: return
        getApplication<Application>().container.unifiedPushManager.dropRelayAddress(credentials)
    }

    /**
     * Turn the app's Sent-folder copy on/off for an account (IMAP only). Written at the toggle, not
     * at Save: a switch that waited for Save would lose the choice to a back gesture.
     */
    fun setUploadSentCopy(id: String, enabled: Boolean) {
        store.setUploadSentCopy(id, enabled)
        refresh()
    }

    /**
     * Turn "show only subscribed folders" on/off for an account (#174). Written at the toggle.
     */
    fun setShowOnlySubscribedFolders(id: String, enabled: Boolean) {
        store.setShowOnlySubscribedFolders(id, enabled)
        refresh()
        val sync = subscriptionSyncOnToggle(id, enabled) ?: return
        appScope.launch {
            runCatching { mail.refreshFolderList(sync.accountId, sync.onlySubscribed) }
                .onFailure { android.util.Log.w("SternaAccounts", "the subscribed-folders sync failed; every folder stays in the drawer", it) }
        }
    }

    fun loadCacheCount(id: String) {
        viewModelScope.launch { _cacheCount.value = storage.accountMessageCount(id) }
    }

    fun clearAccountCache(id: String) {
        viewModelScope.launch {
            storage.clearAccountCache(id)
            mail.resetSyncState()
            _cacheCount.value = storage.accountMessageCount(id)
        }
    }

    fun switchTo(id: String) {
        store.setCurrent(id)
        refresh()
    }

    /**
     * One identity this Save asked the server to create, and the server refused (#172).
     */
    data class IdentityNotCreated(val accountId: String, val email: String, val detail: String)

    private val _identitiesNotCreated = MutableStateFlow<List<IdentityNotCreated>>(emptyList())
    val identitiesNotCreated: StateFlow<List<IdentityNotCreated>> = _identitiesNotCreated.asStateFlow()

    /**
     * Persist edits. A blank [password] keeps the existing one.
     */
    fun save(
        id: String,
        accountName: String,
        server: String,
        username: String,
        password: String,
        signature: String? = null,
        identities: List<StoredIdentity>? = null,
        defaultIdentityId: String? = null,
        imapHost: String? = null,
        imapPort: Int? = null,
        imapSecurity: ConnectionSecurity? = null,
        smtpHost: String? = null,
        smtpPort: Int? = null,
        smtpSecurity: ConnectionSecurity? = null,
    ) {
        // Read before anything is written: what the account already held is what says which
        // identities this gesture is adding (see [identitiesToCreate]).
        val before = store.account(id)
        // A new Save starts from a blank page: last time's refusals are not this gesture's answer.
        _identitiesNotCreated.value = emptyList()
        store.updateAccount(
            id, server = server, username = username, accountName = accountName, signature = signature,
            imapHost = imapHost, imapPort = imapPort, imapSecurity = imapSecurity,
            smtpHost = smtpHost, smtpPort = smtpPort, smtpSecurity = smtpSecurity,
        )
        if (identities != null) store.setIdentities(id, identities)
        store.setDefaultIdentity(id, defaultIdentityId)
        if (password.isNotBlank()) {
            store.updatePassword(id, password)
            // Saving a password signs in an inert BASIC import — take it off the pending list.
            store.setImportPending(id, false)
        }
        refresh()
        createIdentitiesOnServer(id, before, identities)
    }

    /**
     * Tell the server about the identities this Save has just added (`Identity/set`, #172).
     */
    private fun createIdentitiesOnServer(
        id: String,
        before: StoredAccount?,
        identities: List<StoredIdentity>?,
    ) {
        if (identities == null || before == null) return
        if (!mayCreateIdentities(before, identities)) return
        val credentials = store.credentials(id) ?: return
        appScope.launch {
            val server = try {
                mail.serverIdentities(credentials)
            } catch (e: Exception) {
                android.util.Log.w(
                    "SternaAccounts",
                    "could not read the identities the server already holds; nothing was created",
                    e,
                )
                // Nothing is posted, so say which addresses stayed on the phone: the offline
                // candidates are the ones a server holding nothing would have taken.
                for (identity in identitiesToCreate(before, identities, emptyList())) {
                    _identitiesNotCreated.update {
                        it + IdentityNotCreated(id, identity.email.trim(), failureDetail(e.message).ifEmpty { e.javaClass.simpleName })
                    }
                }
                return@launch
            }
            for (identity in identitiesToCreate(before, identities, server)) {
                try {
                    mail.createIdentity(credentials, name = identity.name, email = identity.email.trim())
                } catch (e: Exception) {
                    android.util.Log.w(
                        "SternaAccounts",
                        "the server did not create the identity; it stays local and is not retried",
                        e,
                    )
                    // There is no retry (see the KDoc above), so a refusal nobody reads is an
                    // address that will never reach the server.
                    _identitiesNotCreated.update {
                        it + IdentityNotCreated(id, identity.email.trim(), failureDetail(e.message).ifEmpty { e.javaClass.simpleName })
                    }
                }
            }
        }
    }

    // --- Sign in an inert (imported) account via Microsoft OAuth device flow ---

    sealed interface AccountSignIn {
        data object Idle : AccountSignIn
        data object Starting : AccountSignIn
        data class Approval(
            val userCode: String,
            val verificationUri: String,
            val verificationUriComplete: String?,
        ) : AccountSignIn
        data object Connecting : AccountSignIn
        data class Failed(val message: String, val offerAppPassword: Boolean) : AccountSignIn
        data object Success : AccountSignIn
    }

    private val _accountSignIn = MutableStateFlow<AccountSignIn>(AccountSignIn.Idle)
    val accountSignIn: StateFlow<AccountSignIn> = _accountSignIn.asStateFlow()
    private var accountSignInJob: Job? = null

    fun startAccountOAuth(accountId: String) {
        val account = store.account(accountId) ?: return
        val provider = OAuthProvider.forImapHost(account.imapHost) ?: return
        if (_accountSignIn.value != AccountSignIn.Idle && _accountSignIn.value !is AccountSignIn.Failed) return
        _accountSignIn.value = AccountSignIn.Starting
        accountSignInJob = viewModelScope.launch {
            val result = mail.runProviderDeviceFlow(provider) { device ->
                _accountSignIn.value = AccountSignIn.Approval(
                    device.userCode, device.verificationUri, device.verificationUriComplete,
                )
            }
            result.fold(
                onSuccess = { tokens ->
                    _accountSignIn.value = AccountSignIn.Connecting
                    runCatching { mail.signInImportedOAuth(accountId, provider, tokens) }.fold(
                        onSuccess = {
                            store.setImportPending(accountId, false)
                            refresh()
                            _accountSignIn.value = AccountSignIn.Success
                        },
                        onFailure = { e ->
                            _accountSignIn.value = AccountSignIn.Failed(
                                e.message ?: "sign-in failed", offerAppPassword = true,
                            )
                        },
                    )
                },
                onFailure = { e ->
                    val msg = if (e is OAuthDeniedException) {
                        oauthFailureMessage(getApplication(), e.failure)
                    } else {
                        e.message ?: "sign-in failed"
                    }
                    _accountSignIn.value = AccountSignIn.Failed(msg, offerAppPassword = true)
                },
            )
        }
    }

    /** Cancel an in-progress device flow; the account stays inert and retryable. */
    fun cancelAccountOAuth() {
        accountSignInJob?.cancel()
        accountSignInJob = null
        _accountSignIn.value = AccountSignIn.Idle
    }

    fun resetAccountSignIn() { _accountSignIn.value = AccountSignIn.Idle }

    /** OAuth→app-password fallback: drop OAuth material so a password field signs the account in. */
    fun switchAccountToAppPassword(accountId: String) {
        accountSignInJob?.cancel()
        accountSignInJob = null
        store.convertToBasicAuth(accountId)
        refresh()
        _accountSignIn.value = AccountSignIn.Idle
    }

    // --- OpenPGP (OpenKeychain) ---

    /** Whether an OpenPGP provider is installed and bindable. */
    private val _pgpAvailable = MutableStateFlow(false)
    val pgpAvailable = _pgpAvailable.asStateFlow()

    sealed interface PgpSetup {
        data object Idle : PgpSetup
        data class NeedsInteraction(val pendingIntent: PendingIntent) : PgpSetup
        data class Failed(val message: String?) : PgpSetup
    }

    private val _pgpSetup = MutableStateFlow<PgpSetup>(PgpSetup.Idle)
    val pgpSetup = _pgpSetup.asStateFlow()

    /** The OpenPGP apps installed right now; refreshed on entering the screen, never cached for the
     *  process lifetime. */
    private val _pgpProviders = MutableStateFlow<List<String>>(emptyList())
    val pgpProviders = _pgpProviders.asStateFlow()

    /** The one Sterna will actually bind: the stored choice while it is still installed, the default
     * otherwise ([PgpProviders.resolve]). Naming the stored choice would print an uninstalled
     *  app. */
    private val _pgpProviderInUse = MutableStateFlow<String?>(null)
    val pgpProviderInUse = _pgpProviderInUse.asStateFlow()

    /**
     * Both values are computed first and published together, with no suspension between the two
     */
    fun refreshPgpAvailable() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { PgpProviders.installed(getApplication()) }
            val stored = settings.pgpProvider.first()
            val inUse = PgpProviders.resolve(installed, stored)
            _pgpProviders.value = installed
            _pgpProviderInUse.value = inUse
            _pgpAvailable.value = pgp.isAvailable()
            PgpProviders.providerToPin(stored, inUse)?.let { settings.setPgpProvider(it) }
        }
    }

    /**
     * Persist [packageName] as the OpenPGP app to use, and — only when that is a real switch of
     */
    fun setPgpProvider(packageName: String) {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { PgpProviders.installed(getApplication()) }
            val inUse = PgpProviders.resolve(installed, settings.pgpProvider.first())
            settings.setPgpProvider(packageName)
            _pgpProviderInUse.value = packageName
            if (PgpProviders.switchErasesKeys(inUse, packageName)) {
                store.clearPgpSignKeys()
                refresh()
            }
            _pgpAvailable.value = pgp.isAvailable()
        }
    }

    fun clearPgpSetup() { _pgpSetup.value = PgpSetup.Idle }

    /**
     * Open the provider's sign-key chooser for this account. On success the key is persisted and
     * PGP enabled; [PgpSetup.NeedsInteraction] asks the UI to run the pending intent and call back.
     */
    fun choosePgpKey(accountId: String, interactionResult: Intent? = null) {
        val account = store.account(accountId) ?: return
        viewModelScope.launch {
            when (val result = pgp.getSignKeyId(account.username, interactionResult)) {
                is PgpResult.Success -> {
                    // The public half, cached alongside the id (StoredAccount.pgpPublicKey).
                    // Best effort: the key id is persisted and the gesture succeeds either way —
                    // this cache is one the composer backfills.
                    val publicKey = when (val key = pgp.getPublicKey(result.value, account.username)) {
                        is PgpResult.Success -> pgpPublicKeyCacheValue(key.value)
                        else -> ""
                    }
                    store.setPgp(
                        accountId,
                        enabled = true,
                        signKeyId = result.value,
                        publicKey = publicKey,
                        encryptByDefault = account.pgpEncryptByDefault,
                    )
                    refresh()
                    _pgpSetup.value = PgpSetup.Idle
                }
                is PgpResult.UserInteractionRequired ->
                    _pgpSetup.value = PgpSetup.NeedsInteraction(result.pendingIntent)
                is PgpResult.Error -> _pgpSetup.value = PgpSetup.Failed(result.message)
                PgpResult.NotAvailable -> _pgpSetup.value = PgpSetup.Failed(null)
            }
        }
    }

    /** Toggle PGP off (keeps the chosen key), or update encrypt-by-default. */
    fun setPgp(accountId: String, enabled: Boolean, encryptByDefault: Boolean) {
        val account = store.account(accountId) ?: return
        // The key id does not change here, so its cached public key stays valid and is handed
        // straight back: this is the toggle, not a key choice.
        store.setPgp(
            accountId,
            enabled = enabled,
            signKeyId = account.pgpSignKeyId,
            publicKey = account.pgpPublicKey,
            encryptByDefault = encryptByDefault,
        )
        refresh()
    }

    /**
     * Sign out and purge that account's cached mail and attachments. Signing out a login cascades to
     * the JMAP sub-accounts linked to it (#31): they share its credential and cannot outlive it.
     */
    fun signOut(id: String) {
        val app = getApplication<Application>()
        // Everything this sign-out will remove, resolved BEFORE removal so UnifiedPush teardown
        // (which needs the credentials) and the cache purge can run per removed account.
        val target = store.account(id)
        val toRemove = buildList {
            add(id)
            if (target != null && !target.isLinked) addAll(store.linkedAccounts(id).map { it.id })
        }.distinct()
        store.allCredentials().filter { it.id in toRemove }.forEach {
            app.container.unifiedPushManager.teardown(it)
        }
        // This line is what stops a sync in flight, and it must stay above the purge below: what
        // ends that sync is the account leaving the store (`checkAccountStillConfigured`). Purging
        // first reopens the window, and the orphan sweep below would run too early (#121).
        val removed = store.removeCascading(id).ifEmpty { toRemove }
        removed.forEach { NewMailNotifier.clear(app, it) }
        // The banners, which the line above does not touch. Strictly `removed`, never every
        // account, and not from inside NewMailNotifier.clear, whose callers re-seed live accounts.
        removed.forEach { Notifications.cancelAccount(app, it) }
        // The two preferences filed under an account but living outside the account blob, which
        // removeCascading does not touch: the last view shown (an IMAP folder path in cleartext)
        val removedIds = removed.toSet()
        store.setStoredView(prunedView(store.storedView(), removedIds))
        store.setRefreshFreshness(prunedFreshness(store.refreshFreshness(), removedIds))
        refresh()
        mail.resetSyncState()
        viewModelScope.launch {
            removed.forEach {
                mail.disconnectImap(it)
                storage.purgeAccount(it)
            }
            // Then anything left by an account no longer configured (#121): the per-id purges
            // above only cover what this sign-out removed. Sweeps nothing on an empty store.
            storage.purgeOrphanedAccounts { store.accounts().map { it.id } }
        }
        PushController.apply(app, userInitiated = true)
    }
}

/**
 * Trim an exception's sentence for `R.string.settings_identity_not_created`, whose template already
 */
internal fun failureDetail(raw: String?): String =
    raw.orEmpty().trim().trimEnd { it == '.' || it == '!' || it == '?' || it.isWhitespace() }
