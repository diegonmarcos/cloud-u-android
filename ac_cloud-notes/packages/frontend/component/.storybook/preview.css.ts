import { globalStyle } from '@vanilla-extract/css';

globalStyle('*', {
  margin: 0,
  padding: 0,
});

globalStyle('body', {
  color: 'var(--cloud-notes-text-primary-color)',
  fontFamily: 'var(--cloud-notes-font-family)',
  fontSize: 'var(--cloud-notes-font-base)',
  lineHeight: 'var(--cloud-notes-font-height)',
  backgroundColor: 'var(--cloud-notes-background-primary-color)',
});

globalStyle('.docs-story', {
  backgroundColor: 'var(--cloud-notes-background-primary-color)',
});

globalStyle('body.sb-main-fullscreen', {
  overflowY: 'auto',
});
