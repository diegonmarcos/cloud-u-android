import { CheckboxCell } from './checkbox/cell-renderer.js';
import { DateCell } from './date/cell-renderer.js';
import { ImageCell } from './image/cell-renderer.js';
import { MultiSelectCell } from './multi-select/cell-renderer.js';
import { NumberCell } from './number/cell-renderer.js';
import { ProgressCell } from './progress/cell-renderer.js';
import { SelectCell } from './select/cell-renderer.js';
import { TextCell } from './text/cell-renderer.js';

export function propertyPresetsEffects() {
  customElements.define('cloud-notes-database-checkbox-cell', CheckboxCell);
  customElements.define('cloud-notes-database-date-cell', DateCell);
  customElements.define('cloud-notes-database-image-cell', ImageCell);
  customElements.define('cloud-notes-database-multi-select-cell', MultiSelectCell);
  customElements.define('cloud-notes-database-number-cell', NumberCell);
  customElements.define('cloud-notes-database-progress-cell', ProgressCell);
  customElements.define('cloud-notes-database-select-cell', SelectCell);
  customElements.define('cloud-notes-database-text-cell', TextCell);
}
