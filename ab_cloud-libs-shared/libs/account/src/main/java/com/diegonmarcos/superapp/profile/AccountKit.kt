package com.diegonmarcos.superapp.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner

/**
 * The Account pages' share of the visual pass (cloud-account-ui spec): how a field is NAMED on a
 * page (never its raw `section › key` path), how an op's result line becomes a state + sentence
 * for a banner, and the long-press "copy path" affordance. Pure helpers plus one composable; every
 * drawing comes from libs:ui-kit.
 */

/**
 * The human label of a schema field or vault path: its last segment, `_v2`-style suffixes and
 * separators dropped, first letter capitalised; an array element names its parent and its
 * 1-based position (`titles_v2 › [0]` → "Titles 1", `addresses › [1] › city` → "City · Addresses 2").
 * The path itself stays reachable through [copyPath] on long-press.
 */
fun fieldLabel(path: String): String {
    val segs = path.split(InfoMask.SEP.trim(), ".", "/").map { it.trim() }.filter { it.isNotEmpty() }
    if (segs.isEmpty()) return path
    val index = Regex("""^\[(\d+)]$""")
    fun human(seg: String): String =
        seg.replace(Regex("""_v\d+$"""), "").replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }.ifBlank { seg }
    val lastIdx = index.find(segs.last())?.groupValues?.get(1)?.toIntOrNull()
    if (lastIdx != null) return human(segs.getOrNull(segs.size - 2) ?: segs.last()) + " ${lastIdx + 1}"
    val inner = segs.indices.lastOrNull { i -> i > 0 && index.matches(segs[i]) }
    val n = inner?.let { index.find(segs[it])?.groupValues?.get(1)?.toIntOrNull() }
    return if (inner != null && n != null) "${human(segs.last())} · ${human(segs[inner - 1])} ${n + 1}" else human(segs.last())
}

/** Up to two initials of [name] for the hero avatar. */
fun initialsOf(name: String): String =
    name.split(' ', '.', '_', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.ifBlank { "?" }

/** A result as a page shows it: a state and a sentence (no glyph prefix in the sentence). */
data class Said(val state: KitState, val text: String)

/** An op's result line (`✓ …`, `✗ …`, `…`) as a [Said]; the op's own text is not changed, only drawn. */
fun said(line: String): Said {
    val t = line.trim()
    return when {
        t.isEmpty() -> Said(KitState.IDLE, "")
        t == "…" -> Said(KitState.BUSY, "working")
        t.first() == '✓' -> Said(KitState.OK, t.drop(1).trim())
        t.first() == '✗' -> Said(KitState.BAD, t.drop(1).trim())
        else -> Said(KitState.IDLE, t)
    }
}

/** The last result of a page, as a banner; nothing when there is none. */
@Composable
fun SaidBanner(line: String, tag: String, action: KitAction? = null) = SaidBanner(said(line), tag, action)

@Composable
fun SaidBanner(s: Said, tag: String, action: KitAction? = null) {
    if (s.text.isBlank() && s.state == KitState.IDLE) return
    KitStatusBanner(s.text, s.state, tag = tag, action = action)
}

/** Long-press "copy path": the raw path goes to the clipboard, never onto the page. */
fun copyPath(ctx: Context, path: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("path", path))
    Toast.makeText(ctx, "path copied", Toast.LENGTH_SHORT).show()
}
