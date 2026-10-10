package com.diegonmarcos.cloudcode.chat;

/**
 * What a screen may show of a secret: its last four characters behind a fixed mask, and nothing
 * at all of a short one (its prefix names the provider and its length would say how much is
 * hidden). Plain Java, no Android: test/test-chat.sh compiles and runs it as is.
 */
public final class TokenMask {
    public static final String MASK = "••••••••";

    private TokenMask() {
    }

    public static String of(String token) {
        if (token == null) return "";
        String t = token.trim();
        if (t.isEmpty()) return "";
        if (t.length() < 16) return MASK;
        return MASK + t.substring(t.length() - 4);
    }
}
