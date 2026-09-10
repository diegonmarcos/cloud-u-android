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

    /**
     * THE SERVING APP'S WHOLE AI-ROUTING STATE, AS JSON, FOR A FLEET CONSOLE TO SHOW AND EDIT.
     * Read by cloud-superapp's Configs > AI > Tokens Fleet and by nothing else today.
     *
     * NO KEY IS IN IT. `key_present` says whether a provider holds one and `key_hint` carries at
     * most its last four characters so the owner can tell two accounts apart; the key itself only
     * ever leaves this process through revealAiKey(), which is a separate method precisely so that
     * the one call that emits a secret is the one call an auditor has to read. Anything added to
     * this object later has to pass that same test - see settingsSnapshot() above, which draws the
     * same line for the same reason.
     *
     * IT CARRIES THE MODEL REGISTRY, and that is the main reason it exists rather than being three
     * smaller methods. A console that listed models from a copy of its own would be a second
     * registry to go stale, which is what produced this fleet's stale-price and wrong-unit bugs;
     * shipping the rows themselves means the console can only ever show what the serving app
     * actually holds. Every model row is the registry's own: id, name, open, params_b, quant,
     * trained_for, note, and the two prices.
     *
     * PRICES ARE UNITED STATES DOLLARS PER MILLION TOKENS - the registry's unit, unconverted, under
     * the key names prompt_usd_per_million / completion_usd_per_million so no reader can mistake
     * them for the per-token figure OpenRouter publishes or for the cents this fleet briefly showed.
     *
     * SHAPE: {app, default_provider, providers:[{id, label, needs_token, default_model,
     * chosen_model, key_present, key_hint, pricing_as_of, models:[...]}]}.
     *
     * ANSWERS null FROM AN OLDER SERVING APP, and callers must handle that. A keyboard installed
     * before this method existed has no transaction code for it, so the reply parcel comes back
     * untouched and this reads as null rather than throwing. A caller that is BOUND and gets null
     * here has a peer too old to answer, which is a different thing from a peer that is not
     * installed, and the two must not be reported as one.
     */
    String aiRoutingSnapshot();

    /**
     * Set this app's provider key and/or chosen model - the write half of aiRoutingSnapshot().
     *
     * ONE METHOD FOR BOTH because they are one errand: the owner provisioning a peer sets the
     * account and picks the model in the same breath, and two methods would be two round trips and
     * two ways to half-succeed. A null or empty [apiKey] leaves the stored key untouched, and a
     * null or empty [modelId] leaves the stored model untouched, so either can be sent alone.
     *
     * TO CLEAR A KEY, send [clearKey] true. That is a flag rather than the empty string because
     * empty already means "do not touch", and a console that wiped the owner's credential every
     * time it saved a model change would be the worst possible reading of a blank field.
     *
     * THE KEY TRAVELS IN A BINDER CALL AND NOWHERE ELSE. Not an Intent extra, not a broadcast:
     * this transaction is point-to-point between two processes and the platform has already
     * refused any caller not holding CONSTELLATION_DATA, which is signature-level. It is stored
     * where the key already lives - the serving app's own preferences, the same slot its own
     * settings screen writes - and no new store is created for it anywhere.
     *
     * [modelId] must be a model id the serving app's registry actually holds; an unknown id is
     * refused rather than stored, because a stored id nothing can resolve is a route that fails at
     * call time with nothing on screen having said so.
     */
    String[] setAiRouting(in String providerId, in String apiKey, in String modelId, boolean clearKey);

    /**
     * The provider's key IN PLAINTEXT - the only method in this file that emits a credential.
     *
     * IT IS SEPARATE ON PURPOSE. Folding it into aiRoutingSnapshot() would mean every status
     * refresh carried the secret and the one dangerous call would be invisible among the harmless
     * ones; as its own method it can be grepped for, asserted about, and read by whoever audits
     * this next. Its caller is a deliberate reveal gesture by the owner, never a page load.
     *
     * WHAT THIS WIDENS, stated plainly because it is a real change: before this method the key
     * could be read by exactly one process, the serving app's own. Now it can be read by any
     * installed package holding CONSTELLATION_DATA - that is, any APK signed with the Cloud key,
     * which is the owner's own fleet and nothing else. A hostile app cannot hold a signature
     * permission and cannot be granted one by any user action, so the reachable set is the fleet.
     * That is a wider set than one, and whoever changes the signing story must revisit this.
     *
     * NEVER LOG WHAT THIS RETURNS, on either side. This fleet ships a diagnostics path that reads
     * logcat and uploads it (DevControlServer /diagnostics/bundle, DiagnosticsPush), so a key that
     * reaches the log is a key that leaves the phone.
     */
    String[] revealAiKey(in String providerId);
}
