import type { NativeThemeMode } from './theme-mode';

export interface CloudNotesThemePlugin {
  onThemeChanged(options: { themeMode: NativeThemeMode }): Promise<void>;
}
