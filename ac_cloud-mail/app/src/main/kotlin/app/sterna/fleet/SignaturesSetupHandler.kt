package app.sterna.fleet

import android.content.Context
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.account.SignatureTransfer
import app.sterna.core.data.account.StoredAccount
import com.diegonmarcos.superapp.fleetconfig.SetupStores

/**
 * The `mail_signatures` store of the fleet setup contract (`<package>.fleetsetup`): every identity's
 * signatures, one key per ADDRESS whose value is that identity's [SignatureTransfer.Entry] as JSON.
 *
 * Not a SharedPreferences file of its own. The signatures already live in the accounts blob that
 * [AccountStore] owns, and the send path reads them there, so this handler is a view onto that one
 * store rather than a second copy to keep in step: what Cloud Account exports is what composing uses,
 * and what it applies is written through [AccountStore.setIdentities], the call the editor saves with.
 * Registered with the app's own [AccountStore] so the screens observing its flow see the write.
 */
class SignaturesSetupHandler(
    private val load: () -> List<StoredAccount>,
    private val save: (accountId: String, StoredAccount) -> Unit,
) : SetupStores.SetupHandler {

    override fun read(ctx: Context, file: String): Map<String, Any?>? = values()

    override fun write(ctx: Context, file: String, set: Map<String, Any?>, remove: Set<String>): Boolean =
        apply(set, remove)

    /** Address -> entry JSON, or null when no identity has a signature yet. */
    internal fun values(): Map<String, Any?>? =
        SignatureTransfer.collect(load())
            .mapValues { SignatureTransfer.encodeEntry(it.value) }
            .ifEmpty { null }

    /** All of [set] and [remove] or nothing: one undecodable value refuses the lot. */
    internal fun apply(set: Map<String, Any?>, remove: Set<String>): Boolean {
        val entries = HashMap<String, SignatureTransfer.Entry>()
        for ((key, v) in set) entries[SignatureTransfer.keyOf(key)] = SignatureTransfer.decodeEntry(v.toString()) ?: return false
        val before = load()
        val (added, _) = SignatureTransfer.apply(before, entries)
        val (after, _) = SignatureTransfer.clear(added, remove)
        for ((old, new) in before.zip(after)) if (old != new) save(new.id, new)
        return true
    }

    companion object {
        fun register(store: AccountStore) {
            SetupStores.register(
                "mail_signatures",
                SignaturesSetupHandler(
                    load = { store.accounts() },
                    save = { id, account -> store.setIdentities(id, account.identities) },
                ),
            )
        }
    }
}
