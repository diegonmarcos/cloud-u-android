import raw from './vault-decl.json';
import type { VaultDecl } from './decl-types';

/** build.json::vault, as generated into vault-decl.json (scripts/gen-vault-decl.py). */
export const VAULT_DECL = raw as unknown as VaultDecl;
