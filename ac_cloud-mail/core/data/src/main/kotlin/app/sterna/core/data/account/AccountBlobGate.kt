package app.sterna.core.data.account

/** Distinguishes "no accounts" from "the stored blob failed to decode", refusing every write
 *  while the second is true. [encode] is called from HERE, not [AccountStore.saveAccounts],
 *  so a refactor can't skip this gate silently. */
internal class AccountBlobGate(
    private val decode: (String) -> List<StoredAccount>,
    private val encode: (List<StoredAccount>) -> String,
) {

    @Volatile
    private var unreadable = false

    /** Whether the last [read] failed to decode. Diagnostics only — [writeGuarded] decides. */
    val blobUnreadable: Boolean get() = unreadable

    /** Decodes the stored blob. Never throws: runs from [AccountStore]'s constructor. */
    fun read(raw: String?): List<StoredAccount> {
        // Absent key is not a failure, just nothing stored yet; one assignment covers both cases.
        val decoded = if (raw == null) Result.success(emptyList()) else runCatching { decode(raw) }
        unreadable = decoded.isFailure
        return decoded.getOrDefault(emptyList())
    }

    /** Encodes [list] to [write] unless the last read failed to decode. Returns false rather
     *  than throwing: refusing a write loses a setting, throwing would crash for nothing fixable. */
    fun writeGuarded(list: List<StoredAccount>, write: (String) -> Unit): Boolean {
        if (unreadable) return false
        write(encode(list))
        return true
    }

    /** The stored file was legitimately emptied ([AccountStore.clear]): nothing left to protect. */
    fun onStorageWiped() {
        unreadable = false
    }
}
