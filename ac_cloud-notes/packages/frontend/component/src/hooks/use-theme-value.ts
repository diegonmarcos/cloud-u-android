import { type CloudNotesTheme, darkTheme, lightTheme } from '@cloud-notes/theme';
import {
  type CloudNotesThemeKeyV2,
  darkThemeV2,
  lightThemeV2,
} from '@cloud-notes/theme/v2';
import { useTheme } from 'next-themes';

export const useThemeValueV2 = (key: CloudNotesThemeKeyV2) => {
  const { resolvedTheme } = useTheme();

  return resolvedTheme === 'dark' ? darkThemeV2[key] : lightThemeV2[key];
};

export const useThemeValueV1 = (key: keyof Omit<CloudNotesTheme, 'editorMode'>) => {
  const { resolvedTheme } = useTheme();

  return resolvedTheme === 'dark' ? darkTheme[key] : lightTheme[key];
};
