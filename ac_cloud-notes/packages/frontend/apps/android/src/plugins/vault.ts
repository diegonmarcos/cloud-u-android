import { registerPlugin } from '@capacitor/core';
import { provideVaultNative } from '@cloud-notes/core/mobile/vault/native-host';
import type { VaultNative } from '@cloud-notes/core/mobile/vault/vault-fs';

// Wired to app.cloudnotes.pro.plugin.VaultPlugin on Android (folder access for the Vault view:
// SAF tree with a persisted grant, direct path, shared-store route). Core cannot import this
// package, so the plugin is handed over once, here.
export const Vault = registerPlugin<VaultNative>('Vault');

provideVaultNative(Vault);
