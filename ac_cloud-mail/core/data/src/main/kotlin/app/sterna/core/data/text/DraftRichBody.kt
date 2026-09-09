package app.sterna.core.data.text

/*
 * Pure helpers for a draft's stored HTML (#131): what a save writes, what a reopen reads back,
 * and whether a body would lose fidelity if flattened.
 */

/**
 * The HTML part a draft save must store for [body], or null when there is nothing to store.
 */
fun draftHtmlToSave(body: RichBody): String? = if (body.isPlain) null else toHtml(body)

/**
 * The HTML this editor can use, or null — the single decision behind [richBodyFrom] and
 */
private fun usableDraftHtml(html: String?): RichBody? =
    html?.let(::fromHtml)?.takeIf { it.text.isNotBlank() }

/**
 * The body a composer reopens on: [html] parsed when usable, else [plainText].
 */
fun richBodyFrom(html: String?, plainText: () -> String): RichBody =
    usableDraftHtml(html) ?: RichBody.plain(plainText())

/**
 * Whether [html] is an HTML body this editor cannot give back faithfully: present, and
 */
fun draftHtmlIsLossy(html: String?): Boolean = html != null && usableDraftHtml(html) == null
