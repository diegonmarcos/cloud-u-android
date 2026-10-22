import { cssVar } from '@cloud-notes/theme';
import { style } from '@vanilla-extract/css';

export const createTips = style({
  color: cssVar('textSecondaryColor'),
  fontSize: 12,
  lineHeight: '20px',
});
