import { AdapterPanel, CLOUD_NOTES_ADAPTER_PANEL } from './adapter-panel';
import {
  AdapterPanelBody,
  CLOUD_NOTES_ADAPTER_PANEL_BODY,
} from './body/adapter-panel-body';
import { AdapterMenu, CLOUD_NOTES_ADAPTER_MENU } from './header/adapter-menu';
import {
  AdapterPanelHeader,
  CLOUD_NOTES_ADAPTER_PANEL_HEADER,
} from './header/adapter-panel-header';

export function effects() {
  customElements.define(CLOUD_NOTES_ADAPTER_PANEL, AdapterPanel);
  customElements.define(CLOUD_NOTES_ADAPTER_MENU, AdapterMenu);
  customElements.define(CLOUD_NOTES_ADAPTER_PANEL_HEADER, AdapterPanelHeader);
  customElements.define(CLOUD_NOTES_ADAPTER_PANEL_BODY, AdapterPanelBody);
}
