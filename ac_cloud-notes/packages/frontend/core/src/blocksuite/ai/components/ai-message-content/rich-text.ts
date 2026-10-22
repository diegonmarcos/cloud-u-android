import type { FeatureFlagService } from '@cloud-notes/core/modules/feature-flag';
import { WithDisposable } from '@blocksuite/cloud-notes/global/lit';
import type { ColorScheme } from '@blocksuite/cloud-notes/model';
import { ShadowlessElement } from '@blocksuite/cloud-notes/std';
import type { ExtensionType } from '@blocksuite/cloud-notes/store';
import type { Signal } from '@preact/signals-core';
import { html } from 'lit';
import { property } from 'lit/decorators.js';

import { createTextRenderer } from '../../components/text-renderer';

export class ChatContentRichText extends WithDisposable(ShadowlessElement) {
  @property({ attribute: false })
  accessor text!: string;

  @property({ attribute: false })
  accessor state: 'finished' | 'generating' = 'finished';

  @property({ attribute: false })
  accessor extensions!: ExtensionType[];

  @property({ attribute: false })
  accessor cloudNotesFeatureFlagService!: FeatureFlagService;

  @property({ attribute: false })
  accessor theme!: Signal<ColorScheme>;

  protected override render() {
    const { text } = this;
    return html`${createTextRenderer({
      customHeading: true,
      extensions: this.extensions,
      cloudNotesFeatureFlagService: this.cloudNotesFeatureFlagService,
      theme: this.theme,
      scrollable: false,
    })(text, this.state)}`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'chat-content-rich-text': ChatContentRichText;
  }
}
