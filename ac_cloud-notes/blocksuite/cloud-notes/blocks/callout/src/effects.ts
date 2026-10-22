import { CalloutBlockComponent } from './callout-block';
import { IconPickerWrapper } from './icon-picker-wrapper';

export function effects() {
  customElements.define('cloud-notes-callout', CalloutBlockComponent);
  customElements.define('icon-picker-wrapper', IconPickerWrapper);
}

declare global {
  interface HTMLElementTagNameMap {
    'cloud-notes-callout': CalloutBlockComponent;
    'icon-picker-wrapper': IconPickerWrapper;
  }
}
