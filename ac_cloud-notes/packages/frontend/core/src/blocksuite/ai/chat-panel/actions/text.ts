import './action-wrapper';

import { WithDisposable } from '@blocksuite/cloud-notes/global/lit';
import { unsafeCSSVar } from '@blocksuite/cloud-notes/shared/theme';
import type { EditorHost } from '@blocksuite/cloud-notes/std';
import { ThemeProvider } from '@blocksuite/cloud-notes-shared/services';
import { css, html, LitElement } from 'lit';
import { property } from 'lit/decorators.js';
import { styleMap } from 'lit/directives/style-map.js';

import { type ChatAction } from '../../components/ai-chat-messages';
import { createTextRenderer } from '../../components/text-renderer';

export class ActionText extends WithDisposable(LitElement) {
  static override styles = css`
    .original-text {
      border-radius: 4px;
      margin-bottom: 12px;
      font-size: var(--cloud-notes-font-sm);
      line-height: 22px;
      max-height: 200px;
      overflow-y: auto;
    }

    .original-text::-webkit-scrollbar {
      width: 4px;
      height: 4px;
    }

    .original-text::-webkit-scrollbar-thumb {
      background-color: ${unsafeCSSVar('borderColor')};
    }

    .original-text::-webkit-scrollbar-track {
      background: transparent;
    }
  `;

  @property({ attribute: false })
  accessor item!: ChatAction;

  @property({ attribute: false })
  accessor host!: EditorHost;

  @property({ attribute: false })
  accessor isCode = false;

  protected override render() {
    const originalText = this.item.messages[1].content;
    const { isCode } = this;

    return html` <action-wrapper .host=${this.host} .item=${this.item}>
      <div
        style=${styleMap({
          padding: isCode ? '0' : '10px 16px',
          border: isCode ? 'none' : '1px solid var(--cloud-notes-border-color)',
        })}
        class="original-text"
        data-testid="original-text"
      >
        ${createTextRenderer({
          customHeading: true,
          theme: this.host.std.get(ThemeProvider).app$,
        })(originalText)}
      </div>
    </action-wrapper>`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'action-text': ActionText;
  }
}
