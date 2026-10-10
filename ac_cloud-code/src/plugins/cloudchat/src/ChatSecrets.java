package com.diegonmarcos.cloudcode.chat;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.diegonmarcos.superapp.texttools.TextTools;
import com.diegonmarcos.superapp.texttools.TextToolsClient;

/**
 * Cloud Code Chat's one secret: the OpenRouter token the owner pastes in Profile & Config.
 *
 * It lives ONLY here, in EncryptedSharedPreferences (AES-256-GCM values, AES-256-SIV keys, the
 * master key in the Android Keystore), declared in the fleet manifest as cloud_code_chat_secrets
 * (class secret). It never crosses into the WebView: the web page only ever receives {@link #mask}
 * of it, and every request that needs it is made by {@link CloudChatPlugin}, which adds the
 * Authorization header itself and only for openrouter.ai over https. Nothing in this plugin logs.
 *
 * When no token was pasted, the fleet Account's OpenRouter key (Cloud Account / the keyboard,
 * read over libs:text-tools' ITextTools binder, the same way Cloud Search reads it) is used per
 * request and never stored here.
 */
final class ChatSecrets {
    static final String PREFS = "cloud_code_chat_secrets";
    private static final String TOKEN = "openrouter_token";
    private static final String USE_ACCOUNT = "use_fleet_account";
    /** The fleet Account's provider id for OpenRouter (Cloud Search's search.ai.account_provider). */
    static final String ACCOUNT_PROVIDER = "openrouter";

    private final Context app;
    private SharedPreferences prefs;
    private TextToolsClient account;

    ChatSecrets(Context context) {
        this.app = context.getApplicationContext();
    }

    private synchronized SharedPreferences prefs() throws Exception {
        if (prefs == null) {
            MasterKey key = new MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            prefs = EncryptedSharedPreferences.create(
                app, PREFS, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        }
        return prefs;
    }

    private synchronized TextToolsClient account() {
        if (account == null) account = new TextToolsClient(app);
        return account;
    }

    void set(String token) throws Exception {
        String t = token == null ? "" : token.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("empty token");
        prefs().edit().putString(TOKEN, t).apply();
    }

    void clear() throws Exception {
        prefs().edit().remove(TOKEN).apply();
    }

    void useAccount(boolean on) throws Exception {
        prefs().edit().putBoolean(USE_ACCOUNT, on).apply();
    }

    boolean usesAccount() throws Exception {
        return prefs().getBoolean(USE_ACCOUNT, true);
    }

    String local() throws Exception {
        String t = prefs().getString(TOKEN, null);
        return t == null || t.isEmpty() ? null : t;
    }

    /** Whether the fleet Account binder answers right now (a status line, never the key). */
    boolean accountConnected() {
        return account().isConnected();
    }

    /** The fleet Account's key, per use; null when unbound or it holds none. Blocks: call off the UI thread. */
    String fromAccount() {
        TextTools.Result r = account().revealAiKey(ACCOUNT_PROVIDER);
        String t = r.getText();
        return t == null || t.trim().isEmpty() ? null : t.trim();
    }

    /** The token a request uses: the pasted one, else (when allowed) the fleet Account's. */
    String resolve() throws Exception {
        String t = local();
        if (t != null) return t;
        return usesAccount() ? fromAccount() : null;
    }

    /** The only form of a token that leaves this class for a screen (see {@link TokenMask}). */
    static String mask(String token) {
        return TokenMask.of(token);
    }
}
