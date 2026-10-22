import { bodyEmphasized } from '@cloud-notes/theme/typography';
import { cssVarV2 } from '@cloud-notes/theme/v2';
import { style } from '@vanilla-extract/css';

export const card = style({
  display: 'flex',
  alignItems: 'center',
  gap: 10,
});

export const label = style([
  bodyEmphasized,
  {
    display: 'flex',
    gap: 4,
    color: cssVarV2('text/primary'),
  },
]);

export const dropdownIcon = style({
  fontSize: 24,
  color: cssVarV2('icon/primary'),
});
