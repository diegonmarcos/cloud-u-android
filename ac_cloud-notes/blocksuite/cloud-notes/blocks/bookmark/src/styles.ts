import { unsafeCSSVar, unsafeCSSVarV2 } from '@blocksuite/cloud-notes-shared/theme';
import { baseTheme } from '@cloud-notes/theme';
import { css, unsafeCSS } from 'lit';

export const styles = css`
  bookmark-card {
    display: block;
    height: 100%;
    width: 100%;
  }

  .cloud-notes-bookmark-card {
    container: cloud-notes-bookmark-card / inline-size;
    margin: 0 auto;
    box-sizing: border-box;
    display: flex;
    width: 100%;

    border-radius: 8px;
    border: 1px solid ${unsafeCSSVarV2('layer/background/tertiary')};

    background: ${unsafeCSSVarV2('layer/background/primary')};
    user-select: none;
  }

  .cloud-notes-bookmark-content {
    width: calc(100% - 204px);
    display: flex;
    flex-direction: column;
    flex-grow: 1;
    align-self: stretch;
    gap: 4px;
    padding: 12px;
  }

  .cloud-notes-bookmark-content-title {
    display: flex;
    flex-direction: row;
    gap: 8px;
    align-items: center;

    align-self: stretch;
  }

  .cloud-notes-bookmark-content-title-icon {
    display: flex;
    width: 16px;
    height: 16px;
    justify-content: center;
    align-items: center;
  }

  .cloud-notes-bookmark-content-title-icon img,
  .cloud-notes-bookmark-content-title-icon object,
  .cloud-notes-bookmark-content-title-icon svg {
    width: 16px;
    height: 16px;
    fill: var(--cloud-notes-background-primary-color);
  }

  .cloud-notes-bookmark-content-title-text {
    display: -webkit-box;
    -webkit-line-clamp: 1;
    -webkit-box-orient: vertical;

    word-break: break-word;
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--cloud-notes-text-primary-color);

    font-family: var(--cloud-notes-font-family);
    font-size: var(--cloud-notes-font-sm);
    font-style: normal;
    font-weight: 600;
    line-height: 22px;
  }

  .cloud-notes-bookmark-content-description {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;

    flex-grow: 1;

    white-space: normal;
    word-break: break-word;
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--cloud-notes-text-primary-color);

    font-family: var(--cloud-notes-font-family);
    font-size: var(--cloud-notes-font-xs);
    font-style: normal;
    font-weight: 400;
    line-height: 20px;
  }

  .cloud-notes-bookmark-content-url {
    display: flex;
    align-items: center;
    justify-content: flex-start;
    gap: 4px;
    width: max-content;
    max-width: 100%;
  }

  .cloud-notes-bookmark-content-url > span {
    display: -webkit-box;
    -webkit-line-clamp: 1;
    -webkit-box-orient: vertical;

    word-break: break-all;
    white-space: normal;
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--cloud-notes-text-secondary-color);

    font-family: ${unsafeCSS(baseTheme.fontSansFamily)};
    font-size: var(--cloud-notes-font-xs);
    font-style: normal;
    font-weight: 400;
    line-height: 20px;
  }
  .cloud-notes-bookmark-content-url:hover > span {
    color: var(--cloud-notes-link-color);
  }
  .cloud-notes-bookmark-content-url:hover {
    fill: var(--cloud-notes-link-color);
  }

  .cloud-notes-bookmark-content-url-icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 12px;
    height: 20px;
  }
  .cloud-notes-bookmark-content-url-icon {
    height: 12px;
    width: 12px;
    color: ${unsafeCSSVar('iconSecondary')};
  }

  .cloud-notes-bookmark-banner {
    margin: 12px 12px 0px 0px;
    width: 204px;
    max-width: 100%;
    height: 102px;
  }

  .cloud-notes-bookmark-banner img,
  .cloud-notes-bookmark-banner object,
  .cloud-notes-bookmark-banner svg {
    width: 204px;
    max-width: 100%;
    height: 102px;
    object-fit: cover;
    border-radius: 4px;
  }

  .cloud-notes-bookmark-card.comment-highlighted {
    outline: 2px solid ${unsafeCSSVarV2('block/comment/highlightUnderline')};
  }

  .cloud-notes-bookmark-card.loading {
    .cloud-notes-bookmark-content-title-text {
      color: var(--cloud-notes-placeholder-color);
    }
  }

  .cloud-notes-bookmark-card.error {
    .cloud-notes-bookmark-content-description {
      color: var(--cloud-notes-placeholder-color);
    }
  }

  .cloud-notes-bookmark-card.selected {
    .cloud-notes-bookmark-content-url > span {
      color: var(--cloud-notes-link-color);
    }
    .cloud-notes-bookmark-content-url .cloud-notes-bookmark-content-url-icon {
      color: var(--cloud-notes-link-color);
    }
  }

  .cloud-notes-bookmark-card.list {
    .cloud-notes-bookmark-content {
      width: 100%;
      flex-direction: row;
      align-items: center;
      justify-content: space-between;
    }

    .cloud-notes-bookmark-content-title {
      width: calc(100% - 204px);
    }

    .cloud-notes-bookmark-content-url {
      width: 204px;
      justify-content: flex-end;
    }

    .cloud-notes-bookmark-content-description {
      display: none;
    }

    .cloud-notes-bookmark-banner {
      display: none;
    }
  }

  .cloud-notes-bookmark-card.vertical {
    flex-direction: column-reverse;
    height: 100%;

    .cloud-notes-bookmark-content {
      width: 100%;
    }

    .cloud-notes-bookmark-content-description {
      -webkit-line-clamp: 6;
      max-height: 120px;
    }

    .cloud-notes-bookmark-content-url-wrapper {
      max-width: fit-content;
      display: flex;
      align-items: flex-end;
      flex-grow: 1;
      cursor: pointer;
    }

    .cloud-notes-bookmark-banner {
      width: 340px;
      height: 170px;
      margin-left: 12px;
    }

    .cloud-notes-bookmark-banner img,
    .cloud-notes-bookmark-banner object,
    .cloud-notes-bookmark-banner svg {
      width: 340px;
      height: 170px;
    }
  }

  .cloud-notes-bookmark-card.cube {
    .cloud-notes-bookmark-content {
      width: 100%;
      flex-direction: column;
      align-items: flex-start;
      justify-content: space-between;
    }

    .cloud-notes-bookmark-content-title {
      flex-direction: column;
      gap: 4px;
      align-items: flex-start;
    }

    .cloud-notes-bookmark-content-title-text {
      -webkit-line-clamp: 2;
    }

    .cloud-notes-bookmark-content-description {
      display: none;
    }

    .cloud-notes-bookmark-banner {
      display: none;
    }
  }

  @container cloud-notes-bookmark-card (width < 375px) {
    .cloud-notes-bookmark-content {
      width: 100%;
    }
    .cloud-notes-bookmark-card:not(.edgeless) .cloud-notes-bookmark-banner {
      display: none;
    }
  }
`;
