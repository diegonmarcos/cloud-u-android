import { Tooltip } from './tooltip.js';

export function effects() {
  if (!customElements.get('cloud-notes-tooltip')) {
    customElements.define('cloud-notes-tooltip', Tooltip);
  }
}
