import { TextSelection } from '@blocksuite/std';
import { describe, expect, it } from 'vitest';

import { cloudNotes } from '../../test-utils';

describe('helpers/cloud-notes-template', () => {
  it('should create a basic document structure from template', () => {
    const host = cloudNotes`
      <cloud-notes-page id="page">
        <cloud-notes-note id="note">
          <cloud-notes-paragraph id="paragraph-1">Hello, world</cloud-notes-paragraph>
        </cloud-notes-note>
      </cloud-notes-page>
    `;

    expect(host.store).toBeDefined();

    const pageBlock = host.store.getBlock('page');
    expect(pageBlock).toBeDefined();
    expect(pageBlock?.flavour).toBe('cloud-notes:page');

    const noteBlock = host.store.getBlock('note');
    expect(noteBlock).toBeDefined();
    expect(noteBlock?.flavour).toBe('cloud-notes:note');

    const paragraphBlock = host.store.getBlock('paragraph-1');
    expect(paragraphBlock).toBeDefined();
    expect(paragraphBlock?.flavour).toBe('cloud-notes:paragraph');
  });

  it('should handle nested blocks correctly', () => {
    const host = cloudNotes`
      <cloud-notes-page>
        <cloud-notes-note>
          <cloud-notes-paragraph>First paragraph</cloud-notes-paragraph>
          <cloud-notes-list>List item</cloud-notes-list>
          <cloud-notes-paragraph>Second paragraph</cloud-notes-paragraph>
        </cloud-notes-note>
      </cloud-notes-page>
    `;

    const noteBlocks = host.store.getBlocksByFlavour('cloud-notes:note');
    const paragraphBlocks = host.store.getBlocksByFlavour('cloud-notes:paragraph');
    const listBlocks = host.store.getBlocksByFlavour('cloud-notes:list');

    expect(noteBlocks.length).toBe(1);
    expect(paragraphBlocks.length).toBe(2);
    expect(listBlocks.length).toBe(1);

    const noteBlock = noteBlocks[0];
    const noteChildren =
      host.store.getBlock(noteBlock.id)?.model.children || [];
    expect(noteChildren.length).toBe(3);

    expect(noteChildren[0].flavour).toBe('cloud-notes:paragraph');
    expect(noteChildren[1].flavour).toBe('cloud-notes:list');
    expect(noteChildren[2].flavour).toBe('cloud-notes:paragraph');
  });

  it('should handle empty blocks correctly', () => {
    const host = cloudNotes`
      <cloud-notes-page>
        <cloud-notes-note>
          <cloud-notes-paragraph></cloud-notes-paragraph>
        </cloud-notes-note>
      </cloud-notes-page>
    `;

    const paragraphBlocks = host.store.getBlocksByFlavour('cloud-notes:paragraph');
    expect(paragraphBlocks.length).toBe(1);

    const paragraphBlock = host.store.getBlock(paragraphBlocks[0].id);
    const paragraphText = paragraphBlock?.model.text?.toString() || '';
    expect(paragraphText).toBe('');
  });

  it('should throw error on invalid template', () => {
    expect(() => {
      cloudNotes`
        <unknown-tag></unknown-tag>
      `;
    }).toThrow();
  });

  it('should handle text selection with anchor and focus', () => {
    const host = cloudNotes`
      <cloud-notes-page id="page">
        <cloud-notes-note id="note">
          <cloud-notes-paragraph id="paragraph-1">Hel<anchor />lo</cloud-notes-paragraph>
          <cloud-notes-paragraph id="paragraph-2">Wo<focus />rld</cloud-notes-paragraph>
        </cloud-notes-note>
      </cloud-notes-page>
    `;

    const selection = host.selection.value[0] as TextSelection;
    expect(selection).toBeDefined();
    expect(selection.is(TextSelection)).toBe(true);
    expect(selection.from.blockId).toBe('paragraph-1');
    expect(selection.from.index).toBe(3);
    expect(selection.from.length).toBe(2);
    expect(selection.to?.blockId).toBe('paragraph-2');
    expect(selection.to?.index).toBe(0);
    expect(selection.to?.length).toBe(2);
  });

  it('should handle cursor position', () => {
    const host = cloudNotes`
      <cloud-notes-page id="page">
        <cloud-notes-note id="note">
          <cloud-notes-paragraph id="paragraph-1">Hello<cursor />World</cloud-notes-paragraph>
        </cloud-notes-note>
      </cloud-notes-page>
    `;

    const selection = host.selection.value[0] as TextSelection;
    expect(selection).toBeDefined();
    expect(selection.is(TextSelection)).toBe(true);
    expect(selection.from.blockId).toBe('paragraph-1');
    expect(selection.from.index).toBe(5);
    expect(selection.from.length).toBe(0);
    expect(selection.to).toBeNull();
  });

  it('should handle selection in empty blocks', () => {
    const host = cloudNotes`
      <cloud-notes-page id="page">
        <cloud-notes-note id="note">
          <cloud-notes-paragraph id="paragraph-1"><cursor /></cloud-notes-paragraph>
        </cloud-notes-note>
      </cloud-notes-page>
    `;

    const selection = host.selection.value[0] as TextSelection;
    expect(selection).toBeDefined();
    expect(selection.is(TextSelection)).toBe(true);
    expect(selection.from.blockId).toBe('paragraph-1');
    expect(selection.from.index).toBe(0);
    expect(selection.from.length).toBe(0);
    expect(selection.to).toBeNull();
  });

  it('should handle single point selection', () => {
    const host = cloudNotes`
      <cloud-notes-page id="page">
        <cloud-notes-note id="note">
          <cloud-notes-paragraph id="paragraph-1">Hello<anchor></anchor>World<focus></focus>Cloud Notes</cloud-notes-paragraph>
        </cloud-notes-note>
      </cloud-notes-page>
    `;

    const selection = host.selection.value[0] as TextSelection;
    expect(selection).toBeDefined();
    expect(selection.is(TextSelection)).toBe(true);
    expect(selection.from.blockId).toBe('paragraph-1');
    expect(selection.from.index).toBe(5);
    expect(selection.from.length).toBe(5);
    expect(selection.to).toBeNull();
  });
});
