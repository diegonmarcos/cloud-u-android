import { SignalWatcher } from '@blocksuite/global/lit';
import { consume } from '@lit/context';
import { css, html, LitElement } from 'lit';
import { property } from 'lit/decorators.js';
import { classMap } from 'lit/directives/class-map.js';

import {
  type AdapterItem,
  type AdapterPanelContext,
  adapterPanelContext,
  ADAPTERS,
} from '../config';

export const CLOUD_NOTES_ADAPTER_MENU = 'cloud-notes-adapter-menu';

export class AdapterMenu extends SignalWatcher(LitElement) {
  static override styles = css`
    .adapter-menu {
      min-width: 120px;
      padding: 4px;
      background: var(--cloud-notes-background-primary-color);
      border: 1px solid var(--cloud-notes-border-color);
      border-radius: 4px;
      box-shadow: var(--cloud-notes-shadow-1);
    }
    .adapter-menu-item {
      display: block;
      width: 100%;
      padding: 6px 8px;
      border: none;
      background: none;
      text-align: left;
      cursor: pointer;
      color: var(--cloud-notes-text-primary-color);
      font-family: var(--cloud-notes-font-family);
      font-size: var(--cloud-notes-font-xs);
      border-radius: 4px;
    }
    .adapter-menu-item:hover {
      background: var(--cloud-notes-hover-color);
    }
    .adapter-menu-item.active {
      color: var(--cloud-notes-primary-color);
      background: var(--cloud-notes-hover-color);
    }
  `;

  get activeAdapter() {
    return this._context.activeAdapter$.value;
  }

  private readonly _handleAdapterChange = async (adapter: AdapterItem) => {
    this._context.activeAdapter$.value = adapter;
    this.abortController?.abort();
  };

  override render() {
    return html`<div class="adapter-menu">
      ${ADAPTERS.map(adapter => {
        const classes = classMap({
          'adapter-menu-item': true,
          active: this.activeAdapter.id === adapter.id,
        });
        return html`
          <button
            class=${classes}
            @click=${() => this._handleAdapterChange(adapter)}
          >
            ${adapter.label}
          </button>
        `;
      })}
    </div>`;
  }

  @property({ attribute: false })
  accessor abortController: AbortController | null = null;

  @consume({ context: adapterPanelContext })
  private accessor _context!: AdapterPanelContext;
}
declare global {
  interface HTMLElementTagNameMap {
    [CLOUD_NOTES_ADAPTER_MENU]: AdapterMenu;
  }
}
