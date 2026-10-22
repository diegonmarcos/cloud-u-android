import { cssVar } from '@cloud-notes/theme';
import { style } from '@vanilla-extract/css';

export const errorMessage = style({
  color: cssVar('errorColor'),
});
