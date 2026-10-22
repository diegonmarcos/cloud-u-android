import { ColorScheme } from '@blocksuite/cloud-notes-model';
import { describe, expect, it } from 'vitest';

import {
  getCloudNotesPlaceholderFillColor,
  getCloudNotesPlaceholderStrokeColor,
  inferColorSchemeFromThemeMode,
} from '../../../../shared/src/theme/placeholder-style.js';

describe('cloud-notes placeholder style', () => {
  it('returns subtle light placeholder colors', () => {
    expect(getCloudNotesPlaceholderFillColor(ColorScheme.Light)).toBe(
      'rgba(0, 0, 0, 0.04)'
    );
    expect(getCloudNotesPlaceholderStrokeColor(ColorScheme.Light)).toBe(
      'rgba(0, 0, 0, 0.02)'
    );
  });

  it('returns subtle dark placeholder colors', () => {
    expect(getCloudNotesPlaceholderFillColor(ColorScheme.Dark)).toBe(
      'rgba(255, 255, 255, 0.08)'
    );
    expect(getCloudNotesPlaceholderStrokeColor(ColorScheme.Dark)).toBe(
      'rgba(255, 255, 255, 0.04)'
    );
  });

  it('infers color scheme from theme mode', () => {
    expect(inferColorSchemeFromThemeMode('dark')).toBe(ColorScheme.Dark);
    expect(inferColorSchemeFromThemeMode('light')).toBe(ColorScheme.Light);
    expect(inferColorSchemeFromThemeMode('')).toBe(ColorScheme.Light);
  });
});
