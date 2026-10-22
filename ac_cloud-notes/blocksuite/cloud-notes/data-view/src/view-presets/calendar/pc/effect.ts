import { CalendarViewUI } from './view.js';

export function pcEffects() {
  if (customElements.get('cloud-notes-data-view-calendar')) {
    return;
  }
  customElements.define('cloud-notes-data-view-calendar', CalendarViewUI);
}
