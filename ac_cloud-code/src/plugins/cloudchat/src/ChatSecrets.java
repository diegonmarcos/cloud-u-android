package com.diegonmarcos.cloudcode.chat;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.diegonmarcos.superapp.texttools.TextToolsClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Cloud Code Chat's one secret: the OpenRouter token the owner pastes in Profile & Config.
 *
 * It lives ONLY here, in EncryptedSharedPreferences (AES-256-GCM values, AES-256-SIV keys, the
 * master key in the Android Keystore), declared in the fleet manifest as cloud_code_chat_secrets
 * (class secret). It never crosses into the WebView: the web page only ever receives {@link #mask}
 * of it, and every request that needs it is made by {@link CloudChatPlugin}, which adds the
 * Authorization header itself and only for openrouter.ai over https. Nothing in this plugin logs.
 *
 * The fleet Account (Cloud Account / the keyboard, over libs:text-tools' ITextTools binder) is
 * only ASKED whether it holds an OpenRouter key, through the key-free aiRoutingSnapshot(): this
 * app never calls revealAiKey (the fleet rule, decisions-use guard D5: no new module holds the
 * Account's key), so the Chat pays with the token pasted here and nothing else.
 */
final class ChatSecrets {
    static final String PREFS = "cloud_code_chat_secrets";
    private static final String TOKEN = "openrouter_token";
    /** The fleet Account's provider id for OpenRouter (Cloud Search's search.ai.account_provider). */
    static final String ACCOUNT_PROVIDER = "openrouter";
    /** A wedged peer never answers a binder call: the status line gives it this long. */
    private static final long ACCOUNT_DEADLINE_MS = 2500;
    private final ExecutorService binder = Executors.newSingleThreadExecutor();

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

    private synchronized TextToolsClient client() {
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

    String local() throws Exception {
        String t = prefs().getString(TOKEN, null);
        return t == null || t.isEmpty() ? null : t;
    }

    /**
     * The fleet Account as a status line, never its key: whether the binder answers, and whether it
     * holds an OpenRouter key (key_present / key_hint, the last four characters at most, from the
     * key-free aiRoutingSnapshot). Blocks up to {@link #ACCOUNT_DEADLINE_MS}: call off the UI thread.
     */
    JSONObject account() {
        JSONObject out = new JSONObject();
        try {
            Future<String> f = binder.submit(() -> client().aiRoutingSnapshot());
            String snap = f.get(ACCOUNT_DEADLINE_MS, TimeUnit.MILLISECONDS);
            out.put("connected", snap != null);
            JSONArray providers = snap == null ? null : new JSONObject(snap).optJSONArray("providers");
            for (int i = 0; providers != null && i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p != null && ACCOUNT_PROVIDER.equals(p.optString("id"))) {
                    out.put("key_present", p.optBoolean("key_present"));
                    out.put("key_hint", p.optString("key_hint"));
                }
            }
        } catch (Exception e) {
            try {
                out.put("connected", false);
            } catch (Exception ignored) {
                // a JSON put of a boolean cannot fail
            }
        }
        return out;
    }

    /** The token a request uses: the one pasted in Profile & Config, or null. */
    String resolve() throws Exception {
        return local();
    }

    /** The only form of a token that leaves this class for a screen (see {@link TokenMask}). */
    static String mask(String token) {
        return TokenMask.of(token);
    }
}
