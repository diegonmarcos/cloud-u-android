import type { DatabaseBlockModel } from '@blocksuite/affine-model';
import type { RelationColumn, RelationSpace } from '@blocksuite/data-view';
import type { Store } from '@blocksuite/store';

import { addProperty, getCell, updateCell } from '../../utils/block-utils.js';

/** RelationSpace over the database blocks of one doc (Store). Reads go through the models' signals. */
export class StoreRelationSpace implements RelationSpace {
  constructor(private readonly store: Store) {}

  private model(id: string): DatabaseBlockModel | undefined {
    const m = this.store.getModelById(id);
    return m?.flavour === 'affine:database'
      ? (m as DatabaseBlockModel)
      : undefined;
  }

  databases() {
    return (this.store.getModelsByFlavour('affine:database') as DatabaseBlockModel[]).map(
      m => ({ id: m.id, name: m.props.title?.toString() || 'Untitled database' })
    );
  }

  rowIds(databaseId: string) {
    return this.model(databaseId)?.children.map(c => c.id) ?? [];
  }

  rowTitle(databaseId: string, rowId: string) {
    const row = this.model(databaseId)?.children.find(c => c.id === rowId);
    return (row as { text?: { toString(): string } } | undefined)?.text?.toString() ?? '';
  }

  columns(databaseId: string): RelationColumn[] {
    const model = this.model(databaseId);
    if (!model) return [];
    return model.props.columns$.value.map(c => ({
      id: c.id,
      name: c.name,
      type: c.type,
      data: c.data as Record<string, unknown>,
    }));
  }

  rawCell(databaseId: string, rowId: string, propertyId: string) {
    const model = this.model(databaseId);
    return model ? getCell(model, rowId, propertyId)?.value : undefined;
  }

  setRawCell(databaseId: string, rowId: string, propertyId: string, value: unknown) {
    const model = this.model(databaseId);
    if (model) updateCell(model, rowId, { columnId: propertyId, value });
  }

  addColumn(databaseId: string, column: Omit<RelationColumn, 'id'>) {
    const model = this.model(databaseId);
    if (!model) throw new Error(`no database ${databaseId}`);
    return addProperty(model, 'end', column);
  }
}
