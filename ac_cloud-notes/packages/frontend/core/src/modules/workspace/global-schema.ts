import { AIChatBlockSchema } from '@cloud-notes/core/blocksuite/ai/blocks/ai-chat-block/model';
import { TranscriptionBlockSchema } from '@cloud-notes/core/blocksuite/ai/blocks/transcription-block/model';
import { CloudNotesSchemas } from '@blocksuite/cloud-notes/schemas';
import { Schema } from '@blocksuite/cloud-notes/store';

let _schema: Schema | null = null;
export function getCloudNotesWorkspaceSchema() {
  if (!_schema) {
    _schema = new Schema();

    _schema.register([
      ...CloudNotesSchemas,
      AIChatBlockSchema,
      TranscriptionBlockSchema,
    ]);
  }

  return _schema;
}
