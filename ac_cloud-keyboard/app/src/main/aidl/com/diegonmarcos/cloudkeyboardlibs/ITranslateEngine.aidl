package com.diegonmarcos.cloudkeyboardlibs;
interface ITranslateEngine {
    String[] translate(String text, String targetTag);
    List<String> supportedLanguages();
    // Appended LAST on purpose: AIDL transaction codes follow declaration order, so
    // an older companion still answers translate()/supportedLanguages() for a newer
    // keyboard (the client falls back to translate() when this one is missing).
    // Explicit source, no detection — see TranslateEngineClient.translateFrom.
    String[] translateFrom(String text, String sourceTag, String targetTag);
}
