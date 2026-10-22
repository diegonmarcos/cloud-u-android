import { DocDisplayMetaService } from '@cloud-notes/core/modules/doc-display-meta';
import type { Container } from '@blocksuite/cloud-notes/global/di';
import type {
  DocDisplayMetaExtension,
  DocDisplayMetaParams,
} from '@blocksuite/cloud-notes/shared/services';
import { DocDisplayMetaProvider } from '@blocksuite/cloud-notes/shared/services';
import {
  createSignalFromObservable,
  referenceToNode,
} from '@blocksuite/cloud-notes/shared/utils';
import { LifeCycleWatcher, StdIdentifier } from '@blocksuite/cloud-notes/std';
import { LinkedPageIcon, PageIcon } from '@blocksuite/icons/lit';
import { computed, type ReadonlySignal } from '@preact/signals-core';
import { type FrameworkProvider } from '@toeverything/infra';
import type { TemplateResult } from 'lit';

export function buildDocDisplayMetaExtension(framework: FrameworkProvider) {
  const docDisplayMetaService = framework.get(DocDisplayMetaService);

  function iconBuilder(
    icon: typeof PageIcon,
    size = '1.25em',
    style = 'user-select:none;flex-shrink:0;vertical-align:middle;font-size:inherit;margin-bottom:0.1em;'
  ) {
    return icon({
      width: size,
      height: size,
      style,
    });
  }

  class CloudNotesDocDisplayMetaService
    extends LifeCycleWatcher
    implements DocDisplayMetaExtension
  {
    static override key = 'doc-display-meta';

    readonly disposables: (() => void)[] = [];

    static override setup(di: Container) {
      super.setup(di);
      di.override(DocDisplayMetaProvider, this, [StdIdentifier]);
    }

    dispose() {
      while (this.disposables.length > 0) {
        this.disposables.pop()?.();
      }
    }

    icon(
      docId: string,
      { params, title, referenced }: DocDisplayMetaParams = {}
    ): ReadonlySignal<TemplateResult> {
      const icon$ = docDisplayMetaService
        .icon$(docId, {
          type: 'lit',
          title,
          reference: referenced,
          referenceToNode: referenceToNode({ pageId: docId, params }),
        })
        .map(iconBuilder);

      const { signal: iconSignal, cleanup } = createSignalFromObservable(
        icon$,
        iconBuilder(referenced ? LinkedPageIcon : PageIcon)
      );

      this.disposables.push(cleanup);

      return computed(() => iconSignal.value);
    }

    title(
      docId: string,
      { title, referenced }: DocDisplayMetaParams = {}
    ): ReadonlySignal<string> {
      const title$ = docDisplayMetaService.title$(docId, {
        title,
        reference: referenced,
      });

      const { signal: titleSignal, cleanup } =
        createSignalFromObservable<string>(title$, title ?? '');

      this.disposables.push(cleanup);

      return computed(() => titleSignal.value);
    }

    override unmounted() {
      this.dispose();
    }
  }

  return CloudNotesDocDisplayMetaService;
}
