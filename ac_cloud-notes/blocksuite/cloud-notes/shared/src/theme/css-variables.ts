/* CSS variables. You need to handle all places where `CSS variables` are marked. */

import { LINE_COLORS } from '@blocksuite/cloud-notes-model';
import {
  type CloudNotesCssVariables,
  type CloudNotesTheme,
  cssVar,
} from '@toeverything/theme';
export { cssVar } from '@toeverything/theme';
import { type CloudNotesThemeKeyV2, cssVarV2 } from '@toeverything/theme/v2';
import { unsafeCSS } from 'lit';
export { cssVarV2 } from '@toeverything/theme/v2';
export const ColorVariables = [
  '--cloud-notes-brand-color',
  '--cloud-notes-primary-color',
  '--cloud-notes-secondary-color',
  '--cloud-notes-tertiary-color',
  '--cloud-notes-hover-color',
  '--cloud-notes-icon-color',
  '--cloud-notes-icon-secondary',
  '--cloud-notes-border-color',
  '--cloud-notes-divider-color',
  '--cloud-notes-placeholder-color',
  '--cloud-notes-quote-color',
  '--cloud-notes-link-color',
  '--cloud-notes-edgeless-grid-color',
  '--cloud-notes-success-color',
  '--cloud-notes-warning-color',
  '--cloud-notes-error-color',
  '--cloud-notes-processing-color',
  '--cloud-notes-text-emphasis-color',
  '--cloud-notes-text-primary-color',
  '--cloud-notes-text-secondary-color',
  '--cloud-notes-text-disable-color',
  '--cloud-notes-black-10',
  '--cloud-notes-black-30',
  '--cloud-notes-black-50',
  '--cloud-notes-black-60',
  '--cloud-notes-black-80',
  '--cloud-notes-black-90',
  '--cloud-notes-black',
  '--cloud-notes-white-10',
  '--cloud-notes-white-30',
  '--cloud-notes-white-50',
  '--cloud-notes-white-60',
  '--cloud-notes-white-80',
  '--cloud-notes-white-90',
  '--cloud-notes-white',
  '--cloud-notes-background-code-block',
  '--cloud-notes-background-tertiary-color',
  '--cloud-notes-background-processing-color',
  '--cloud-notes-background-error-color',
  '--cloud-notes-background-warning-color',
  '--cloud-notes-background-success-color',
  '--cloud-notes-background-primary-color',
  '--cloud-notes-background-secondary-color',
  '--cloud-notes-background-modal-color',
  '--cloud-notes-background-overlay-panel-color',
  '--cloud-notes-tag-blue',
  '--cloud-notes-tag-green',
  '--cloud-notes-tag-teal',
  '--cloud-notes-tag-white',
  '--cloud-notes-tag-purple',
  '--cloud-notes-tag-red',
  '--cloud-notes-tag-pink',
  '--cloud-notes-tag-yellow',
  '--cloud-notes-tag-orange',
  '--cloud-notes-tag-gray',
  ...LINE_COLORS,
  '--cloud-notes-tooltip',
  '--cloud-notes-blue',
];

export const SizeVariables = [
  '--cloud-notes-font-h-1',
  '--cloud-notes-font-h-2',
  '--cloud-notes-font-h-3',
  '--cloud-notes-font-h-4',
  '--cloud-notes-font-h-5',
  '--cloud-notes-font-h-6',
  '--cloud-notes-font-base',
  '--cloud-notes-font-sm',
  '--cloud-notes-font-xs',
  '--cloud-notes-line-height',
  '--cloud-notes-z-index-modal',
  '--cloud-notes-z-index-popover',
];

export const FontFamilyVariables = [
  '--cloud-notes-font-family',
  '--cloud-notes-font-number-family',
  '--cloud-notes-font-code-family',
];

export const StyleVariables = [
  '--cloud-notes-editor-width',

  '--cloud-notes-theme-mode',
  '--cloud-notes-editor-mode',
  /* --cloud-notes-palette-transparent: special values added for the sake of logical consistency. */
  '--cloud-notes-palette-transparent',

  '--cloud-notes-popover-shadow',
  '--cloud-notes-menu-shadow',
  '--cloud-notes-float-button-shadow',
  '--cloud-notes-shadow-1',
  '--cloud-notes-shadow-2',
  '--cloud-notes-shadow-3',

  '--cloud-notes-paragraph-space',
  '--cloud-notes-popover-radius',
  '--cloud-notes-scale',
  ...SizeVariables,
  ...ColorVariables,
  ...FontFamilyVariables,
] as const;

type VariablesType = typeof StyleVariables;
export type CssVariableName = Extract<
  VariablesType[keyof VariablesType],
  string
>;

export type CssVariablesMap = Record<CssVariableName, string>;

export const unsafeCSSVar = (
  key: keyof CloudNotesCssVariables | keyof CloudNotesTheme,
  fallback?: string
) => unsafeCSS(cssVar(key, fallback));

export const unsafeCSSVarV2 = (key: CloudNotesThemeKeyV2, fallback?: string) =>
  unsafeCSS(cssVarV2(key, fallback));
