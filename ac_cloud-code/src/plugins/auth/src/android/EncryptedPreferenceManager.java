package com.foxdebug.acode.rk.auth;

import android.content.Context;
import android.content.SharedPreferences;
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs;

public class EncryptedPreferenceManager {
    private final SharedPreferences sharedPreferences;

    /**
     * @param context  The Android Context
     * @param prefName The custom name for your preference file (e.g., "user_session")
     */
    public EncryptedPreferenceManager(Context context, String prefName) {
        this(context, prefName, true);
    }

    /**
     * @param context                The Android Context
     * @param prefName               The custom name for your preference file
     * @param allowPlaintextFallback Whether storage may fall back to ordinary
     *                               SharedPreferences when encryption is unavailable
     */
    public EncryptedPreferenceManager(
            Context context,
            String prefName,
            boolean allowPlaintextFallback
    ) {
        // #1106 SafeEncryptedPrefs never throws: a Keystore master key that does not match the file resets the
        // store instead of crashing, so there is no plaintext fallback to choose (allowPlaintextFallback is kept for callers).
        SharedPreferences preferences = SafeEncryptedPrefs.prefs(context, prefName);
        sharedPreferences = preferences;
    }

    // --- Reusable Methods ---

    public void setString(String key, String value) {
        sharedPreferences.edit().putString(key, value).apply();
    }

    public boolean setStringSync(String key, String value) {
        return sharedPreferences.edit().putString(key, value).commit();
    }

    public String getString(String key, String defaultValue) {
        return sharedPreferences.getString(key, defaultValue);
    }

    public void setInt(String key, int value) {
        sharedPreferences.edit().putInt(key, value).apply();
    }

    public int getInt(String key, int defaultValue) {
        return sharedPreferences.getInt(key, defaultValue);
    }

    public void setBoolean(String key, boolean value) {
        sharedPreferences.edit().putBoolean(key, value).apply();
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return sharedPreferences.getBoolean(key, defaultValue);
    }

    public void remove(String key) {
        sharedPreferences.edit().remove(key).apply();
    }

    public boolean removeSync(String key) {
        return sharedPreferences.edit().remove(key).commit();
    }

    public boolean exists(String key) {
        return sharedPreferences.contains(key);
    }

    public void clear() {
        sharedPreferences.edit().clear().apply();
    }
}
