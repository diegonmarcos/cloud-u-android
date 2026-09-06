package com.diegonmarcos.superapp.translate;

import android.content.Context;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TranslateEngine {
    // Every ML Kit await is BOUNDED. Before, all three were Tasks.await(task)
    // with no timeout: a first-use model download on a slow link (or no link —
    // downloadModelIfNeeded has no network precondition) blocked the caller's
    // single translate thread indefinitely, and every later keystroke's request
    // queued behind it — the bar just went silent. A timed-out download keeps
    // going inside ML Kit, so the next attempt after it lands succeeds.
    static final long ID_TIMEOUT_S = 5;
    static final long DOWNLOAD_TIMEOUT_S = 30;
    static final long TRANSLATE_TIMEOUT_S = 10;

    // Auto-detect the source. Returns {detectedLanguageTag, translatedText}. BLOCKING — call off the main thread.
    public static String[] translateBlocking(Context context, String text, String targetTag) throws IOException {
        return translateBlocking(context, text, null, targetTag);
    }

    // sourceTag null / "" / "auto" = detect; anything else is used as-is (no detection).
    // Returns {sourceTag, translatedText}; {"und", ""} when the source is unknown/unsupported.
    public static String[] translateBlocking(Context context, String text, String sourceTag, String targetTag) throws IOException {
        if (text == null || text.trim().isEmpty())
            return new String[]{"und", text == null ? "" : text};
        String detected = sourceTag;
        if (detected == null || detected.isEmpty() || "auto".equals(detected)) {
            LanguageIdentifier identifier = LanguageIdentification.getClient();
            try {
                detected = Tasks.await(identifier.identifyLanguage(text), ID_TIMEOUT_S, TimeUnit.SECONDS);
            } catch (Throwable ex) {
                throw asIo(ex);
            } finally {
                identifier.close();
            }
        }
        String source = TranslateLanguage.fromLanguageTag(detected);
        String target = TranslateLanguage.fromLanguageTag(targetTag);
        if (target == null) throw new IOException("Unsupported target language: " + targetTag);
        if (source == null) return new String[]{"und", ""};
        if (source.equals(target)) return new String[]{detected, text};
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(source).setTargetLanguage(target).build();
        Translator translator = Translation.getClient(options);
        try {
            Tasks.await(translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()),
                    DOWNLOAD_TIMEOUT_S, TimeUnit.SECONDS);
            String result = Tasks.await(translator.translate(text), TRANSLATE_TIMEOUT_S, TimeUnit.SECONDS);
            return new String[]{detected, result};
        } catch (Throwable ex) {
            throw asIo(ex);
        } finally {
            translator.close();
        }
    }

    private static IOException asIo(Throwable ex) {
        return ex instanceof IOException ? (IOException) ex : new IOException(ex);
    }

    public static List<String> supportedLanguageTags() {
        return new ArrayList<>(TranslateLanguage.getAllLanguages());
    }

    // Best-effort background pre-download of en/pt/es/de models (Wi-Fi only).
    public static void prefetchDefaults(Context context) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    RemoteModelManager manager = RemoteModelManager.getInstance();
                    DownloadConditions conditions = new DownloadConditions.Builder().requireWifi().build();
                    String[] defaults = {TranslateLanguage.ENGLISH, TranslateLanguage.PORTUGUESE, TranslateLanguage.SPANISH, TranslateLanguage.GERMAN};
                    for (String tag : defaults) {
                        try {
                            TranslateRemoteModel model = new TranslateRemoteModel.Builder(tag).build();
                            Tasks.await(manager.download(model, conditions));
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        }).start();
    }
}
