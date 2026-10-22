import { MarkdownASTToDeltaExtension } from '@blocksuite/cloud-notes-shared/adapters';

export const markdownInlineMathToDeltaMatcher = MarkdownASTToDeltaExtension({
  name: 'inlineMath',
  match: ast => ast.type === 'inlineMath',
  toDelta: ast => {
    if (!('value' in ast)) {
      return [];
    }
    return [{ insert: ' ', attributes: { latex: ast.value } }];
  },
});
