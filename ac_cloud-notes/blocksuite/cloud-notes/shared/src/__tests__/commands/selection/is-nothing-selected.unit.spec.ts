/**
 * @vitest-environment happy-dom
 */
import { BlockSelection, TextSelection } from '@blocksuite/std';
import { describe, expect, it, vi } from 'vitest';

import { isNothingSelectedCommand } from '../../../commands/selection/is-nothing-selected';
import { ImageSelection } from '../../../selection';
import { cloudNotes } from '../../../test-utils';

describe('commands/selection', () => {
  describe('isNothingSelectedCommand', () => {
    it('should return true when nothing is selected', () => {
      const host = cloudNotes`<cloud-notes-page></cloud-notes-page>`;

      const [_, { isNothingSelected }] = host.command.exec(
        isNothingSelectedCommand,
        {}
      );

      expect(isNothingSelected).toBe(true);
    });

    it('should return false when text selection exists', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1">Test paragraph</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      // Mock text selection
      const textSelection = new TextSelection({
        from: {
          blockId: 'paragraph-1',
          index: 0,
          length: 0,
        },
        to: {
          blockId: 'paragraph-1',
          index: 4,
          length: 0,
        },
      });

      const [_, { isNothingSelected }] = host.command.exec(
        isNothingSelectedCommand,
        {
          currentTextSelection: textSelection,
        }
      );

      expect(isNothingSelected).toBe(false);
    });

    it('should return false when block selection exists', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1">Test paragraph</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      // Mock block selection
      const blockSelection = new BlockSelection({
        blockId: 'paragraph-1',
      });

      const [_, { isNothingSelected }] = host.command.exec(
        isNothingSelectedCommand,
        {
          currentBlockSelections: [blockSelection],
        }
      );

      expect(isNothingSelected).toBe(false);
    });

    it('should return false when image selection exists', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-image id="image-1">Test paragraph</cloud-notes-image>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      // Mock image selection
      const imageSelection = new ImageSelection({
        blockId: 'image-1',
      });

      const [_, { isNothingSelected }] = host.command.exec(
        isNothingSelectedCommand,
        {
          currentImageSelections: [imageSelection],
        }
      );

      expect(isNothingSelected).toBe(false);
    });

    it('should return false when no selection is provided but selection is found in context', () => {
      const host = cloudNotes`
        <cloud-notes-page>
          <cloud-notes-note id="note-1">
            <cloud-notes-paragraph id="paragraph-1">Test paragraph</cloud-notes-paragraph>
          </cloud-notes-note>
        </cloud-notes-page>
      `;

      // Mock selection behavior via vi.spyOn before executing the command
      const mockTextSelection = new TextSelection({
        from: {
          blockId: 'paragraph-1',
          index: 0,
          length: 0,
        },
        to: null,
      });

      const mockContext = {
        // No explicit `currentTextSelection provided
        std: {
          selection: {
            find: vi.fn().mockImplementation(type => {
              if (type === TextSelection) {
                return mockTextSelection;
              }
              return null;
            }),
            filter: vi.fn().mockReturnValue([]),
          },
        },
      };

      const [_, { isNothingSelected }] = host.command.exec(
        isNothingSelectedCommand,
        mockContext as any
      );

      expect(isNothingSelected).toBe(false);
    });
  });
});
