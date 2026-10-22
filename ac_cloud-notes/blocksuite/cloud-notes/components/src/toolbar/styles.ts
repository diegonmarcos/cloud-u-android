import {
  type CloudNotesCssVariables,
  combinedDarkCssVariables,
  combinedLightCssVariables,
} from '@cloud-notes/theme';
import { unsafeCSS } from 'lit';

const toolbarColorKeys: Array<keyof CloudNotesCssVariables> = [
  '--cloud-notes-background-overlay-panel-color',
  '--cloud-notes-v2-layer-background-overlayPanel' as never,
  '--cloud-notes-v2-layer-insideBorder-blackBorder' as never,
  '--cloud-notes-v2-icon-primary' as never,
  '--cloud-notes-background-error-color',
  '--cloud-notes-background-primary-color',
  '--cloud-notes-background-tertiary-color',
  '--cloud-notes-icon-color',
  '--cloud-notes-icon-secondary',
  '--cloud-notes-border-color',
  '--cloud-notes-divider-color',
  '--cloud-notes-text-primary-color',
  '--cloud-notes-hover-color',
  '--cloud-notes-hover-color-filled',
];

export const lightToolbarStyles = (selector: string) => `
  ${selector}[data-app-theme='light'] {
    ${toolbarColorKeys
      .map(key => `${key}: ${unsafeCSS(combinedLightCssVariables[key])};`)
      .join('\n')}
  }
`;

export const darkToolbarStyles = (selector: string) => `
  ${selector}[data-app-theme='dark'] {
    ${toolbarColorKeys
      .map(key => `${key}: ${unsafeCSS(combinedDarkCssVariables[key])};`)
      .join('\n')}
  }
`;
