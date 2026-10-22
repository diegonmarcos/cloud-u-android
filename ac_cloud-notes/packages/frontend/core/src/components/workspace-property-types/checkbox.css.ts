import { cssVarV2 } from '@cloud-notes/theme/v2';
import { style } from '@vanilla-extract/css';

export const checkboxProperty = style({
  fontSize: 24,
  color: cssVarV2('icon/primary'),
});

export const container = style({
  padding: 4,
});
