import { unsafeCSSVarV2 } from '@blocksuite/cloud-notes-shared/theme';
import { css } from 'lit';

export const styles = css`
  .cloud-notes-attachment-container {
    border-radius: 8px;
    box-sizing: border-box;
    user-select: none;
    overflow: hidden;
    border: 1px solid ${unsafeCSSVarV2('layer/background/tertiary')};
    background: ${unsafeCSSVarV2('layer/background/primary')};

    &.focused {
      border-color: ${unsafeCSSVarV2('layer/insideBorder/primaryBorder')};
    }
  }

  .cloud-notes-attachment-container.comment-highlighted {
    outline: 2px solid ${unsafeCSSVarV2('block/comment/highlightUnderline')};
  }

  .cloud-notes-attachment-card {
    display: flex;
    gap: 12px;
    padding: 12px;
  }

  .cloud-notes-attachment-content {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
    flex: 1 0 0;
    min-width: 0;
  }

  .truncate {
    align-self: stretch;
    text-overflow: ellipsis;
    white-space: nowrap;
    overflow: hidden;
  }

  .cloud-notes-attachment-content-title {
    display: flex;
    flex-direction: row;
    gap: 8px;
    align-items: center;
    align-self: stretch;
  }

  .cloud-notes-attachment-content-title-icon {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--cloud-notes-text-primary-color);
    font-size: 16px;
  }

  .cloud-notes-attachment-content-title-text {
    color: var(--cloud-notes-text-primary-color);
    font-family: var(--cloud-notes-font-family);
    font-size: var(--cloud-notes-font-sm);
    font-style: normal;
    font-weight: 600;
    line-height: 22px;
  }

  .cloud-notes-attachment-content-description {
    display: flex;
    align-items: center;
    align-self: stretch;
    gap: 8px;
  }

  .cloud-notes-attachment-content-info {
    color: var(--cloud-notes-text-secondary-color);
    font-family: var(--cloud-notes-font-family);
    font-size: var(--cloud-notes-font-xs);
    font-style: normal;
    font-weight: 400;
    line-height: 20px;
  }

  .cloud-notes-attachment-content-button {
    display: flex;
    height: 20px;
    align-items: center;
    align-self: stretch;
    gap: 4px;
    white-space: nowrap;
    padding: 0 4px;
    color: ${unsafeCSSVarV2('button/primary')};
    font-family: var(--cloud-notes-font-family);
    font-size: var(--cloud-notes-font-xs);
    font-style: normal;
    font-weight: 500;
    text-transform: capitalize;
    line-height: 20px;

    svg {
      font-size: 16px;
    }
  }

  .cloud-notes-attachment-banner {
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .cloud-notes-attachment-card.loading {
    .cloud-notes-attachment-content-title-text {
      color: ${unsafeCSSVarV2('text/placeholder')};
    }
  }

  .cloud-notes-attachment-card.error {
    .cloud-notes-attachment-content-title-icon {
      color: ${unsafeCSSVarV2('status/error')};
    }
  }

  .cloud-notes-attachment-card.loading,
  .cloud-notes-attachment-card.error {
    background: ${unsafeCSSVarV2('layer/background/secondary')};
  }

  .cloud-notes-attachment-card.cubeThick {
    flex-direction: column-reverse;

    .cloud-notes-attachment-content {
      width: 100%;
      flex-direction: column;
      align-items: flex-start;
      justify-content: space-between;
    }

    .cloud-notes-attachment-banner {
      justify-content: space-between;
    }
  }

  .cloud-notes-attachment-embed-container {
    position: relative;
    width: 100%;
    height: 100%;
  }

  .cloud-notes-attachment-embed-status {
    position: absolute;
    left: 14px;
    bottom: 64px;
  }

  .cloud-notes-attachment-embed-event-mask {
    position: absolute;
    inset: 0;
  }
`;
