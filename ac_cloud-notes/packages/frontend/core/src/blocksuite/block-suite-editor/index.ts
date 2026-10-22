import { registerAIEditorEffects } from '@cloud-notes/core/blocksuite/ai/effects/editor';
import { editorEffects } from '@cloud-notes/core/blocksuite/editors';

import { registerTemplates } from './register-templates';

editorEffects();
registerAIEditorEffects();
registerTemplates();

export * from './blocksuite-editor';
