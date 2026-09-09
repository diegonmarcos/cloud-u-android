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
}
