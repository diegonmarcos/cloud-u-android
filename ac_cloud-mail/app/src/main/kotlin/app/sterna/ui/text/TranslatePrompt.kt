package app.sterna.ui.text

/**
 * The prompt for the model-backed translator. One segment per line in, the same number of lines out,
 * so the in-place pipeline can pair them; tokens that stand in for links and figures are left alone.
 */
internal fun llmTranslatePrompt(targetTag: String, sourceTag: String?): String {
    val to = MailLanguages.nameOf(targetTag, java.util.Locale.ENGLISH)
    val from = sourceTag?.let { " from ${MailLanguages.nameOf(it, java.util.Locale.ENGLISH)}" } ?: ""
    return "You are a translation engine. Translate the following text$from into $to" +
        (if (sourceTag == null) " (detect the source language yourself; the text may mix languages)" else "") +
        ". The input has one segment per line: output EXACTLY the same number of lines, in the same order, " +
        "one translated segment per input line, with nothing added. Leave tokens like \u27E60\u27E7, web addresses, " +
        "e-mail addresses, numbers and code exactly as they are. Output only the translation."
}

/**
 * The summary prompt, extended to ALSO produce one suggested reply in the same call: the summary, then a
 * line holding exactly the marker, then the reply. [replyTag] is the message's detected language, or null
 * to ask the model to answer in the language the message is written in.
 */
internal fun summaryWithReplyPrompt(summaryPrompt: String, replyTag: String?, answerPrompt: String): String {
    val language = replyTag?.let { MailLanguages.nameOf(it, java.util.Locale.ENGLISH) } ?: "the language the message is written in"
    return summaryPrompt +
        "\nThen, after the summary, write one line containing exactly ${app.sterna.core.data.text.SuggestedReply.MARK} " +
        "and below it ONE short suggested reply to the sender, written in $language. For the reply, follow " +
        "these instructions: $answerPrompt"
}
