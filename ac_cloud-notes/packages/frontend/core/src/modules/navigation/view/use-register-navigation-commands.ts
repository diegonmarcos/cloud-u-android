import {
  PreconditionStrategy,
  registerCloudNotesCommand,
} from '@cloud-notes/core/commands';
import { track } from '@cloud-notes/track';
import { useService } from '@toeverything/infra';
import { useEffect } from 'react';

import { NavigatorService } from '../services/navigator';

export function useRegisterNavigationCommands() {
  const navigator = useService(NavigatorService).navigator;
  useEffect(() => {
    const unsubs: Array<() => void> = [];

    unsubs.push(
      registerCloudNotesCommand({
        id: 'cloud-notes:shortcut-history-go-back',
        category: 'cloud-notes:general',
        preconditionStrategy: PreconditionStrategy.Never,
        icon: 'none',
        label: 'go back',
        keyBinding: {
          binding: '$mod+[',
        },
        run() {
          track.$.cmdk.general.goBack();

          navigator.back();
        },
      })
    );
    unsubs.push(
      registerCloudNotesCommand({
        id: 'cloud-notes:shortcut-history-go-forward',
        category: 'cloud-notes:general',
        preconditionStrategy: PreconditionStrategy.Never,
        icon: 'none',
        label: 'go forward',
        keyBinding: {
          binding: '$mod+]',
        },
        run() {
          track.$.cmdk.general.goForward();

          navigator.forward();
        },
      })
    );

    return () => {
      unsubs.forEach(unsub => unsub());
    };
  }, [navigator]);
}
