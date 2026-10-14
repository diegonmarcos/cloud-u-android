// Cloud fleet: the server link Cloud Account's Fleet Setup pushed into this app (Android native module
// CloudFleetSetup, fleet-config.json store `chat_setup`). The server screen uses it after a managed (EMM)
// config and before the local defaults. Read-only; never carries a token.

import {NativeModules, Platform} from 'react-native';

export type FleetSetupConfig = {
    serverUrl?: string;
    serverName?: string;
};

type FleetSetupNative = {
    getConfig: () => Promise<FleetSetupConfig>;
};

let cached: FleetSetupConfig | undefined;

export async function getFleetSetup(): Promise<FleetSetupConfig> {
    if (cached) {
        return cached;
    }
    const native = (NativeModules as {CloudFleetSetup?: FleetSetupNative}).CloudFleetSetup;
    if (Platform.OS !== 'android' || !native?.getConfig) {
        cached = {};
        return cached;
    }
    try {
        const cfg = await native.getConfig();
        cached = {
            serverUrl: cfg?.serverUrl || undefined,
            serverName: cfg?.serverName || undefined,
        };
    } catch {
        cached = {};
    }
    return cached;
}
