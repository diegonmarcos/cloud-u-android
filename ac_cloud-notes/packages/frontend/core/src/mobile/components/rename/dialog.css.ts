import { bodyEmphasized } from '@cloud-notes/theme/typography';
import { cssVarV2 } from '@cloud-notes/theme/v2';
import { style } from '@vanilla-extract/css';

export const header = style({
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  gap: 8,
  padding: '10px 16px',
});
export const title = style([
  bodyEmphasized,
  {
    color: cssVarV2('text/primary'),
  },
]);
