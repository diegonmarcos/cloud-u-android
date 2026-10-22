import type { CloudNotesTextAttributes } from '@blocksuite/cloud-notes-shared/types';
import {
  type InlineRootElement,
  InlineSpecExtension,
} from '@blocksuite/std/inline';
import type { ExtensionType } from '@blocksuite/store';
import { html } from 'lit';
import { z } from 'zod';

export type CloudNotesInlineRootElement = InlineRootElement<CloudNotesTextAttributes>;

export const BoldInlineSpecExtension =
  InlineSpecExtension<CloudNotesTextAttributes>({
    name: 'bold',
    schema: z.object({
      bold: z.literal(true).optional().nullable().catch(undefined),
    }),
    match: delta => {
      return !!delta.attributes?.bold;
    },
    renderer: ({ delta }) => {
      return html`<cloud-notes-text .delta=${delta}></cloud-notes-text>`;
    },
  });

export const ItalicInlineSpecExtension =
  InlineSpecExtension<CloudNotesTextAttributes>({
    name: 'italic',
    schema: z.object({
      italic: z.literal(true).optional().nullable().catch(undefined),
    }),
    match: delta => {
      return !!delta.attributes?.italic;
    },
    renderer: ({ delta }) => {
      return html`<cloud-notes-text .delta=${delta}></cloud-notes-text>`;
    },
  });

export const UnderlineInlineSpecExtension =
  InlineSpecExtension<CloudNotesTextAttributes>({
    name: 'underline',
    schema: z.object({
      underline: z.literal(true).optional().nullable().catch(undefined),
    }),
    match: delta => {
      return !!delta.attributes?.underline;
    },
    renderer: ({ delta }) => {
      return html`<cloud-notes-text .delta=${delta}></cloud-notes-text>`;
    },
  });

export const StrikeInlineSpecExtension =
  InlineSpecExtension<CloudNotesTextAttributes>({
    name: 'strike',
    schema: z.object({
      strike: z.literal(true).optional().nullable().catch(undefined),
    }),
    match: delta => {
      return !!delta.attributes?.strike;
    },
    renderer: ({ delta }) => {
      return html`<cloud-notes-text .delta=${delta}></cloud-notes-text>`;
    },
  });

export const CodeInlineSpecExtension =
  InlineSpecExtension<CloudNotesTextAttributes>({
    name: 'inline-code',
    schema: z.object({
      code: z.literal(true).optional().nullable().catch(undefined),
    }),
    match: delta => {
      return !!delta.attributes?.code;
    },
    renderer: ({ delta }) => {
      return html`<cloud-notes-text .delta=${delta}></cloud-notes-text>`;
    },
  });

export const BackgroundInlineSpecExtension =
  InlineSpecExtension<CloudNotesTextAttributes>({
    name: 'background',
    schema: z.object({
      background: z.string().optional().nullable().catch(undefined),
    }),
    match: delta => {
      return !!delta.attributes?.background;
    },
    renderer: ({ delta }) => {
      return html`<cloud-notes-text .delta=${delta}></cloud-notes-text>`;
    },
  });

export const ColorInlineSpecExtension =
  InlineSpecExtension<CloudNotesTextAttributes>({
    name: 'color',
    schema: z.object({
      color: z.string().optional().nullable().catch(undefined),
    }),
    match: delta => {
      return !!delta.attributes?.color;
    },
    renderer: ({ delta }) => {
      return html`<cloud-notes-text .delta=${delta}></cloud-notes-text>`;
    },
  });

export const InlineSpecExtensions: ExtensionType[] = [
  BoldInlineSpecExtension,
  ItalicInlineSpecExtension,
  UnderlineInlineSpecExtension,
  StrikeInlineSpecExtension,
  CodeInlineSpecExtension,
  BackgroundInlineSpecExtension,
  ColorInlineSpecExtension,
];
