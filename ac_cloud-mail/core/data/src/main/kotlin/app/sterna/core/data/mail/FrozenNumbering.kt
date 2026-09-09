package app.sterna.core.data.mail

/** What a caller of the two destructive halves of a move between accounts (#189) says about the IMAP
 *  numbering its message id belongs to. A type because one `Long?` spelled both "I froze nothing,
 *  fall back on the folder's record" and "I froze the numbering, and the row carried none". */
sealed interface FrozenNumbering {

    /** Fall back on the folder's record; a refusal here would fail every IMAP swipe-delete. */
    data object NothingFrozen : FrozenNumbering

    /** [value] is null when the row carried none. On the destructive path, nothing to oppose is a
     *  refusal and not a fallback; `0` or a negative counts as nothing too ([UidValidity.stated]). */
    data class Frozen(val value: Long?) : FrozenNumbering
}

sealed interface NumberingToOppose {

    /** Nothing can be confirmed: raise `ImapNumberingUnconfirmed` and touch nothing. */
    data object Refuse : NumberingToOppose

    /** [stamp] goes on the SELECT about to run; `null` falls back on the folder's record. */
    data class Select(val stamp: Long?) : NumberingToOppose
}

/** [FrozenNumbering.NothingFrozen] → `Select(null)`; `Frozen(x)` with a real numbering → `Select(x)`;
 *  `Frozen(null)`, `0` or a negative → [NumberingToOppose.Refuse]. Says nothing about JMAP. */
fun numberingToOppose(frozen: FrozenNumbering): NumberingToOppose = when (frozen) {
    FrozenNumbering.NothingFrozen -> NumberingToOppose.Select(null)
    is FrozenNumbering.Frozen -> UidValidity.stated(frozen.value)?.let { NumberingToOppose.Select(it) } ?: NumberingToOppose.Refuse
}
