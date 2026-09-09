package com.diegonmarcos.superapp.texttools;

/**
 * The Text tools, as one binder: rewrite this text, translate this text.
 *
 * TWO ENGINES, AND THEY ARE NOT INTERCHANGEABLE. enhance() goes through the
 * OpenRouter-shape provider chosen in AI Model Routing. translate() goes through the
 * translation library. Neither ever calls the other's engine; crossing them would
 * bill a translation to the LLM provider and route a rewrite through a translator,
 * and the caller cannot tell from the reply which one answered. The implementation
 * is asserted against exactly that.
 *
 * REPLY SHAPE: every call answers String[2] = {text, reason}. Success fills slot 0
 * and leaves slot 1 empty; failure leaves slot 0 empty and puts a user-readable
 * reason in slot 1. Never null, never a one-slot array. A binder cannot carry an
 * exception across a process boundary, and the translate engines in this repo
 * already learned that squashing a failure into an empty result is how a feature
 * comes to look "flaky" instead of broken - the reason has to travel.
 *
 * BLOCKING. Every method here does network or model work; the platform runs binder
 * transactions on a pool thread on the service side, but the CALLER's thread is the
 * one that waits. Never call these on a main thread.
 *
 * METHOD ORDER IS THE WIRE FORMAT. Transaction codes are assigned by declaration
 * order, so a method may only ever be APPENDED. Inserting one renumbers every method
 * below it, and an older installed keyboard would then answer the wrong call.
 */
interface ITextTools {

    /**
     * Rewrite [text] through the AI Model Routing provider.
     * styleId "" = the style, tone, length and output language the user pinned in
     * Text Enhancements; any other id = that one style alone (what Grammar check does).
     */
    String[] enhance(in String text, in String styleId);

    /**
     * Translate [text] through the translation library.
     * targetTag "" = the default target from Translation settings.
     */
    String[] translate(in String text, in String targetTag);

    /** Readable name of the provider enhance() routes to, for progress and error text. */
    String enhanceProviderLabel();

    /** BCP-47 tags translate() can target right now; empty when no engine is bound. */
    List<String> translateLanguages();

    /**
     * Summarise [text] through the same AI Model Routing provider enhance() uses.
     * summaryId "" = the summary shape the user pinned in Text Resume; any other id = that one.
     *
     * "RESUME" IS THE OWNER'S NAME FOR THIS AND IT MEANS SUMMARISE. Not a curriculum vitae, and
     * not resuming a paused operation. The method is spelled summarise() so the wire cannot be
     * misread even though the menus say Resume.
     *
     * A THIRD METHOD, NOT A THIRD ENGINE. It shares enhance()'s provider, key, model, timeout and
     * error wording; what differs is the prompt set it draws from (keyboard_ai.summaries) and that
     * it sends ONE request rather than splitting long input, because a summary of each piece
     * stitched together is longer than the piece it came from, not shorter.
     *
     * APPENDED, at the end, per the method-order rule above: inserting it anywhere else renumbers
     * every method below and an older installed keyboard would answer the wrong call.
     */
    String[] summarise(in String text, in String summaryId);

    /**
     * Rewrite [text] against a system prompt and a model THE CALLER chose.
     *
     * WHY THIS EXISTS, AND IT IS NOT AN OPTIMISATION. [enhance] resolves the prompt and the model
     * out of the SERVING app's preferences. That makes the serving app the only owner of those
     * settings: a second app calling it gets the keyboard's Enhance style whether it wants it or
     * not, and the only screen that could change it is the keyboard's own. The owner asked for
     * cloud-mail to have its own Enhance settings, editable in cloud-mail, and this is the method
     * that lets an app have them — it hands over its whole decision and keeps nothing here.
     *
     * WHAT STAYS ON THIS SIDE: the HTTP call, the auth header, the timeout, the chunking of input
     * past the provider's budget, the error wording, and above all the API KEY. The caller names a
     * provider; it never sees that provider's credential. One engine, several sets of settings.
     *
     * [systemPrompt] is the FULLY COMPOSED prompt — preamble, style, tone, length and output
     * language already joined by the caller from the caller's own registry. Nothing here adds to
     * it, because anything added here would be a setting this side still owned.
     *
     * [providerId] and [modelId] empty = fall back to whatever the serving app has configured.
     * That is the honest answer for a caller that has not chosen yet, and it is what the very
     * first launch after an upgrade does before its settings are seeded.
     */
    String[] enhanceWith(in String text, in String systemPrompt, in String providerId, in String modelId);

    /**
     * Summarise [text] against a system prompt and a model THE CALLER chose — [enhanceWith]'s
     * reasoning applied to Text Resume, and appended for the same wire-format rule.
     *
     * Still NOT [enhanceWith] with a different prompt: this one sends ONE request and states the
     * cut when the input did not fit, where a rewrite splits and rejoins. Crossing them yields a
     * summary per piece, concatenated — longer than the text it summarised.
     *
     * [bullets] true = the caller's prompt asked for a list, so hold the model to one. It travels
     * because it is a property OF THE CALLER'S PROMPT, and the caller is now the only side that
     * knows which of its prompts asked for bullets. The enforcement itself does not travel.
     */
    String[] summariseWith(in String text, in String systemPrompt, boolean bullets, in String providerId, in String modelId);

    /** Readable name of [providerId], for progress and error text; the configured one when empty. */
    String providerLabelFor(in String providerId);

    /**
     * The serving app's CURRENT text-tool choices, as JSON, so a caller adopting its own copy of
     * these settings can start from what the owner already configured rather than from defaults.
     *
     * READ ONCE, AT SEEDING, AND NEVER AGAIN. A caller that kept reading this would be back to
     * having no settings of its own. Keys mirror the preference names; see MailTextToolsPrefs.
     *
     * NO CREDENTIAL IS IN IT. The API key is deliberately absent and must stay absent: this method
     * exists so a second app can copy the owner's CHOICES, not their account. A snapshot that
     * carried the token would put the key in a second app's storage, which is the one thing the
     * whole binder design exists to prevent.
     */
    String settingsSnapshot();
}
