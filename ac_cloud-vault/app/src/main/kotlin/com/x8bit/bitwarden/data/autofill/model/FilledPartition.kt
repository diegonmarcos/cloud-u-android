package com.x8bit.bitwarden.data.autofill.model

import android.view.autofill.AutofillId
import android.widget.inline.InlinePresentationSpec

/**
 * All of the data required to build a `Dataset` for fulfilling a partition of data based on an
 * [AutofillCipher].
 *
 * @param autofillCipher The cipher used to fulfill these [filledItems].
 * @param filledItems A filled copy of each view from this partition.
 * @param inlinePresentationSpec The spec for the inline presentation given one is expected.
 * @param presentationIds Cloud Vault: when set, only these fields show the suggestion; the other
 * [filledItems] are filled when it is picked but never offer it (an Identity item's name and
 * address fields). Null: every filled field shows it, as upstream.
 */
data class FilledPartition(
    val autofillCipher: AutofillCipher,
    val filledItems: List<FilledItem>,
    val inlinePresentationSpec: InlinePresentationSpec?,
    val presentationIds: Set<AutofillId>? = null,
)
