import { whenHover } from '@blocksuite/cloud-notes-components/hover';
import { RefNodeSlotsProvider } from '@blocksuite/cloud-notes-inline-reference';
import type { ReferenceInfo } from '@blocksuite/cloud-notes-model';
import {
  ParseDocUrlProvider,
  ToolbarRegistryIdentifier,
} from '@blocksuite/cloud-notes-shared/services';
import { cloudNotesTextStyles } from '@blocksuite/cloud-notes-shared/styles';
import type { CloudNotesTextAttributes } from '@blocksuite/cloud-notes-shared/types';
import { normalizeUrl } from '@blocksuite/cloud-notes-shared/utils';
import { WithDisposable } from '@blocksuite/global/lit';
import type { BlockComponent, BlockStdScope } from '@blocksuite/std';
import { BLOCK_ID_ATTR, ShadowlessElement } from '@blocksuite/std';
import {
  INLINE_ROOT_ATTR,
  type InlineRootElement,
  ZERO_WIDTH_FOR_EMPTY_LINE,
} from '@blocksuite/std/inline';
import type { DeltaInsert } from '@blocksuite/store';
import { css, html } from 'lit';
import { property } from 'lit/decorators.js';
import { type StyleInfo, styleMap } from 'lit/directives/style-map.js';

export class CloudNotesLink extends WithDisposable(ShadowlessElement) {
  static override styles = css`
    cloud-notes-link a:hover [data-v-text='true'] {
      text-decoration: underline;
    }
  `;

  // The link has been identified.
  private _identified: boolean = false;

  // see https://github.com/diegonmarcos/cloud-u-android/issues/1540
  private readonly _onMouseUp = () => {
    const anchorElement = this.querySelector('a');
    if (!anchorElement || !anchorElement.isContentEditable) return;
    anchorElement.contentEditable = 'false';
    setTimeout(() => {
      anchorElement.removeAttribute('contenteditable');
    }, 0);
  };

  private _referenceInfo: ReferenceInfo | null = null;

  openLink = (e?: MouseEvent) => {
    if (!this._identified) {
      this._identified = true;
      this._identify();
    }

    const referenceInfo = this._referenceInfo;
    if (!referenceInfo) return;

    const refNodeSlotsProvider = this.std.getOptional(RefNodeSlotsProvider);
    if (!refNodeSlotsProvider) return;

    e?.preventDefault();

    refNodeSlotsProvider.docLinkClicked.next({
      ...referenceInfo,
      openMode: e?.button === 1 ? 'open-in-new-tab' : undefined,
      host: this.std.host,
    });
  };

  _whenHover = whenHover(
    hovered => {
      const message$ = this.std.get(ToolbarRegistryIdentifier).message$;

      if (hovered) {
        message$.value = {
          flavour: 'cloud-notes:link',
          element: this,
          setFloating: this._whenHover.setFloating,
        };
        return;
      }

      // Clears previous bindings
      message$.value = null;
      this._whenHover.setFloating();
    },
    { enterDelay: 500 }
  );

  override connectedCallback() {
    super.connectedCallback();

    this._whenHover.setReference(this);

    const message$ = this.std.get(ToolbarRegistryIdentifier).message$;

    this._disposables.add(() => {
      if (message$?.value) {
        message$.value = null;
      }
      this._whenHover.dispose();
    });
  }

  // Workaround for links not working in contenteditable div
  // see also https://stackoverflow.com/questions/12059211/how-to-make-clickable-anchor-in-contenteditable-div
  //
  // Note: We cannot use JS to directly open a new page as this may be blocked by the browser.
  //
  // Please also note that when readonly mode active,
  // this workaround is not necessary and links work normally.
  get block() {
    if (!this.inlineEditor?.rootElement) return null;
    const block = this.inlineEditor.rootElement.closest<BlockComponent>(
      `[${BLOCK_ID_ATTR}]`
    );
    return block;
  }

  get inlineEditor() {
    const inlineRoot = this.closest<InlineRootElement<CloudNotesTextAttributes>>(
      `[${INLINE_ROOT_ATTR}]`
    );
    return inlineRoot?.inlineEditor;
  }

  get link() {
    return normalizeUrl(this.delta.attributes?.link ?? '');
  }

  get selfInlineRange() {
    const selfInlineRange = this.inlineEditor?.getInlineRangeFromElement(this);
    return selfInlineRange;
  }

  // Identify if url is an internal link
  private _identify() {
    const link = this.link;
    if (!link) return;

    const result = this.std.getOptional(ParseDocUrlProvider)?.parseDocUrl(link);
    if (!result) return;

    const { docId: pageId, ...params } = result;

    this._referenceInfo = { pageId, params };
  }

  private _renderLink(style: StyleInfo) {
    return html`<a
      href=${this.link}
      rel="noopener noreferrer"
      target="_blank"
      style=${styleMap(style)}
      @click=${this.openLink}
      @auxclick=${this.openLink}
      @mouseup=${this._onMouseUp}
      ><v-text .str=${this.delta.insert}></v-text
    ></a>`;
  }

  override render() {
    const linkStyle = {
      color: 'var(--cloud-notes-link-color)',
      fill: 'var(--cloud-notes-link-color)',
      cursor: 'pointer',
    };

    if (this.delta.attributes && this.delta.attributes?.code) {
      const codeStyle = cloudNotesTextStyles(this.delta.attributes);
      return html`<code style=${styleMap(codeStyle)}>
        ${this._renderLink(linkStyle)}
      </code>`;
    }

    const style = this.delta.attributes
      ? cloudNotesTextStyles(this.delta.attributes, linkStyle)
      : {};

    return this._renderLink(style);
  }

  @property({ type: Object })
  accessor delta: DeltaInsert<CloudNotesTextAttributes> = {
    insert: ZERO_WIDTH_FOR_EMPTY_LINE,
  };

  @property({ attribute: false })
  accessor std!: BlockStdScope;
}
