import { css, html } from 'lit';

import { EdgelessToolIconButton } from './tool-icon-button.js';

export class EdgelessToolbarButton extends EdgelessToolIconButton {
  static override styles = css`
    .icon-container {
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 0;
      color: var(--cloud-notes-icon-color);
      cursor: pointer;
    }

    .icon-container.active-mode-color[active] {
      color: var(--cloud-notes-primary-color);
    }

    .icon-container.active-mode-background[active] {
      background: var(--cloud-notes-hover-color);
    }

    .icon-container[disabled] {
      pointer-events: none;
      cursor: not-allowed;
    }

    .icon-container[coming] {
      cursor: not-allowed;
      color: var(--cloud-notes-text-disable-color);
    }
  `;

  override render() {
    return html` ${super.render()} `;
  }
}
