import { cssVar } from '@cloud-notes/theme';
import { cssVarV2 } from '@cloud-notes/theme/v2';
import { style } from '@vanilla-extract/css';
export const containerStyle = style({
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: cssVar('fontXs'),
  fontWeight: 500,
  lineHeight: '20px',
  padding: '0 4px',
  color: cssVarV2('button/pureWhiteText'),
  backgroundColor: cssVarV2('button/primary'),
  borderRadius: '4px',
});
