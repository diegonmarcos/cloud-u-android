import { cssVar } from '@cloud-notes/theme';
import { style } from '@vanilla-extract/css';

export const errorMessage = style({
  color: cssVar('--cloud-notes-error-color'),
  fontSize: '0.6rem',
  margin: '4px 8px 2px 2px',
});
