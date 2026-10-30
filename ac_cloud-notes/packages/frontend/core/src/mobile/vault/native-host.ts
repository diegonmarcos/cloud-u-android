// The Capacitor plugin lives in the android app package (apps/android/src/plugins/vault.ts);
// core cannot import it, so the app hands it over once at start-up.
import type { VaultNative } from './vault-fs';

let host: VaultNative | null = null;

export const provideVaultNative = (native: VaultNative): void => {
  host = native;
};

export const vaultNative = (): VaultNative | null => host;
