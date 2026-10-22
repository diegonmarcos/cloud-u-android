import { registerPlugin } from '@capacitor/core';

import type { CloudNotesThemePlugin } from './definitions';

const CloudNotesTheme = registerPlugin<CloudNotesThemePlugin>('CloudNotesTheme');

export * from './definitions';
export * from './theme-mode';
export { CloudNotesTheme };
