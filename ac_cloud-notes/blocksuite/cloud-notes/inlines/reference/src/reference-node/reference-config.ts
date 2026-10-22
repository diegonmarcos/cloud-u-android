import { type BlockStdScope, ConfigExtensionFactory } from '@blocksuite/std';
import type { TemplateResult } from 'lit';

import type { CloudNotesReference } from './reference-node';

export interface ReferenceNodeConfig {
  customContent?: (reference: CloudNotesReference) => TemplateResult;
  interactable?: boolean;
  hidePopup?: boolean;
}

export const ReferenceNodeConfigExtension =
  ConfigExtensionFactory<ReferenceNodeConfig>('CloudNotesReferenceNodeConfig');

export class ReferenceNodeConfigProvider {
  private _customContent:
    | ((reference: CloudNotesReference) => TemplateResult)
    | undefined = undefined;

  private _hidePopup = false;

  private _interactable = true;

  get customContent() {
    return this._customContent;
  }

  get doc() {
    return this.std.store;
  }

  get hidePopup() {
    return this._hidePopup;
  }

  get interactable() {
    return this._interactable;
  }

  constructor(readonly std: BlockStdScope) {}

  setCustomContent(content: ReferenceNodeConfigProvider['_customContent']) {
    this._customContent = content;
  }

  setHidePopup(hidePopup: boolean) {
    this._hidePopup = hidePopup;
  }

  setInteractable(interactable: boolean) {
    this._interactable = interactable;
  }
}
