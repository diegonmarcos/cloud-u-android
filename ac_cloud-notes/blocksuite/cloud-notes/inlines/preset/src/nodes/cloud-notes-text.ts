import { cloudNotesTextStyles } from '@blocksuite/cloud-notes-shared/styles';
import type { CloudNotesTextAttributes } from '@blocksuite/cloud-notes-shared/types';
import { ShadowlessElement } from '@blocksuite/std';
import { ZERO_WIDTH_FOR_EMPTY_LINE } from '@blocksuite/std/inline';
import type { DeltaInsert } from '@blocksuite/store';
import { html } from 'lit';
import { property } from 'lit/decorators.js';
import { styleMap } from 'lit/directives/style-map.js';

export class CloudNotesText extends ShadowlessElement {
  override render() {
    const style = this.delta.attributes
      ? cloudNotesTextStyles(this.delta.attributes)
      : {};

    // we need to avoid \n appearing before and after the span element, which will
    // cause the unexpected space
    if (this.delta.attributes?.code) {
      return html`<code style=${styleMap(style)}
        ><v-text .str=${this.delta.insert}></v-text
      ></code>`;
    }

    // we need to avoid \n appearing before and after the span element, which will
    // cause the unexpected space
    return html`<span style=${styleMap(style)}
      ><v-text .str=${this.delta.insert}></v-text
    ></span>`;
  }

  @property({ type: Object })
  accessor delta: DeltaInsert<CloudNotesTextAttributes> = {
    insert: ZERO_WIDTH_FOR_EMPTY_LINE,
  };
}
