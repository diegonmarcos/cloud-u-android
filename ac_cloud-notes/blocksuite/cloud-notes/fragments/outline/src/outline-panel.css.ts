import { cssVar } from '@cloud-notes/theme';
import { cssVarV2 } from '@cloud-notes/theme/v2';
import { style } from '@vanilla-extract/css';

export const outlinePanel = style({
  display: 'flex',
  flexDirection: 'column',
  backgroundColor: cssVarV2('layer/background/primary'),
  boxSizing: 'border-box',
  width: '100%',
  height: '100%',
  fontFamily: cssVar('fontSansFamily'),
  paddingTop: '8px',
  position: 'relative',
});
