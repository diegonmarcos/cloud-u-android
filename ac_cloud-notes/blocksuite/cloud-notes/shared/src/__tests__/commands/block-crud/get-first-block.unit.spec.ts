/**
 * @vitest-environment happy-dom
 */
import { describe, expect, it } from 'vitest';

import { getFirstBlockCommand } from '../../../commands/block-crud/get-first-content-block';
import { cloudNotes } from '../../../test-utils';

describe('commands/block-crud', () => {
  describe('getFirstBlockCommand', () => {
    it('should return null when root is not exists', () => {
      const host = cloudNotes`<cloud-notes-page></cloud-notes-page>`;

      const [_, { firstBlock }] = host.command.exec(getFirstBlockCommand, {
        role: 'content',
        root: undefined,
      });

      expect(firstBlock).toBeNull();
    });

    it('should return first block with content role when found', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1-1">First Paragraph</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-1-2">Second Paragraph</cloud-notes-paragraph>
          </cloud-notes-note>
          <cloud-notes-note id="note-2">
            <cloud-notes-paragraph id="paragraph-2-1">First Paragraph</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-2-2">Second Paragraph</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const [_, { firstBlock }] = host.command.exec(getFirstBlockCommand, {
        role: 'hub',
        root: undefined,
      });

      expect(firstBlock?.id).toBe('note-1');
    });

    it('should return first block with any role in the array when found', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1-1">First Paragraph</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-1-2">Second Paragraph</cloud-notes-paragraph>
          </cloud-notes-note>
          <cloud-notes-note id="note-2">
            <cloud-notes-paragraph id="paragraph-2-1">First Paragraph</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-2-2">Second Paragraph</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const [_, { firstBlock }] = host.command.exec(getFirstBlockCommand, {
        role: ['hub', 'content'],
        root: undefined,
      });

      expect(firstBlock?.id).toBe('note-1');
    });

    it('should return first block with specified flavour when found', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1">Paragraph</cloud-notes-paragraph>
            <cloud-notes-list id="list-1">List Item</cloud-notes-list>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const note = host.store.getBlock('note-1')?.model;

      const [_, { firstBlock }] = host.command.exec(getFirstBlockCommand, {
        flavour: 'affine:list',
        root: note,
      });

      expect(firstBlock?.id).toBe('list-1');
    });

    it('should return first block with any flavour in the array when found', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1">Paragraph</cloud-notes-paragraph>
            <cloud-notes-list id="list-1">List Item</cloud-notes-list>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const note = host.store.getBlock('note-1')?.model;

      const [_, { firstBlock }] = host.command.exec(getFirstBlockCommand, {
        flavour: ['affine:list', 'affine:code'],
        root: note,
      });

      expect(firstBlock?.id).toBe('list-1');
    });

    it('should return first block matching both role and flavour when both specified', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1">Content Paragraph</cloud-notes-paragraph>
            <cloud-notes-list id="list-1">Content List</cloud-notes-list>
            <cloud-notes-paragraph id="paragraph-2">hub Paragraph</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const note = host.store.getBlock('note-1')?.model;
      const [_, { firstBlock }] = host.command.exec(getFirstBlockCommand, {
        role: 'content',
        flavour: 'affine:list',
        root: note,
      });

      expect(firstBlock?.id).toBe('list-1');
    });

    it('should return first block with default roles when role not specified', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1">hub Paragraph</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-2">Content Paragraph</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-3">Hub Paragraph</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const [_, { firstBlock }] = host.command.exec(getFirstBlockCommand, {
        root: undefined,
      });

      expect(firstBlock?.id).toBe('note-1');
    });

    it('should return first block with specified role when found', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1">Content Paragraph</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-2">hub Paragraph</cloud-notes-paragraph>
            <cloud-notes-database id="database-1">Database</cloud-notes-database>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const note = host.store.getBlock('note-1')?.model;

      const [_, { firstBlock }] = host.command.exec(getFirstBlockCommand, {
        role: 'hub',
        root: note,
      });

      expect(firstBlock?.id).toBe('database-1');
    });

    it('should return null when no blocks with specified role are found in children', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1">Content Paragraph</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-2">Another Content Paragraph</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const note = host.store.getBlock('note-1')?.model;

      const [_, { firstBlock }] = host.command.exec(getFirstBlockCommand, {
        role: 'hub',
        root: note,
      });

      expect(firstBlock).toBeNull();
    });

    it('should return null when no blocks with specified flavour are found in children', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1">Paragraph</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-2">Another Paragraph</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const note = host.store.getBlock('note-1')?.model;

      const [_, { firstBlock }] = host.command.exec(getFirstBlockCommand, {
        flavour: 'affine:list',
        root: note,
      });

      expect(firstBlock).toBeNull();
    });

    it('should return first block with specified role within specified root subtree', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1-1">1-1 Content</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-1-2">1-2 hub</cloud-notes-paragraph>
          </cloud-notes-note>
          <cloud-notes-note id="note-2">
            <cloud-notes-paragraph id="paragraph-2-1">2-1 hub</cloud-notes-paragraph>
            <cloud-notes-paragraph id="paragraph-2-2">2-2 Content</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      const note = host.store.getBlock('note-2')?.model;

      const [_, { firstBlock }] = host.command.exec(getFirstBlockCommand, {
        role: 'content',
        root: note,
      });

      expect(firstBlock?.id).toBe('paragraph-2-1');
    });
  });
});
