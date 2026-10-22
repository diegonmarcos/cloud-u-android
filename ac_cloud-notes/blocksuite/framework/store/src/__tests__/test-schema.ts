import { BlockSchemaExtension } from '../extension/schema.js';
import { BlockModel, defineBlockSchema } from '../model/index.js';

export const RootBlockSchema = defineBlockSchema({
  flavour: 'cloud-notes:page',
  props: internal => ({
    title: internal.Text(),
    count: 0,
    style: {} as Record<string, unknown>,
    items: [] as unknown[],
  }),
  metadata: {
    version: 2,
    role: 'root',
  },
});

export const RootBlockSchemaExtension = BlockSchemaExtension(RootBlockSchema);

export class RootBlockModel extends BlockModel<
  ReturnType<(typeof RootBlockSchema)['model']['props']>
> {}

export const NoteBlockSchema = defineBlockSchema({
  flavour: 'cloud-notes:note',
  props: () => ({}),
  metadata: {
    version: 1,
    role: 'hub',
    parent: ['cloud-notes:page'],
    children: [
      'cloud-notes:paragraph',
      'cloud-notes:list',
      'cloud-notes:code',
      'cloud-notes:divider',
      'cloud-notes:database',
      'cloud-notes:data-view',
      'cloud-notes:image',
      'cloud-notes:note-block-*',
      'cloud-notes:bookmark',
      'cloud-notes:attachment',
      'cloud-notes:surface-ref',
      'cloud-notes:embed-*',
    ],
  },
});

export const NoteBlockSchemaExtension = BlockSchemaExtension(NoteBlockSchema);

export const ParagraphBlockSchema = defineBlockSchema({
  flavour: 'cloud-notes:paragraph',
  props: internal => ({
    type: 'text',
    text: internal.Text(),
  }),
  metadata: {
    version: 1,
    role: 'content',
    parent: [
      'cloud-notes:note',
      'cloud-notes:database',
      'cloud-notes:paragraph',
      'cloud-notes:list',
    ],
  },
});

export const ParagraphBlockSchemaExtension =
  BlockSchemaExtension(ParagraphBlockSchema);

export const ListBlockSchema = defineBlockSchema({
  flavour: 'cloud-notes:list',
  props: internal => ({
    type: 'bulleted',
    text: internal.Text(),
    checked: false,
    collapsed: false,
  }),
  metadata: {
    version: 1,
    role: 'content',
    parent: [
      'cloud-notes:note',
      'cloud-notes:database',
      'cloud-notes:list',
      'cloud-notes:paragraph',
    ],
  },
});

export const ListBlockSchemaExtension = BlockSchemaExtension(ListBlockSchema);

export const DividerBlockSchema = defineBlockSchema({
  flavour: 'cloud-notes:divider',
  metadata: {
    version: 1,
    role: 'content',
    children: [],
  },
});

export const DividerBlockSchemaExtension =
  BlockSchemaExtension(DividerBlockSchema);
