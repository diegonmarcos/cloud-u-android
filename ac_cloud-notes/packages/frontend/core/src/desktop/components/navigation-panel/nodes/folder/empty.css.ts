import { cssVar } from '@cloud-notes/theme';
import { style } from '@vanilla-extract/css';

export const draggedOverHighlight = style({
  selectors: {
    '&[data-dragged-over="true"]': {
      background: cssVar('--cloud-notes-hover-color'),
      borderRadius: '4px',
    },
  },
});
