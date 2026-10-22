/**
 * @vitest-environment happy-dom
 */

import { getInternalStoreExtensions } from '@blocksuite/cloud-notes/extensions/store';
import { StoreExtensionManager } from '@blocksuite/cloud-notes-ext-loader';
import { createCloudNotesTemplate } from '@blocksuite/cloud-notes-shared/test-utils';
import type { Store } from '@blocksuite/store';
import { describe, expect, it } from 'vitest';

import { applyPatchToDoc } from '../../../../blocksuite/ai/utils/apply-model/apply-patch-to-doc';
import type { PatchOp } from '../../../../blocksuite/ai/utils/apply-model/markdown-diff';

declare module 'vitest' {
  interface Assertion<T = any> {
    toEqualDoc(expected: Store, options?: { compareId?: boolean }): T;
  }
}

const manager = new StoreExtensionManager(getInternalStoreExtensions());
const { cloudNotes } = createCloudNotesTemplate(manager.get('store'));

describe('applyPatchToDoc', () => {
  it('should delete a block', async () => {
    const host = cloudNotes`
    <cloud-notes-page id="page">
      <cloud-notes-note id="note">
        <cloud-notes-paragraph id="paragraph-1">Hello</cloud-notes-paragraph>
        <cloud-notes-paragraph id="paragraph-2">World</cloud-notes-paragraph>
      </cloud-notes-note>
    </cloud-notes-page>
  `;

    const patch: PatchOp[] = [{ op: 'delete', id: 'paragraph-1' }];
    await applyPatchToDoc(host.store, patch);

    const expected = cloudNotes`
      <cloud-notes-page id="page">
        <cloud-notes-note id="note">
          <cloud-notes-paragraph id="paragraph-2">World</cloud-notes-paragraph>
        </cloud-notes-note>
      </cloud-notes-page>
    `;

    expect(host.store).toEqualDoc(expected.store, {
      compareId: true,
    });
  });

  // FIXME: markdown parse error in test mode
  it.skip('should replace a block', async () => {
    const host = cloudNotes`
    <cloud-notes-page id="page">
      <cloud-notes-note id="note">
        <cloud-notes-paragraph id="paragraph-1">Hello</cloud-notes-paragraph>
        <cloud-notes-paragraph id="paragraph-2">World</cloud-notes-paragraph>
      </cloud-notes-note>
    </cloud-notes-page>
  `;

    const patch: PatchOp[] = [
      {
        op: 'replace',
        id: 'paragraph-1',
        content: 'New content',
      },
    ];

    await applyPatchToDoc(host.store, patch);

    const expected = cloudNotes`
      <cloud-notes-page id="page">
        <cloud-notes-note id="note">
          <cloud-notes-paragraph id="paragraph-1">New content</cloud-notes-paragraph>
          <cloud-notes-paragraph id="paragraph-2">World</cloud-notes-paragraph>
        </cloud-notes-note>
      </cloud-notes-page>
    `;

    expect(host.store).toEqualDoc(expected.store, {
      compareId: true,
    });
  });

  // FIXME: markdown parse error in test mode
  it.skip('should insert a block at index', async () => {
    const host = cloudNotes`
    <cloud-notes-page id="page">
      <cloud-notes-note id="note">
        <cloud-notes-paragraph id="paragraph-1">Hello</cloud-notes-paragraph>
        <cloud-notes-paragraph id="paragraph-2">World</cloud-notes-paragraph>
      </cloud-notes-note>
    </cloud-notes-page>
  `;

    const patch: PatchOp[] = [
      {
        op: 'insert',
        index: 2,
        after: 'paragraph-1',
        block: {
          id: 'paragraph-3',
          type: 'affine:paragraph',
          content: 'Inserted',
        },
      },
    ];

    await applyPatchToDoc(host.store, patch);

    const expected = cloudNotes`
      <cloud-notes-page id="page">
        <cloud-notes-note id="note">
          <cloud-notes-paragraph id="paragraph-1">Hello</cloud-notes-paragraph>
          <cloud-notes-paragraph id="paragraph-2">World</cloud-notes-paragraph>
          <cloud-notes-paragraph id="paragraph-3">Inserted</cloud-notes-paragraph>
        </cloud-notes-note>
      </cloud-notes-page>
    `;

    expect(host.store).toEqualDoc(expected.store, {
      compareId: true,
    });
  });
});
