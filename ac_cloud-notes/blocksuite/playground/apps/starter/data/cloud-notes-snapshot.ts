import { CloudNotesSchemas } from '@blocksuite/cloud-notes/schemas';
import { Schema, Text, type Workspace } from '@blocksuite/cloud-notes/store';
import { ZipTransformer } from '@blocksuite/cloud-notes/widgets/linked-doc';
export async function cloudNotesSnapshot(collection: Workspace, id: string) {
  const doc = collection.createDoc(id);
  doc.load();
  const store = doc.getStore();
  // Add root block and surface block at root level
  const rootId = store.addBlock('affine:page', {
    title: new Text('Cloud Notes Snapshot Test'),
  });
  store.addBlock('affine:surface', {}, rootId);

  const path = '/apps/starter/data/snapshots/cloud-notes-default.zip';
  const response = await fetch(path);
  const file = await response.blob();
  const schema = new Schema();
  schema.register(CloudNotesSchemas);
  await ZipTransformer.importDocs(collection, schema, file);
}

cloudNotesSnapshot.id = 'cloud-notes-snapshot';
cloudNotesSnapshot.displayName = 'Cloud Notes Snapshot Test';
cloudNotesSnapshot.description = 'Cloud Notes Snapshot Test';
