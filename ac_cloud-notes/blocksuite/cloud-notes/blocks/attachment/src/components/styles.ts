import { fontXSStyle, panelBaseStyle } from '@blocksuite/cloud-notes-shared/styles';
import { css } from 'lit';

export const renameStyles = css`
  ${panelBaseStyle('.cloud-notes-attachment-rename-container')}
  .cloud-notes-attachment-rename-container {
    position: relative;
    display: flex;
    align-items: center;
    width: 320px;
    gap: 12px;
    padding: 12px;
    z-index: var(--cloud-notes-z-index-popover);
  }

  .cloud-notes-attachment-rename-input-wrapper {
    display: flex;
    min-width: 280px;
    height: 30px;
    box-sizing: border-box;
    padding: 4px 10px;
    background: var(--cloud-notes-white-10);
    border-radius: 4px;
    border: 1px solid var(--cloud-notes-border-color);
  }

  .cloud-notes-attachment-rename-input-wrapper:focus-within {
    border-color: var(--cloud-notes-blue-700);
    box-shadow: var(--cloud-notes-active-shadow);
  }

  .cloud-notes-attachment-rename-input-wrapper input {
    flex: 1;
    border: none;
    outline: none;
    background: transparent;
    color: var(--cloud-notes-text-primary-color);
  }
  ${fontXSStyle('.cloud-notes-attachment-rename-input-wrapper input')}

  .cloud-notes-attachment-rename-input-wrapper input::placeholder {
    color: var(--cloud-notes-placeholder-color);
  }

  .cloud-notes-attachment-rename-extension {
    font-size: var(--cloud-notes-font-xs);
    color: var(--cloud-notes-text-secondary-color);
  }

  .cloud-notes-attachment-rename-overlay-mask {
    position: fixed;
    top: 0;
    left: 0;
    width: 100vw;
    height: 100vh;
    z-index: var(--cloud-notes-z-index-popover);
  }
`;

export const styles = css`
  :host {
    z-index: 1;
  }
`;
