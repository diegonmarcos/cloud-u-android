import { CaptionedBlockComponent } from '@blocksuite/cloud-notes-components/caption';
import { whenHover } from '@blocksuite/cloud-notes-components/hover';
import { LoadingIcon } from '@blocksuite/cloud-notes-components/icons';
import { Peekable } from '@blocksuite/cloud-notes-components/peek';
import { ResourceController } from '@blocksuite/cloud-notes-components/resource';
import type { ImageBlockModel } from '@blocksuite/cloud-notes-model';
import { ImageSelection } from '@blocksuite/cloud-notes-shared/selection';
import {
  BlockElementCommentManager,
  ToolbarRegistryIdentifier,
} from '@blocksuite/cloud-notes-shared/services';
import { formatSize } from '@blocksuite/cloud-notes-shared/utils';
import { IS_MOBILE } from '@blocksuite/global/env';
import { BrokenImageIcon, ImageIcon } from '@blocksuite/icons/lit';
import { BlockSelection } from '@blocksuite/std';
import { computed } from '@preact/signals-core';
import { cssVarV2 } from '@cloud-notes/theme/v2';
import { html } from 'lit';
import { query } from 'lit/decorators.js';
import { styleMap } from 'lit/directives/style-map.js';
import { when } from 'lit/directives/when.js';

import type { ImageBlockPageComponent } from './components/page-image-block';
import {
  copyImageBlob,
  downloadImageBlob,
  refreshData,
  turnImageIntoCardView,
} from './utils';

@Peekable({
  enableOn: () => !IS_MOBILE,
})
export class ImageBlockComponent extends CaptionedBlockComponent<ImageBlockModel> {
  resizeable$ = computed(() =>
    this.std.selection.value.some(
      selection =>
        selection.is(ImageSelection) && selection.blockId === this.blockId
    )
  );

  resourceController = new ResourceController(
    computed(() => this.model.props.sourceId$.value),
    'Image'
  );

  get blobUrl() {
    return this.resourceController.blobUrl$.value;
  }

  convertToCardView = () => {
    turnImageIntoCardView(this).catch(console.error);
  };

  copy = () => {
    copyImageBlob(this).catch(console.error);
  };

  download = () => {
    downloadImageBlob(this).catch(console.error);
  };

  refreshData = () => {
    refreshData(this).catch(console.error);
  };

  get resizableImg() {
    return this.pageImage?.resizeImg;
  }

  get isCommentHighlighted() {
    return (
      this.std
        .getOptional(BlockElementCommentManager)
        ?.isBlockCommentHighlighted(this.model) ?? false
    );
  }

  private _handleClick(event: MouseEvent) {
    // the peek view need handle shift + click
    if (event.defaultPrevented) return;

    event.stopPropagation();
    const selectionManager = this.host.selection;
    const blockSelection = selectionManager.create(BlockSelection, {
      blockId: this.blockId,
    });
    selectionManager.setGroup('note', [blockSelection]);
  }

  private _initHover() {
    const { setReference, setFloating, dispose } = whenHover(
      hovered => {
        const message$ = this.std.get(ToolbarRegistryIdentifier).message$;
        if (hovered) {
          message$.value = {
            flavour: this.model.flavour,
            element: this,
            setFloating,
          };
          return;
        }

        // Clears previous bindings
        message$.value = null;
        setFloating();
      },
      { enterDelay: 500 }
    );
    setReference(this.hoverableContainer);
    this._disposables.add(dispose);
  }

  override connectedCallback() {
    super.connectedCallback();

    this.contentEditable = 'false';

    this.resourceController.setEngine(this.std.store.blobSync);

    this.disposables.add(this.resourceController.subscribe());
    this.disposables.add(this.resourceController);

    this.disposables.add(
      this.model.props.sourceId$.subscribe(() => {
        this.refreshData();
      })
    );
  }

  override firstUpdated() {
    // lazy bindings
    this.disposables.addFromEvent(this, 'click', this._handleClick);
    this._initHover();
  }

  override renderBlock() {
    const blobUrl = this.blobUrl;
    const { size = 0 } = this.model.props;

    const containerStyleMap = styleMap({
      position: 'relative',
      width: '100%',
    });

    const alignItemsStyleMap = styleMap({
      alignItems:
        this.model.props.textAlign$.value === 'left'
          ? 'flex-start'
          : this.model.props.textAlign$.value === 'right'
            ? 'flex-end'
            : undefined,
    });

    const resovledState = this.resourceController.resolveStateWith({
      loadingIcon: LoadingIcon({
        strokeColor: cssVarV2('button/pureWhiteText'),
        ringColor: cssVarV2('loading/imageLoadingLayer', '#ffffff8f'),
      }),
      errorIcon: BrokenImageIcon(),
      icon: ImageIcon(),
      title: 'Image',
      description: formatSize(size),
    });

    return html`
      <div class="cloud-notes-image-container" style=${containerStyleMap}>
        ${when(
          blobUrl,
          () =>
            html`<cloud-notes-page-image
              .block=${this}
              .state=${resovledState}
              style="${alignItemsStyleMap}"
            ></cloud-notes-page-image>`,
          () =>
            html`<cloud-notes-image-fallback-card
              .state=${resovledState}
            ></cloud-notes-image-fallback-card>`
        )}
      </div>

      ${Object.values(this.widgets)}
    `;
  }

  override accessor blockContainerStyles = { margin: '18px 0' };

  @query('cloud-notes-page-image')
  private accessor pageImage: ImageBlockPageComponent | null = null;

  @query('.cloud-notes-image-container')
  accessor hoverableContainer!: HTMLDivElement;

  override accessor useCaptionEditor = true;

  override accessor useZeroWidth = true;
}

declare global {
  interface HTMLElementTagNameMap {
    'cloud-notes-image': ImageBlockComponent;
  }
}
