import { createEmbedBlockPlainTextAdapterMatcher } from '@blocksuite/cloud-notes-block-embed';
import { BookmarkBlockSchema } from '@blocksuite/cloud-notes-model';
import { BlockPlainTextAdapterExtension } from '@blocksuite/cloud-notes-shared/adapters';

export const bookmarkBlockPlainTextAdapterMatcher =
  createEmbedBlockPlainTextAdapterMatcher(BookmarkBlockSchema.model.flavour);

export const BookmarkBlockPlainTextAdapterExtension =
  BlockPlainTextAdapterExtension(bookmarkBlockPlainTextAdapterMatcher);
