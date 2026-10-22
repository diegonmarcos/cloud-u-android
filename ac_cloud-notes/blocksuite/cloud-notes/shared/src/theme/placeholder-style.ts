import { ColorScheme } from '@blocksuite/cloud-notes-model';

export function inferColorSchemeFromThemeMode(
  themeMode?: string | null
): ColorScheme {
  return themeMode === 'dark' ? ColorScheme.Dark : ColorScheme.Light;
}

export function getCloudNotesPlaceholderFillColor(colorScheme: ColorScheme) {
  return colorScheme === ColorScheme.Dark
    ? 'rgba(255, 255, 255, 0.08)'
    : 'rgba(0, 0, 0, 0.04)';
}

export function getCloudNotesPlaceholderStrokeColor(colorScheme: ColorScheme) {
  return colorScheme === ColorScheme.Dark
    ? 'rgba(255, 255, 255, 0.04)'
    : 'rgba(0, 0, 0, 0.02)';
}
