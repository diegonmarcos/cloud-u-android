package com.termux.shared.shell;

/**
 * The app's versionName carries a build suffix ("0.118.4 (sha-dac09da6)"), which dpkg rejects
 * ("version string has embedded spaces"). Anything that hands that name to dpkg or writes it into
 * a control/status Version field goes through here: "0.118.4+sha.dac09da6".
 */
public final class DebianVersion {
    private DebianVersion() {}

    public static String of(String versionName) {
        if (versionName == null) return null;
        String v = versionName.trim().replaceAll("\\s*\\(sha-([0-9A-Za-z]+)\\)$", "+sha.$1");
        v = v.replaceAll("[^0-9A-Za-z.+~:-]+", ".");
        if (v.isEmpty() || !Character.isDigit(v.charAt(0))) v = "0" + (v.isEmpty() ? "" : "." + v);
        return v;
    }
}
