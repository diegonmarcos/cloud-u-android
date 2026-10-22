/**
 * @vitest-environment happy-dom
 */
import type { TextSelection } from '@blocksuite/std';
import { describe, expect, it } from 'vitest';

import { replaceSelectedTextWithBlocksCommand } from '../../../commands/model-crud/replace-selected-text-with-blocks';
import { cloudNotes, block } from '../../../test-utils';

describe('commands/model-crud', () => {
  describe('replaceSelectedTextWithBlocksCommand', () => {
    it('should replace selected text with blocks when both first and last blocks are mergable blocks', () => {
      const host = cloudNotes`
        <cloud-notes-page id="page">
          <cloud-notes-note id="note">
            <cloud-notes-paragraph id="paragraph-1">Hel<anchor />lo</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-2">Wor<focus />ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [
        block`<cloud-notes-paragraph id="111">111</cloud-notes-paragraph>`,
        block`<cloud-notes-code id="code"></cloud-notes-code>`,
        block`<cloud-notes-paragraph id="222">222</cloud-notes-paragraph>`,
      ]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page id="page">
          <cloud-notes-note id="note">
            <cloud-notes-paragraph id="paragraph-1">Hel111</cloud-notes-paragraph>
            <cloud-notes-code id="code"></cloud-notes-code>
            <cloud-notes-paragraph id="paragraph-2">222ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when both first and last blocks are mergable blocks in single paragraph', () => {
      const host = cloudNotes`
        <cloud-notes-page id="page">
          <cloud-notes-note id="note">
            <cloud-notes-paragraph id="paragraph-1">Hel<anchor></anchor>lo Wor<focus></focus>ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [
        block`<cloud-notes-paragraph id="111">111</cloud-notes-paragraph>`,
        block`<cloud-notes-code id="code"></cloud-notes-code>`,
        block`<cloud-notes-paragraph id="222">222</cloud-notes-paragraph>`,
      ]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page id="page">
          <cloud-notes-note id="note">
            <cloud-notes-paragraph id="paragraph-1">Hel111</cloud-notes-paragraph>
            <cloud-notes-code id="code"></cloud-notes-code>
            <cloud-notes-paragraph id="222">222ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when blocks contains only one mergable block', () => {
      const host = cloudNotes`
        <cloud-notes-page id="page">
          <cloud-notes-note id="note">
            <cloud-notes-paragraph id="paragraph-1">Hel<anchor />lo</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-2">Wor<focus />ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [block`<cloud-notes-paragraph id="111">111</cloud-notes-paragraph>`]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page id="page">
          <cloud-notes-note id="note">
            <cloud-notes-paragraph id="paragraph-1">Hel111ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when blocks contains only one mergable block in single paragraph', () => {
      const host = cloudNotes`
        <cloud-notes-page id="page">
          <cloud-notes-note id="note">
            <cloud-notes-paragraph id="paragraph-1">Hel<anchor></anchor>lo Wor<focus></focus>ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [block`<cloud-notes-paragraph id="111">111</cloud-notes-paragraph>`]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page id="page">
          <cloud-notes-note id="note">
            <cloud-notes-paragraph id="paragraph-1">Hel111ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when only first block is mergable block', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-paragraph>Hel<anchor />lo</cloud-notes-paragraph>
            <cloud-notes-paragraph>Wor<focus />ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [
        block`<cloud-notes-paragraph>111</cloud-notes-paragraph>`,
        block`<cloud-notes-code></cloud-notes-code>`,
        block`<cloud-notes-code></cloud-notes-code>`,
      ]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note >
            <cloud-notes-paragraph>Hel111</cloud-notes-paragraph>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-paragraph>ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when only first block is mergable block in single paragraph', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-paragraph>Hel<anchor></anchor>lo Wor<focus></focus>ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [
        block`<cloud-notes-paragraph>111</cloud-notes-paragraph>`,
        block`<cloud-notes-code></cloud-notes-code>`,
        block`<cloud-notes-code></cloud-notes-code>`,
      ]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-paragraph>Hel111</cloud-notes-paragraph>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-paragraph>ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when only last block is mergable block', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-paragraph>Hel<anchor />lo</cloud-notes-paragraph>
            <cloud-notes-paragraph>Wor<focus />ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [
        block`<cloud-notes-code></cloud-notes-code>`,
        block`<cloud-notes-code></cloud-notes-code>`,
        block`<cloud-notes-paragraph>111</cloud-notes-paragraph>`,
      ]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note >
            <cloud-notes-paragraph>Hel</cloud-notes-paragraph>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-paragraph>111ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;
      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when only last block is mergable block in single paragraph', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-paragraph>Hel<anchor></anchor>lo Wor<focus></focus>ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [
        block`<cloud-notes-code></cloud-notes-code>`,
        block`<cloud-notes-code></cloud-notes-code>`,
        block`<cloud-notes-paragraph>111</cloud-notes-paragraph>`,
      ]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-paragraph>Hel</cloud-notes-paragraph>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-paragraph>111ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;
      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when neither first nor last block is mergable block', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-paragraph>Hel<anchor />lo</cloud-notes-paragraph>
            <cloud-notes-paragraph>Wor<focus />ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [
        block`<cloud-notes-code></cloud-notes-code>`,
        block`<cloud-notes-code></cloud-notes-code>`,
      ]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note >
            <cloud-notes-paragraph>Hel</cloud-notes-paragraph>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-paragraph>ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;
      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when neither first nor last block is mergable block in single paragraph', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-paragraph>Hel<anchor></anchor>lo Wor<focus></focus>ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [
        block`<cloud-notes-code></cloud-notes-code>`,
        block`<cloud-notes-code></cloud-notes-code>`,
      ]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-paragraph>Hel</cloud-notes-paragraph>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-paragraph>ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;
      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when both first and last blocks are mergable blocks with different types', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-paragraph>Hel<anchor />lo</cloud-notes-paragraph>
            <cloud-notes-paragraph>Wor<focus />ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [
        block`<cloud-notes-list>1.</cloud-notes-list>`,
        block`<cloud-notes-list>2.</cloud-notes-list>`,
      ]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note >
            <cloud-notes-paragraph>Hel</cloud-notes-paragraph>
            <cloud-notes-list>1.</cloud-notes-list>
            <cloud-notes-list>2.</cloud-notes-list>
            <cloud-notes-paragraph>ld</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;
      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when both first and last blocks are paragraphs, and cursor is at the end of the text-block with different types', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-list>Hel<anchor />lo</cloud-notes-list>
            <cloud-notes-list>Wor<focus />ld</cloud-notes-list>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [
        block`<cloud-notes-paragraph>111</cloud-notes-paragraph>`,
        block`<cloud-notes-paragraph>222</cloud-notes-paragraph>`,
      ]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note >
            <cloud-notes-list>Hel111</cloud-notes-list>
            <cloud-notes-list>222ld</cloud-notes-list>
          </cloud-notes-note>
        </cloud-notes-page>
      `;
      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when first block is paragraph, and cursor is at the end of the text-block with different type  ', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-list>Hel<anchor />lo</cloud-notes-list>
            <cloud-notes-list>Wor<focus />ld</cloud-notes-list>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [
        block`<cloud-notes-paragraph>111</cloud-notes-paragraph>`,
        block`<cloud-notes-code></cloud-notes-code>`,
      ]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note >
            <cloud-notes-list>Hel111</cloud-notes-list>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-list>ld</cloud-notes-list>
          </cloud-notes-note>
        </cloud-notes-page>
      `;
      expect(host.store).toEqualDoc(expected.store);
    });

    it('should replace selected text with blocks when last block is paragraph, and cursor is at the end of the text-block with different type  ', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note>
            <cloud-notes-list>Hel<anchor />lo</cloud-notes-list>
            <cloud-notes-list>Wor<focus />ld</cloud-notes-list>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const blocks = [
        block`<cloud-notes-code></cloud-notes-code>`,
        block`<cloud-notes-paragraph>222</cloud-notes-paragraph>`,
      ]
        .filter((b): b is NonNullable<typeof b> => b !== null)
        .map(b => b.model);

      const textSelection = host.selection.value[0] as TextSelection;

      host.command.exec(replaceSelectedTextWithBlocksCommand, {
        textSelection,
        blocks,
      });

      const expected = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note >
            <cloud-notes-list>Hel</cloud-notes-list>
            <cloud-notes-code></cloud-notes-code>
            <cloud-notes-list>222ld</cloud-notes-list>
          </cloud-notes-note>
        </cloud-notes-page>
      `;
      expect(host.store).toEqualDoc(expected.store);
    });
  });
});
