import { Modal } from '@cloud-notes/component';
import { CollectionService } from '@cloud-notes/core/modules/collection';
import type {
  DialogComponentProps,
  WORKSPACE_DIALOG_SCHEMA,
} from '@cloud-notes/core/modules/dialogs';
import { useI18n } from '@cloud-notes/i18n';
import { ViewLayersIcon } from '@blocksuite/icons/rc';
import { useLiveData, useService } from '@toeverything/infra';
import { cssVarV2 } from '@toeverything/theme/v2';
import { useMemo } from 'react';

import { GenericSelector } from './generic-selector';

export const CollectionSelectorDialog = ({
  close,
  init,
  onBeforeConfirm,
}: DialogComponentProps<WORKSPACE_DIALOG_SCHEMA['collection-selector']>) => {
  const t = useI18n();
  const collectionService = useService(CollectionService);
  const collections = useLiveData(collectionService.collectionMetas$);

  const list = useMemo(() => {
    return collections.map(collection => ({
      id: collection.id,
      icon: <ViewLayersIcon />,
      label: collection.name,
    }));
  }, [collections]);

  return (
    <Modal
      open
      onOpenChange={() => close()}
      withoutCloseButton
      fullScreen
      contentOptions={{
        style: {
          background: cssVarV2('layer/background/secondary'),
          padding: 0,
        },
      }}
    >
      <GenericSelector
        onBack={close}
        onConfirm={close}
        onBeforeConfirm={onBeforeConfirm}
        initial={init}
        data={list}
        typeName={t[`com.cloud-notes.m.selector.type-collection`]()}
      />
    </Modal>
  );
};
