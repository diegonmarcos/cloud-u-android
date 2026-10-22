import { createEmbedBlockHtmlAdapterMatcher } from '@blocksuite/cloud-notes-block-embed';
import { BookmarkBlockSchema } from '@blocksuite/cloud-notes-model';
import { BlockHtmlAdapterExtension } from '@blocksuite/cloud-notes-shared/adapters';

export const bookmarkBlockHtmlAdapterMatcher =
  createEmbedBlockHtmlAdapterMatcher(BookmarkBlockSchema.model.flavour);

export const BookmarkBlockHtmlAdapterExtension = BlockHtmlAdapterExtension(
  bookmarkBlockHtmlAdapterMatcher
);
