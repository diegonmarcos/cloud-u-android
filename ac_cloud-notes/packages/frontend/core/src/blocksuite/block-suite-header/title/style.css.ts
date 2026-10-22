import { cssVar } from '@cloud-notes/theme';
import { style } from '@vanilla-extract/css';
export const title = style({
  fontWeight: 500,
  color: cssVar('textPrimaryColor'),
  selectors: {
    '&[data-editing="true"]': {
      ['WebkitAppRegion' as string]: 'no-drag',
    },
  },
});
