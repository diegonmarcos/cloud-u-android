import {
  menu,
  popMenu,
  popupTargetFromElement,
} from '@blocksuite/affine-components/context-menu';
import {
  BaseCellRenderer,
  createFromBaseCellRenderer,
  createIcon,
  ensureMirror,
  findMirror,
  type RelationData,
  type RollupData,
  ROLLUP_FNS,
} from '@blocksuite/data-view';
import { css } from '@emotion/css';
import { signal } from '@preact/signals-core';
import { html, nothing } from 'lit';

import {
  relationPropertyModelConfig,
  rollupPropertyModelConfig,
} from './define.js';

const chips = css({
  display: 'flex',
  flexWrap: 'wrap',
  gap: '4px',
  alignItems: 'center',
  width: '100%',
  minHeight: '28px',
  padding: '2px 4px',
  boxSizing: 'border-box',
  cursor: 'pointer',
});
const chip = css({
  padding: '0 8px',
  borderRadius: '10px',
  fontSize: '13px',
  lineHeight: '22px',
  background: 'var(--affine-hover-color)',
  maxWidth: '160px',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
});
const hint = css({ color: 'var(--affine-text-secondary-color)', fontSize: '13px' });

/** Relation cell: chips with the related rows' titles; click to choose the target database and the rows. */
export class RelationCell extends BaseCellRenderer<string[], string[], RelationData> {
  private get ctx() {
    return this.view.manager.dataSource.relationContext?.();
  }

  private openEditor = () => {
    const ctx = this.ctx;
    if (!ctx || this.readonly) return;
    const { space, databaseId } = ctx;
    const propertyId = this.property.id;
    const selected = signal<string[]>([...(this.value ?? [])]);
    const version = signal(0);
    const data = () => this.property.data$.value as RelationData;
    const isMirror = () => !!data().mirrorOf;

    popMenu(popupTargetFromElement(this), {
      options: {
        onClose: () => this.selectCurrentCell(false),
        items: [
          menu.dynamic(() => {
            version.value; // re-render after a change
            const d = data();
            const items = [];
            if (!isMirror()) {
              for (const db of space.databases()) {
                items.push(
                  menu.action({
                    name: db.name,
                    prefix: html`<span class="${hint}">Target</span>`,
                    isSelected: d.targetDatabaseId === db.id,
                    closeOnSelect: false,
                    select: () => {
                      this.property.dataUpdate(old => ({
                        ...old,
                        targetDatabaseId: db.id,
                      }));
                      selected.value = [];
                      version.value++;
                      return false;
                    },
                  })
                );
              }
            }
            const target = d.targetDatabaseId;
            if (target) {
              for (const id of space.rowIds(target)) {
                const title = space.rowTitle(target, id) || 'Untitled';
                items.push(
                  menu.action({
                    name: title,
                    isSelected: selected.value.includes(id),
                    closeOnSelect: false,
                    select: () => {
                      selected.value = selected.value.includes(id)
                        ? selected.value.filter(x => x !== id)
                        : [...selected.value, id];
                      this.valueSetImmediate(selected.value);
                      return false;
                    },
                  })
                );
              }
              if (!isMirror() && !findMirror(space, databaseId, propertyId)) {
                items.push(
                  menu.action({
                    name: 'Also show this relation in the other database',
                    closeOnSelect: false,
                    select: () => {
                      ensureMirror(space, databaseId, propertyId);
                      version.value++;
                      return false;
                    },
                  })
                );
              }
            }
            return items;
          }),
        ],
      },
    });
  };

  override afterEnterEditingMode() {
    this.openEditor();
  }

  override render() {
    const ctx = this.ctx;
    const target = (this.property.data$.value as RelationData).targetDatabaseId;
    const ids = this.value ?? [];
    if (!ctx || !target) {
      return html`<div class="${chips}"><span class="${hint}">Choose a database</span></div>`;
    }
    return html`<div class="${chips}">
      ${ids.length
        ? ids.map(
            id =>
              html`<span class="${chip}">${ctx.space.rowTitle(target, id) || 'Untitled'}</span>`
          )
        : nothing}
    </div>`;
  }
}

export const relationColumnConfig = relationPropertyModelConfig.createPropertyMeta({
  icon: createIcon('LinkedPageIcon'),
  cellRenderer: {
    view: createFromBaseCellRenderer(RelationCell),
  },
});

/** Rollup cell: the computed number; click to choose the relation, the property and the function. */
export class RollupCell extends BaseCellRenderer<number | null, number | null, RollupData> {
  private openEditor = () => {
    const ctx = this.view.manager.dataSource.relationContext?.();
    if (!ctx || this.readonly) return;
    const { space, databaseId } = ctx;
    const version = signal(0);
    const set = (patch: Partial<RollupData>) => {
      this.property.dataUpdate(old => ({ ...old, ...patch }));
      version.value++;
    };
    popMenu(popupTargetFromElement(this), {
      options: {
        onClose: () => this.selectCurrentCell(false),
        items: [
          menu.dynamic(() => {
            version.value;
            const d = this.property.data$.value as RollupData;
            const relations = space
              .columns(databaseId)
              .filter(c => c.type === 'relation');
            const relation = relations.find(r => r.id === d.relationPropertyId);
            const target = (relation?.data as RelationData | undefined)?.targetDatabaseId;
            const items = relations.map(r =>
              menu.action({
                name: r.name,
                prefix: html`<span class="${hint}">Relation</span>`,
                isSelected: r.id === d.relationPropertyId,
                closeOnSelect: false,
                select: () => {
                  set({ relationPropertyId: r.id, targetPropertyId: undefined });
                  return false;
                },
              })
            );
            if (target) {
              items.push(
                menu.action({
                  name: 'Title',
                  prefix: html`<span class="${hint}">Property</span>`,
                  isSelected: d.targetPropertyId === 'title',
                  closeOnSelect: false,
                  select: () => {
                    set({ targetPropertyId: 'title' });
                    return false;
                  },
                })
              );
              for (const c of space.columns(target)) {
                if (c.type === 'rollup') continue;
                items.push(
                  menu.action({
                    name: c.name,
                    prefix: html`<span class="${hint}">Property</span>`,
                    isSelected: d.targetPropertyId === c.id,
                    closeOnSelect: false,
                    select: () => {
                      set({ targetPropertyId: c.id });
                      return false;
                    },
                  })
                );
              }
            }
            for (const f of ROLLUP_FNS) {
              items.push(
                menu.action({
                  name: f.label,
                  prefix: html`<span class="${hint}">Calculate</span>`,
                  isSelected: (d.fn ?? 'count') === f.fn,
                  closeOnSelect: false,
                  select: () => {
                    set({ fn: f.fn });
                    return false;
                  },
                })
              );
            }
            return items;
          }),
        ],
      },
    });
  };

  override beforeEnterEditMode() {
    return true;
  }

  override afterEnterEditingMode() {
    this.openEditor();
  }

  override render() {
    const v = this.value;
    const text =
      v == null ? '' : Number.isInteger(v) ? String(v) : String(Math.round(v * 100) / 100);
    return html`<div class="${chips}">${text || html`<span class="${hint}">Set up the rollup</span>`}</div>`;
  }
}

export const rollupColumnConfig = rollupPropertyModelConfig.createPropertyMeta({
  icon: createIcon('DatabaseTableViewIcon'),
  cellRenderer: {
    view: createFromBaseCellRenderer(RollupCell),
  },
});
