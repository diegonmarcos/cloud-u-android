import { type ColorScheme } from '@blocksuite/cloud-notes-model';
import { getCloudNotesPlaceholderFillColor } from '@blocksuite/cloud-notes-shared/theme';

export function getSurfacePlaceholderFallback(colorScheme: ColorScheme) {
  return getCloudNotesPlaceholderFillColor(colorScheme);
}

export function resolveSurfacePlaceholderColor(colorScheme: ColorScheme) {
  return getSurfacePlaceholderFallback(colorScheme);
}
