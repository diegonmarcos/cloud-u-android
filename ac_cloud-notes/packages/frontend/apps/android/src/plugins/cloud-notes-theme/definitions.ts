export interface CloudNotesThemePlugin {
  onThemeChanged(options: { darkMode: boolean }): Promise<void>;
  getSystemNavBarHeight(): Promise<{ height: number }>;
}
