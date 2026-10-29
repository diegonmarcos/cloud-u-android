package com.diegonmarcos.superapp.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A write waiting for the next tap: the serialized message and whether a blank tag may be formatted. */
data class PendingWrite(val label: String, val message: ByteArray, val format: Boolean)

data class NfcState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val listening: Boolean = false,
    val last: TagInfo? = null,
    val pending: PendingWrite? = null,
    val status: String = "",
    val history: List<ScanEntry> = emptyList(),
)

/**
 * The NFC page's state (#1264): reader mode on the hosting activity while the page is resumed, the last tag, a pending write, and the
 * scan history (SharedPreferences `nfc_history`, the same JSON as the export, capped at [NfcHistory.MAX]).
 */
class NfcController(private val app: Context, private val catalog: NfcCatalog) {
    private val prefs = app.getSharedPreferences("nfc_history", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(NfcState(history = NfcHistory.parse(prefs.getString("entries", "[]") ?: "[]")))
    val state: StateFlow<NfcState> = _state

    fun refresh() {
        val a = NfcAdapter.getDefaultAdapter(app)
        _state.update { it.copy(available = a != null, enabled = a?.isEnabled == true) }
    }

    fun startReader(activity: Activity) {
        refresh()
        val a = NfcAdapter.getDefaultAdapter(activity) ?: return
        if (!a.isEnabled) return
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        runCatching { a.enableReaderMode(activity, { tag -> onTag(tag) }, flags, extras) }
            .onSuccess { _state.update { it.copy(listening = true) } }
            .onFailure { e -> _state.update { it.copy(listening = false, status = "Reader mode failed: ${e.message}") } }
    }

    fun stopReader(activity: Activity) {
        runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
        _state.update { it.copy(listening = false) }
    }

    fun onTag(tag: Tag) {
        val p = _state.value.pending
        if (p != null) {
            val result = runCatching { NfcReader.write(tag, p.message, p.format) }.getOrElse { "Write failed: ${it.javaClass.simpleName} ${it.message.orEmpty()}" }
            _state.update { it.copy(pending = null, status = result) }
        }
        val info = runCatching { NfcReader.read(tag, catalog) }.getOrElse { TagInfo(System.currentTimeMillis(), Bytes.hex(tag.id ?: ByteArray(0)), tag.techList.map { t -> t.substringAfterLast('.') }, "Unknown", error = it.message.orEmpty()) }
        val hist = (listOf(NfcHistory.entry(info)) + _state.value.history).take(NfcHistory.MAX)
        prefs.edit().putString("entries", NfcHistory.json(hist)).apply()
        _state.update { it.copy(last = info, history = hist, status = if (p == null) "Read ${info.type}" else it.status) }
    }

    fun armWrite(w: PendingWrite?) = _state.update { it.copy(pending = w, status = if (w == null) "Write cancelled" else "Hold your tag to the phone to write: ${w.label}") }

    fun clearHistory() {
        prefs.edit().putString("entries", "[]").apply()
        _state.update { it.copy(history = emptyList()) }
    }
}

/** The process-wide controller, so the tabs share the last tag, the pending write and the history. */
object NfcHub {
    @Volatile private var controller: NfcController? = null

    fun controller(ctx: Context): NfcController = controller ?: synchronized(this) {
        controller ?: NfcController(ctx.applicationContext, NfcCatalog.load(ctx.applicationContext)).also { controller = it }
    }
}
