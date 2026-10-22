import { WithDisposable } from '@blocksuite/cloud-notes/global/lit';
import { ThemeProvider } from '@blocksuite/cloud-notes/shared/services';
import { scrollbarStyle } from '@blocksuite/cloud-notes/shared/styles';
import { unsafeCSSVar } from '@blocksuite/cloud-notes/shared/theme';
import { on, stopPropagation } from '@blocksuite/cloud-notes/shared/utils';
import type { EditorHost } from '@blocksuite/cloud-notes/std';
import { darkCssVariables, lightCssVariables } from '@cloud-notes/theme';
import { css, html, LitElement, nothing, unsafeCSS } from 'lit';
import { property } from 'lit/decorators.js';

import type { AIItemGroupConfig } from '../../components/ai-item/types';

export class EdgelessCopilotPanel extends WithDisposable(LitElement) {
  static override styles = css`
    :host {
      display: flex;
      position: absolute;
      max-height: 374px;
    }

    .edgeless-copilot-panel {
      box-sizing: border-box;
      padding: 8px 4px 8px 8px;
      min-width: 330px;
      overflow-y: auto;
      overscroll-behavior: contain;
      background: ${unsafeCSSVar('--cloud-notes-background-overlay-panel-color')};
      box-shadow: ${unsafeCSSVar('--cloud-notes-overlay-shadow')};
      border-radius: 8px;
      z-index: var(--cloud-notes-z-index-popover);
    }

    .edgeless-copilot-panel[data-app-theme='light'] {
      background: ${unsafeCSS(
        lightCssVariables['--cloud-notes-background-overlay-panel-color']
      )};

      box-shadow: ${unsafeCSS(lightCssVariables['--cloud-notes-overlay-shadow'])};
    }

    .edgeless-copilot-panel[data-app-theme='dark'] {
      background: ${unsafeCSS(
        darkCssVariables['--cloud-notes-background-overlay-panel-color']
      )};

      box-shadow: ${unsafeCSS(darkCssVariables['--cloud-notes-overlay-shadow'])};
    }

    .edgeless-copilot-panel[data-app-theme='dark'] ai-item {
      background: blue;
    }

    ${scrollbarStyle('.edgeless-copilot-panel')}
    .edgeless-copilot-panel:hover::-webkit-scrollbar-thumb {
      background-color: var(--cloud-notes-black-30);
    }

    .edgeless-copilot-panel[data-app-theme='light']:hover::-webkit-scrollbar-thumb {
      background-color: ${unsafeCSS(lightCssVariables['--cloud-notes-black30'])};
    }

    .edgeless-copilot-panel[data-app-theme='dark']:hover::-webkit-scrollbar-thumb {
      background-color: ${unsafeCSS(darkCssVariables['--cloud-notes-black30'])};
    }
  `;

  private _getChain() {
    return this.host.std.command.chain();
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this._disposables.add(on(this, 'wheel', stopPropagation));
    this._disposables.add(on(this, 'pointerdown', stopPropagation));
    this.disposables.add(
      this.host.std.get(ThemeProvider).app$.subscribe(() => {
        this.requestUpdate();
      })
    );
  }

  hide() {
    this.remove();
  }

  override render() {
    const appTheme = this.host.std.get(ThemeProvider).app$.value;
    const chain = this._getChain();
    const groups = this.groups.reduce((pre, group) => {
      const filtered = group.items.filter(item =>
        item.showWhen?.(chain, 'edgeless', this.host)
      );

      if (filtered.length > 0) pre.push({ ...group, items: filtered });

      return pre;
    }, [] as AIItemGroupConfig[]);

    if (groups.every(group => group.items.length === 0)) return nothing;

    return html`
      <div class="edgeless-copilot-panel" data-app-theme=${appTheme}>
        <ai-item-list
          .theme=${appTheme}
          .onClick=${() => {
            this.onClick?.();
          }}
          .host=${this.host}
          .groups=${groups}
        ></ai-item-list>
      </div>
    `;
  }

  @property({ attribute: false })
  accessor entry: 'toolbar' | 'selection' | undefined = undefined;

  @property({ attribute: false })
  accessor groups!: AIItemGroupConfig[];

  @property({ attribute: false })
  accessor host!: EditorHost;

  @property({ attribute: false })
  accessor onClick: (() => void) | undefined = undefined;
}

declare global {
  interface HTMLElementTagNameMap {
    'edgeless-copilot-panel': EdgelessCopilotPanel;
  }
}
