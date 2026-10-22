import { type FrameBlockModel } from '@blocksuite/cloud-notes-model';
import { WidgetComponent, WidgetViewExtension } from '@blocksuite/std';
import { html } from 'lit';
import { literal, unsafeStatic } from 'lit/static-html.js';

export const CLOUD_NOTES_FRAME_TITLE_WIDGET = 'cloud-notes-frame-title-widget';

export class CloudNotesFrameTitleWidget extends WidgetComponent<FrameBlockModel> {
  override render() {
    return html`<cloud-notes-frame-title
      .model=${this.model}
      data-id=${this.model.id}
    ></cloud-notes-frame-title>`;
  }
}

export const frameTitleWidget = WidgetViewExtension(
  'cloud-notes:frame',
  CLOUD_NOTES_FRAME_TITLE_WIDGET,
  literal`${unsafeStatic(CLOUD_NOTES_FRAME_TITLE_WIDGET)}`
);
