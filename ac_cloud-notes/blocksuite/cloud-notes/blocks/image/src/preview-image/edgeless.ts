import { unsafeCSSVarV2 } from '@blocksuite/cloud-notes-shared/theme';
import { toGfxBlockComponent } from '@blocksuite/std';
import { css } from 'lit';

import { ImagePlaceholderBlockComponent } from './page.js';

export class ImageEdgelessPlaceholderBlockComponent extends toGfxBlockComponent(
  ImagePlaceholderBlockComponent
) {
  static override styles = css`
    cloud-notes-edgeless-placeholder-preview-image
      .cloud-notes-placeholder-preview-container {
      border: 1px solid ${unsafeCSSVarV2('layer/background/tertiary')};
    }
  `;

  override renderGfxBlock(): unknown {
    return super.renderGfxBlock();
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'cloud-notes-edgeless-placeholder-preview-image': ImageEdgelessPlaceholderBlockComponent;
  }
}
