import type { InsertToPosition } from '@blocksuite/affine-shared/utils';
import { html, nothing, type TemplateResult } from 'lit';
import { repeat } from 'lit/directives/repeat.js';

import {
  createUniComponentFromWebComponent,
  renderUniLit,
} from '../../../core/index.js';
import {
  DataViewUIBase,
  DataViewUILogicBase,
} from '../../../core/view/data-view-base.js';
import type { GallerySingleView } from '../gallery-view-manager.js';
import { galleryViewStyles } from './styles.js';

export class GalleryViewUILogic extends DataViewUILogicBase<GallerySingleView> {
  private ui?: GalleryViewUI;

  clearSelection = () => {};

  addRow = (position: InsertToPosition) => {
    if (this.view.readonly$.value) return;
    const id = this.view.rowAdd(position);
    this.ui?.requestUpdate();
    return id;
  };

  focusFirstCell = () => {};

  showIndicator = (_evt: MouseEvent) => false;

  hideIndicator = () => {};

  moveTo = () => {};

  openCard(rowId: string) {
    this.root.openDetailPanel({ view: this.view, rowId });
  }

  createCard() {
    const rowId = this.addRow('end');
    if (rowId) this.openCard(rowId);
  }

  renderer = createUniComponentFromWebComponent(GalleryViewUI);

  attach(ui: GalleryViewUI) {
    this.ui = ui;
  }

  detach(ui: GalleryViewUI) {
    if (this.ui === ui) this.ui = undefined;
  }
}

export class GalleryViewUI extends DataViewUIBase<GalleryViewUILogic> {
  static override styles = galleryViewStyles;

  override connectedCallback(): void {
    super.connectedCallback();
    this.logic.attach(this);
    this.dataset['testid'] = 'dv-gallery-view';
  }

  override disconnectedCallback(): void {
    this.logic.detach(this);
    super.disconnectedCallback();
  }

  override render(): TemplateResult {
    const view = this.logic.view;
    this.dataset['size'] = view.size$.value;
    const cards = view.cards$.value;
    return html`
      ${this.logic.headerWidget
        ? renderUniLit(this.logic.headerWidget, { dataViewLogic: this.logic })
        : nothing}
      <div class="gallery-grid">
        ${repeat(
          cards,
          card => card.rowId,
          card => html`
            <div
              class="gallery-card"
              data-testid="gallery-card"
              @click=${() => this.logic.openCard(card.rowId)}
            >
              ${card.cover
                ? html`<img class="gallery-cover" src=${card.cover} alt="" />`
                : html`<div class="gallery-cover-empty"></div>`}
              <div class="gallery-body">
                <div class="gallery-title">${card.title}</div>
                ${card.properties.map(
                  p =>
                    html`<div class="gallery-prop">
                      ${p.name ? `${p.name}: ` : ''}${p.value}
                    </div>`
                )}
              </div>
            </div>
          `
        )}
        ${view.readonly$.value
          ? nothing
          : html`<div
              class="gallery-add"
              data-testid="gallery-add"
              @click=${() => this.logic.createCard()}
            >
              + New
            </div>`}
      </div>
      ${cards.length === 0
        ? html`<div class="gallery-empty">No rows yet.</div>`
        : nothing}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'affine-data-view-gallery': GalleryViewUI;
  }
}
