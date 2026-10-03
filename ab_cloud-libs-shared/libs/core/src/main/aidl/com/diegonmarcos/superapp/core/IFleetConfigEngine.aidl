package com.diegonmarcos.superapp.core;

/**
 * #825 the versioned wire between an app's `<package>.fleetconfig` provider (libs:core, in every
 * fleet app) and the fleet-config policy engine (Cloud-Lib-Fleetconfig.apk). The provider keeps
 * only what must run in the app's own process - reading and writing its SharedPreferences; the
 * manifest, the store classes and the migration policy run in the engine.
 *
 * Kept deliberately tiny and stable: a change here rebuilds every fleet app.
 * contractVersion() is the handshake: a provider refuses an engine that speaks another version
 * (FleetConfigEngine.CONTRACT_VERSION) instead of misreading its answers.
 */
interface IFleetConfigEngine {
    /** The engine's contract version; the client compares it before its first call. */
    int contractVersion();

    /** Method names this engine answers, so a caller can degrade knowingly. */
    String[] methods();

    /** Invoke [method] with [args]; JSON back, or a JSON object with an "error" key. Never throws. */
    String call(String method, in String[] args);
}
