import { style } from '@vanilla-extract/css';
export const editor = style({
  flex: 1,
  selectors: {
    '&.full-screen': {
      width: '100%',
      minWidth: 0,
      vars: {
        '--cloud-notes-editor-width': '100%',
        '--cloud-notes-editor-side-padding': '72px',
      },
    },
  },
  '@media': {
    'screen and (max-width: 800px)': {
      selectors: {
        '&.is-public': {
          vars: {
            '--cloud-notes-editor-width': '100%',
            '--cloud-notes-editor-side-padding': '24px',
          },
        },
      },
    },
  },
});
