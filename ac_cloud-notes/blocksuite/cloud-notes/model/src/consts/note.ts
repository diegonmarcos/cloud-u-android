import { z } from 'zod';

import { createEnumMap } from '../utils/enum.js';

export const NOTE_MIN_WIDTH = 170 + 24 * 2;
export const NOTE_MIN_HEIGHT = 92;

export const DEFAULT_NOTE_WIDTH = 450 + 24 * 2;
export const DEFAULT_NOTE_HEIGHT = NOTE_MIN_HEIGHT;

export const DEFAULT_PAGE_BLOCK_WIDTH = 800;
export const DEFAULT_PAGE_BLOCK_HEIGHT = DEFAULT_NOTE_HEIGHT;

export enum NoteShadow {
  Box = '--cloud-notes-note-shadow-box',
  Film = '--cloud-notes-note-shadow-film',
  Float = '--cloud-notes-note-shadow-float',
  None = '',
  Paper = '--cloud-notes-note-shadow-paper',
  Sticker = '--cloud-notes-note-shadow-sticker',
}

export const NoteShadowMap = createEnumMap(NoteShadow);

export const NOTE_SHADOWS = [
  NoteShadow.None,
  NoteShadow.Box,
  NoteShadow.Sticker,
  NoteShadow.Paper,
  NoteShadow.Float,
  NoteShadow.Film,
] as const;

export const DEFAULT_NOTE_SHADOW = NoteShadow.Box;

export const NoteShadowsSchema = z.nativeEnum(NoteShadow);

export enum NoteDisplayMode {
  DocAndEdgeless = 'both',
  DocOnly = 'doc',
  EdgelessOnly = 'edgeless',
}

export enum StrokeStyle {
  Dash = 'dash',
  None = 'none',
  Solid = 'solid',
}

export const StrokeStyleSchema = z.nativeEnum(StrokeStyle);

export const NoteDisplayModeSchema = z.nativeEnum(NoteDisplayMode);

export const DEFAULT_NOTE_BORDER_STYLE = StrokeStyle.None;

export const StrokeStyleMap = createEnumMap(StrokeStyle);

export enum NoteCorners {
  Huge = 32,
  Large = 24,
  Medium = 16,
  None = 0,
  Small = 8,
}

export const NoteCornersMap = createEnumMap(NoteCorners);

export const NOTE_CORNERS = [
  NoteCorners.None,
  NoteCorners.Small,
  NoteCorners.Medium,
  NoteCorners.Large,
  NoteCorners.Huge,
] as const;

export const DEFAULT_NOTE_CORNER = NoteCorners.Small;

export const NoteCornersSchema = z.nativeEnum(NoteCorners);

export const DEFAULT_NOTE_BORDER_SIZE = 4;
