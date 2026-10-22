import {
  PreconditionStrategy,
  registerCloudNotesCommand,
} from '@cloud-notes/core/commands';
import { useSharingUrl } from '@cloud-notes/core/components/hooks/cloud-notes/use-share-url';
import { getDefaultShareMode } from '@cloud-notes/core/components/hooks/cloud-notes/use-share-url.utils';
import { EditorService } from '@cloud-notes/core/modules/editor';
import { useIsActiveView } from '@cloud-notes/core/modules/workbench';
import type { WorkspaceMetadata } from '@cloud-notes/core/modules/workspace';
import { track } from '@cloud-notes/track';
import { useLiveData, useService } from '@toeverything/infra';
import { useEffect } from 'react';

export function useRegisterCopyLinkCommands({
  workspaceMeta,
  docId,
}: {
  workspaceMeta: WorkspaceMetadata;
  docId: string;
}) {
  const isActiveView = useIsActiveView();
  const workspaceId = workspaceMeta.id;
  const isCloud = workspaceMeta.flavour !== 'local';
  const currentMode = useLiveData(useService(EditorService).editor.mode$);

  const { onClickCopyLink } = useSharingUrl({
    workspaceId,
    pageId: docId,
  });

  useEffect(() => {
    if (!isActiveView) {
      return;
    }
    const unsubs: Array<() => void> = [];

    unsubs.push(
      registerCloudNotesCommand({
        id: `cloud-notes:share-private-link:${docId}`,
        category: 'cloud-notes:general',
        preconditionStrategy: PreconditionStrategy.Never,
        keyBinding: {
          binding: '$mod+Shift+c',
        },
        label: '',
        icon: null,
        run() {
          track.$.cmdk.general.copyShareLink();
          isActiveView &&
            isCloud &&
            onClickCopyLink(getDefaultShareMode(currentMode));
        },
      })
    );
    return () => {
      unsubs.forEach(unsub => unsub());
    };
  }, [currentMode, docId, isActiveView, isCloud, onClickCopyLink]);
}
