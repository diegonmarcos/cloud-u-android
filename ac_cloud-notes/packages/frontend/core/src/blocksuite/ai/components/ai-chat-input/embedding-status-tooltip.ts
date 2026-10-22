import type { WorkspaceDialogService } from '@cloud-notes/core/modules/dialogs';
import { SignalWatcher } from '@blocksuite/cloud-notes/global/lit';
import { unsafeCSSVar } from '@blocksuite/cloud-notes/shared/theme';
import { css, html, LitElement } from 'lit';
import { property } from 'lit/decorators.js';
import { debounce } from 'lodash-es';

export class AIChatEmbeddingStatusTooltip extends SignalWatcher(LitElement) {
  static override styles = css`
    :host {
      width: 100%;
    }
    .embedding-status {
      display: flex;
      width: 100%;
      align-items: center;
      justify-content: space-between;
      gap: 4px;
      user-select: none;
    }
    .embedding-status-text {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 500px;
    }
    .check-status {
      padding: 4px;
      cursor: pointer;
      border-radius: 4px;
    }
    .check-status:hover {
      background-color: ${unsafeCSSVar('--affine-hover-color')};
    }
  `;

  @property({ attribute: false })
  accessor cloudNotesWorkspaceDialogService!: WorkspaceDialogService;

  override connectedCallback() {
    super.connectedCallback();
  }

  private readonly _handleCheckStatusClick = debounce(
    () => {
      this.cloudNotesWorkspaceDialogService.open('setting', {
        activeTab: 'workspace:embedding',
      });
    },
    1000,
    { leading: true }
  );

  override render() {
    return html`
      <div
        class="embedding-status"
        data-testid="ai-chat-embedding-status-tooltip"
      >
        <div class="embedding-status-text">
          Better results after embedding finished.
        </div>
        <div
          class="check-status"
          data-testid="ai-chat-embedding-status-tooltip-check"
          @click=${this._handleCheckStatusClick}
        >
          Check status
        </div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'ai-chat-embedding-status-tooltip': AIChatEmbeddingStatusTooltip;
  }
}
