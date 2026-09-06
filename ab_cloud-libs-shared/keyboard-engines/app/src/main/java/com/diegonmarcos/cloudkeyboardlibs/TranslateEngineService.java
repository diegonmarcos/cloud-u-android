package com.diegonmarcos.cloudkeyboardlibs;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class TranslateEngineService extends Service {

    // Reply shape: {sourceTag, translatedText[, errorMessage]}. The third slot is
    // how the WHY of a failure crosses the binder: before, every exception was
    // squashed to {"und", ""} here and the keyboard could only say "engine
    // failed" — a timed-out model download, an unsupported language and a
    // missing network all looked identical to the user. Appending a slot keeps
    // an older keyboard working (it reads indices 0 and 1 only).
    private static String[] failed(Throwable t) {
        String msg = t.getMessage();
        return new String[]{"und", "", (msg == null || msg.isEmpty()) ? t.getClass().getSimpleName() : msg};
    }

    private final ITranslateEngine.Stub mBinder = new ITranslateEngine.Stub() {

        @Override
        public String[] translate(String text, String targetTag) {
            try {
                return com.diegonmarcos.superapp.translate.TranslateEngine
                        .translateBlocking(TranslateEngineService.this, text, targetTag);
            } catch (Throwable t) {
                return failed(t);
            }
        }

        @Override
        public String[] translateFrom(String text, String sourceTag, String targetTag) {
            try {
                return com.diegonmarcos.superapp.translate.TranslateEngine
                        .translateBlocking(TranslateEngineService.this, text, sourceTag, targetTag);
            } catch (Throwable t) {
                return failed(t);
            }
        }

        @Override
        public java.util.List<String> supportedLanguages() {
            try {
                return com.diegonmarcos.superapp.translate.TranslateEngine
                        .supportedLanguageTags();
            } catch (Throwable t) {
                return java.util.Collections.emptyList();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // Best-effort prefetch of common language models at startup.
        try {
            com.diegonmarcos.superapp.translate.TranslateEngine
                    .prefetchDefaults(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
