import { builtInTemplates as builtInEdgelessTemplates } from '@cloud-notes/templates/edgeless';
import { builtInTemplates as builtInStickersTemplates } from '@cloud-notes/templates/stickers';
import {
  EdgelessTemplatePanel,
  type TemplateManager,
} from '@blocksuite/cloud-notes/gfx/template';

export function registerTemplates() {
  EdgelessTemplatePanel.templates.extend(
    builtInStickersTemplates as TemplateManager
  );
  EdgelessTemplatePanel.templates.extend(
    builtInEdgelessTemplates as TemplateManager
  );
}
