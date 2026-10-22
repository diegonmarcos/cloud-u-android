import { unsafeCSSVarV2 } from '@blocksuite/cloud-notes-shared/theme';
import { css, html } from 'lit';

export const styles = css`
  .cloud-notes-embed-youtube-block {
    box-sizing: border-box;
    width: 100%;
    height: 100%;

    display: flex;
    flex-direction: column;
    gap: 20px;
    padding: 12px;

    border-radius: 8px;
    border: 1px solid ${unsafeCSSVarV2('layer/background/tertiary')};

    background: ${unsafeCSSVarV2('layer/background/primary')};
    user-select: none;
  }

  .cloud-notes-embed-youtube-video {
    flex-grow: 1;
    width: 100%;
  }

  .cloud-notes-embed-youtube-video img,
  .cloud-notes-embed-youtube-video object,
  .cloud-notes-embed-youtube-video svg {
    width: 100%;
    height: 100%;
    object-fit: cover;
    border-radius: 4px;
  }

  .cloud-notes-embed-youtube-video-iframe-container {
    position: relative;
    height: 100%;
  }

  .cloud-notes-embed-youtube-video-iframe-container > iframe {
    width: 100%;
    height: 100%;
    border-radius: 4px;
  }

  .cloud-notes-embed-youtube-video-iframe-overlay {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
  }

  .cloud-notes-embed-youtube-video-iframe-overlay.hide {
    display: none;
  }

  .cloud-notes-embed-youtube-content {
    display: block;
    flex-direction: column;
    width: 100%;
    height: fit-content;
  }

  .cloud-notes-embed-youtube-content-header {
    display: flex;
    flex-direction: row;
    gap: 8px;
    align-items: center;

    align-self: stretch;
  }

  .cloud-notes-embed-youtube-content-title-icon {
    display: flex;
    width: 20px;
    height: 20px;
    justify-content: center;
    align-items: center;
  }

  .cloud-notes-embed-youtube-content-title-icon img,
  .cloud-notes-embed-youtube-content-title-icon object,
  .cloud-notes-embed-youtube-content-title-icon svg {
    width: 20px;
    height: 20px;
    fill: var(--cloud-notes-background-primary-color);
  }

  .cloud-notes-embed-youtube-content-title-text {
    flex: 1 0 0;

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

  .cloud-notes-embed-youtube-content-creator-image {
    display: flex;
    width: 16px;
    height: 16px;
    flex-direction: column;
    align-items: flex-start;
  }

  .cloud-notes-embed-youtube-content-creator-image img,
  .cloud-notes-embed-youtube-content-creator-image object,
  .cloud-notes-embed-youtube-content-creator-image svg {
    width: 16px;
    height: 16px;
    border-radius: 50%;
    fill: var(--cloud-notes-background-primary-color);
  }

  .cloud-notes-embed-youtube-content-creator-text {
    display: -webkit-box;
    -webkit-line-clamp: 1;
    -webkit-box-orient: vertical;

    color: var(--cloud-notes-text-primary-color);
    text-align: justify;
    font-family: var(--cloud-notes-font-family);
    font-size: var(--cloud-notes-font-xs);
    font-style: normal;
    font-weight: 400;
    line-height: 20px;
  }

  .cloud-notes-embed-youtube-content-description {
    height: 40px;

    position: relative;

    word-break: break-word;
    white-space: normal;
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--cloud-notes-text-primary-color);

    font-family: var(--cloud-notes-font-family);
    font-size: var(--cloud-notes-font-xs);
    font-style: normal;
    font-weight: 400;
    line-height: 20px;
  }

  .cloud-notes-embed-youtube-content-description::after {
    content: '...';
    position: absolute;
    right: 0;
    bottom: 0;
    background-color: var(--cloud-notes-background-primary-color);
  }

  .cloud-notes-embed-youtube-content-url {
    display: flex;
    align-items: center;
    justify-content: flex-start;
    gap: 4px;
    width: max-content;
    max-width: 100%;
    cursor: pointer;
  }
  .cloud-notes-embed-youtube-content-url > span {
    display: -webkit-box;
    -webkit-line-clamp: 1;
    -webkit-box-orient: vertical;

    word-break: break-all;
    white-space: normal;
    overflow: hidden;
    text-overflow: ellipsis;
    color: ${unsafeCSSVarV2('icon/primary')};

    font-family: var(--cloud-notes-font-family);
    font-size: var(--cloud-notes-font-xs);
    font-style: normal;
    font-weight: 400;
    line-height: 20px;
  }
  .cloud-notes-embed-youtube-content-url:hover > span {
    color: var(--cloud-notes-link-color);
  }
  .cloud-notes-embed-youtube-content-url:hover .open-icon {
    fill: var(--cloud-notes-link-color);
  }

  .cloud-notes-embed-youtube-content-url-icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 12px;
    height: 12px;
  }
  .cloud-notes-embed-youtube-content-url-icon svg {
    height: 12px;
    width: 12px;
    fill: ${unsafeCSSVarV2('icon/primary')};
  }

  .cloud-notes-embed-youtube-block.loading {
    .cloud-notes-embed-youtube-content-title-text {
      color: var(--cloud-notes-placeholder-color);
    }
  }

  .cloud-notes-embed-youtube-block.selected {
    .cloud-notes-embed-youtube-content-url > span {
      color: var(--cloud-notes-link-color);
    }
    .cloud-notes-embed-youtube-content-url .open-icon {
      fill: var(--cloud-notes-link-color);
    }
  }
`;

export const YoutubeIcon = html`<svg
  width="20"
  height="20"
  viewBox="0 0 20 20"
  fill="none"
  xmlns="http://www.w3.org/2000/svg"
>
  <path
    d="M17.5672 5.99344C17.4763 5.64032 17.2992 5.3184 17.0533 5.05972C16.8075 4.80104 16.5015 4.61464 16.1659 4.51907C14.9372 4.1665 9.99202 4.1665 9.99202 4.1665C9.99202 4.1665 5.04662 4.17718 3.81791 4.52974C3.48228 4.62532 3.1763 4.81173 2.93045 5.07042C2.6846 5.32911 2.50744 5.65105 2.41664 6.00417C2.04498 8.30113 1.90081 11.8012 2.42684 14.0062C2.51766 14.3594 2.69482 14.6813 2.94067 14.94C3.18652 15.1986 3.49249 15.385 3.82811 15.4806C5.05683 15.8332 10.0021 15.8332 10.0021 15.8332C10.0021 15.8332 14.9473 15.8332 16.176 15.4806C16.5116 15.385 16.8176 15.1986 17.0635 14.94C17.3093 14.6813 17.4865 14.3594 17.5773 14.0062C17.9693 11.706 18.0901 8.20821 17.5672 5.9935V5.99344Z"
    fill="#FF0000"
  />
  <path d="M8.33325 12.5L12.4999 10L8.33325 7.5V12.5Z" fill="white" />
</svg>`;
