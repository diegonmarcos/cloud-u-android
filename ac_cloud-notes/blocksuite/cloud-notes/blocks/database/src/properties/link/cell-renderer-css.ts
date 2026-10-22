import { cssVarV2 } from '@blocksuite/cloud-notes-shared/theme';
import { css } from '@emotion/css';
import { baseTheme } from '@cloud-notes/theme';

export const linkCellStyle = css({
  width: '100%',
  height: '100%',
  userSelect: 'none',
  position: 'relative',
});

export const linkContainerStyle = css({
  display: 'flex',
  position: 'relative',
  alignItems: 'center',
  width: '100%',
  height: '100%',
  outline: 'none',
  overflow: 'hidden',
  fontSize: 'var(--data-view-cell-text-size)',
  lineHeight: 'var(--data-view-cell-text-line-height)',
  wordBreak: 'break-all',
});

export const linkIconContainerStyle = css({
  position: 'absolute',
  right: '8px',
  top: '8px',
  display: 'flex',
  alignItems: 'center',
  visibility: 'hidden',
  backgroundColor: cssVarV2.layer.background.primary,
  boxShadow: 'var(--cloud-notes-button-shadow)',
  borderRadius: '4px',
  overflow: 'hidden',
  zIndex: 1,
});

export const linkIconStyle = css({
  width: '100%',
  height: '100%',
  display: 'flex',
  alignItems: 'center',
  cursor: 'pointer',
  color: cssVarV2.icon.primary,
  fontSize: '14px',
  padding: '2px',
  ':hover': {
    backgroundColor: cssVarV2.layer.background.hoverOverlay,
  },
});

export const showLinkIconStyle = css({
  [`.${linkCellStyle}:hover &`]: {
    visibility: 'visible',
  },
});

export const linkedDocStyle = css({
  textDecoration: 'underline',
  textDecorationColor: 'var(--cloud-notes-divider-color)',
  transition: 'text-decoration-color 0.2s ease-out',
  cursor: 'pointer',
  ':hover': {
    textDecorationColor: 'var(--cloud-notes-icon-color)',
  },
});

export const linkEditingStyle = css({
  display: 'flex',
  alignItems: 'center',
  width: '100%',
  padding: '0',
  border: 'none',
  fontFamily: baseTheme.fontSansFamily,
  color: 'var(--cloud-notes-text-primary-color)',
  fontWeight: 400,
  backgroundColor: 'transparent',
  fontSize: 'var(--data-view-cell-text-size)',
  lineHeight: 'var(--data-view-cell-text-line-height)',
  wordBreak: 'break-all',
  ':focus': {
    outline: 'none',
  },
});

export const inlineLinkNodeStyle = css({
  wordBreak: 'break-all',
  color: 'var(--cloud-notes-link-color)',
  fill: 'var(--cloud-notes-link-color)',
  cursor: 'pointer',
  fontWeight: 'normal',
  fontStyle: 'normal',
  textDecoration: 'none',
});

export const normalTextStyle = css({
  wordBreak: 'break-all',
});
