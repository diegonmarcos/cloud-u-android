import type { CopilotChatHistoryFragment } from '@cloud-notes/graphql';
import { WithDisposable } from '@blocksuite/cloud-notes/global/lit';
import { type NotificationService } from '@blocksuite/cloud-notes/shared/services';
import { unsafeCSSVarV2 } from '@blocksuite/cloud-notes/shared/theme';
import { ShadowlessElement } from '@blocksuite/cloud-notes/std';
import type { Store } from '@blocksuite/cloud-notes/store';
import { css, html } from 'lit';
import { property } from 'lit/decorators.js';

import type { ChatContextValue } from '../ai-chat-content';

export class AIHistoryClear extends WithDisposable(ShadowlessElement) {
  @property({ attribute: false })
  accessor chatContextValue!: ChatContextValue;

  @property({ attribute: false })
  accessor session!: CopilotChatHistoryFragment | null | undefined;

  @property({ attribute: false })
  accessor notificationService!: NotificationService;

  @property({ attribute: false })
  accessor doc!: Store;

  @property({ attribute: false })
  accessor onHistoryCleared!: () => void;

  @property({ attribute: false })
  accessor onClearHistory!: (sessionIds: string[]) => Promise<void> | void;

  static override styles = css`
    .chat-history-clear {
      cursor: pointer;
      color: ${unsafeCSSVarV2('icon/primary')};
    }
    .chat-history-clear[aria-disabled='true'] {
      cursor: not-allowed;
      color: ${unsafeCSSVarV2('icon/secondary')};
    }
  `;

  private get _isHistoryClearDisabled() {
    return (
      this.chatContextValue.status === 'loading' ||
      this.chatContextValue.status === 'transmitting' ||
      !this.chatContextValue.messages.length ||
      !this.session
    );
  }

  private readonly _cleanupHistories = async () => {
    if (this._isHistoryClearDisabled || !this.session) {
      return;
    }
    const sessionId = this.session.sessionId;
    try {
      const confirm = await this.notificationService.confirm({
        title: 'Clear History',
        message:
          'Are you sure you want to clear all history? This action will permanently delete all content, including all chat logs and data, and cannot be undone.',
        confirmText: 'Confirm',
        cancelText: 'Cancel',
      });

      if (confirm) {
        const actionIds = this.chatContextValue.messages
          .filter(item => 'sessionId' in item)
          .map(item => item.sessionId);
        await this.onClearHistory([
          ...(sessionId ? [sessionId] : []),
          ...(actionIds || []),
        ]);
        this.notificationService.toast('History cleared');
        this.onHistoryCleared?.();
      }
    } catch {
      this.notificationService.toast('Failed to clear history');
    }
  };

  override render() {
    return html`
      <div
        class="chat-history-clear"
        aria-disabled=${this._isHistoryClearDisabled}
        @click=${this._cleanupHistories}
        data-testid="chat-panel-clear"
      >
        Clear
      </div>
    `;
  }
}
