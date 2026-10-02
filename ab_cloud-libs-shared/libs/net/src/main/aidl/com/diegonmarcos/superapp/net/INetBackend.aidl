package com.diegonmarcos.superapp.net;

/**
 * The WireGuard Backend contract, across a process boundary.
 *
 * Everything is a String on purpose. Config has a canonical text form
 * (wg-quick), Tunnel.State is an enum name, and statistics arrive as the
 * engine's own raw `wgGetConfig` output which Statistics.parse turns back
 * into an object on this side - so no AIDL parcelable has to be kept in
 * step with upstream WireGuard types we re-sync from their tree.
 *
 * Declared in libs:net (the contract) rather than duplicated on each side,
 * so client and service cannot drift.
 */
interface INetBackend {
    /** Tunnel.State name, or "DOWN" when unknown. */
    String getState(String tunnelName);

    /**
     * Bring [tunnelName] to [state]. [wgQuickConfig] is Config.toWgQuickString()
     * and may be null when going DOWN. Returns the resulting Tunnel.State name.
     */
    String setState(String tunnelName, String state, String wgQuickConfig);

    /** Raw wgGetConfig text for Statistics.parse; "" when the tunnel is down. */
    String getStatisticsRaw(String tunnelName);

    /** Engine version, for diagnostics. */
    String getVersion();

    /**
     * Always-on / lockdown are properties of the VPN profile the SYSTEM holds
     * against the package that owns the VpnService - which is the engine APK,
     * not the caller. So they have to be asked across the boundary too; the
     * app cannot read its own settings and get the right answer any more.
     */
    boolean isAlwaysOn();

    boolean isLockdownEnabled();

    /**
     * #751 What the VPN slot carries while NO other tunnel is up: [wgQuickConfig]
     * (a peerless tunnel holding the fleet's DNS choice) under [tunnelName].
     * The engine keeps it, raises it whenever one of its tunnels goes down, and
     * yields to any tunnel brought UP. [raiseNow] also raises it at once - false
     * when another app's VPN holds the slot and should keep it. An empty config
     * releases the slot. Returns the same answer as getIdleStatus().
     *
     * Appended after every older method so their transaction codes are
     * unchanged: an engine that predates this answers null, not a wrong method.
     */
    String setIdleTunnel(String tunnelName, String wgQuickConfig, boolean raiseNow);

    /** "OFF", "UP", "STANDBY" (another tunnel holds the slot), or "DOWN: <why>". */
    String getIdleStatus();
}
