package app.sterna.ui.text

/**
 * The prompts of the two composer tools that have no registry entry of their own.
 *
 * [DEFAULT] is Answer Prediction's prompt out of the box, and it is the text the owner sees, edits and
 * resets on Configs > Text > Answer Prediction. The same prompt, whatever it has been edited to, also
 * writes the suggested reply that comes with a summary - one declaration of how this app answers mail.
 */
object AnswerPrompt {
    const val DEFAULT =
        "Write a reply to the message below, as its recipient. Write the reply in the language of the message " +
            "and match its tone and level of formality. Keep it concise. Never invent facts, dates, prices or " +
            "commitments that the message does not support; where information is missing, leave a placeholder " +
            "such as [date] or [amount] for the sender to fill in. Plain text only: no subject line and no signature."

    /** Check Grammar: fix the writing, change nothing else. */
    const val GRAMMAR =
        "Correct the spelling, grammar and punctuation of the text below. Keep its language, its meaning, " +
            "its tone and its line breaks, and add or remove nothing. Output only the corrected text."
}
