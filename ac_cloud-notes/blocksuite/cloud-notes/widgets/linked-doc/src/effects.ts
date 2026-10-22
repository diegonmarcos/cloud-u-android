import { CLOUD_NOTES_LINKED_DOC_WIDGET } from './config.js';
import { ImportDoc } from './import-doc/import-doc.js';
import { Loader } from './import-doc/loader.js';
import { CloudNotesLinkedDocWidget } from './index.js';
import { LinkedDocPopover } from './linked-doc-popover.js';
import { CloudNotesMobileLinkedDocMenu } from './mobile-linked-doc-menu.js';

export function effects() {
  customElements.define('cloud-notes-linked-doc-popover', LinkedDocPopover);
  customElements.define(CLOUD_NOTES_LINKED_DOC_WIDGET, CloudNotesLinkedDocWidget);
  customElements.define('import-doc', ImportDoc);
  customElements.define(
    'cloud-notes-mobile-linked-doc-menu',
    CloudNotesMobileLinkedDocMenu
  );
  customElements.define('loader-element', Loader);
}
