package com.diegonmarcos.ide;

/**
 * #787 When this terminal holds its wake lock: exactly while the user wants it (build.json
 * wake_lock.default_on until they flip the in-app toggle) AND at least one session is open.
 * The caller does the PowerManager / WifiManager work for each transition this returns.
 *
 * No Android in here, so test/test-wake-lock.sh runs the real class on a plain JVM. The SAME
 * file (package line aside) is in cld.termux, cld.termux.nix and cloud-myterminal; that tester
 * goes red when the copies drift.
 */
public final class CloudWakeLock {

    public enum Transition { ACQUIRE, RELEASE, NONE }

    /** The live terminal's state, for /api/terminal; the service is the only writer. */
    public static final CloudWakeLock STATE = new CloudWakeLock();

    private boolean wanted;
    private int sessions;
    private boolean held;
    private long sinceMs;

    /** Record the user's choice and the open session count; say what the locks must do now. */
    public synchronized Transition update(boolean wanted, int sessions, long nowMs) {
        this.wanted = wanted;
        this.sessions = sessions;
        boolean hold = wanted && sessions > 0;
        if (hold == held) return Transition.NONE;
        held = hold;
        sinceMs = hold ? nowMs : 0;
        return hold ? Transition.ACQUIRE : Transition.RELEASE;
    }

    /** The service is going away: nothing is open and nothing may stay held. */
    public synchronized Transition destroyed() {
        sessions = 0;
        if (!held) return Transition.NONE;
        held = false;
        sinceMs = 0;
        return Transition.RELEASE;
    }

    public synchronized boolean held() { return held; }

    public synchronized boolean wanted() { return wanted; }

    /** {"held":..,"since":<epoch ms>|null,"sessions":..,"wanted":..,"wifi_lock":..} */
    public synchronized String json(boolean wifiLock) {
        return "{\"held\":" + held
            + ",\"since\":" + (held ? Long.toString(sinceMs) : "null")
            + ",\"sessions\":" + sessions
            + ",\"wanted\":" + wanted
            + ",\"wifi_lock\":" + (held && wifiLock) + "}";
    }
}
