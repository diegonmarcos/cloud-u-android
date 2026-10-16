import type { StorageConstructor } from '..';
import { NotesSyncDocStorage } from './doc';

export * from './doc';

export const notesSyncStorages = [
  NotesSyncDocStorage,
] satisfies StorageConstructor[];
