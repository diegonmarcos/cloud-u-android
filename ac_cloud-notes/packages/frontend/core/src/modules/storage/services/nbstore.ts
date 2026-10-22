import type { WorkerInitOptions } from '@cloud-notes/nbstore/worker/client';
import { Service } from '@toeverything/infra';

import type { NbstoreProvider } from '../providers/nbstore';

export class NbstoreService extends Service {
  constructor(private readonly nbstoreProvider: NbstoreProvider) {
    super();
  }

  openStore(key: string, options: WorkerInitOptions) {
    return this.nbstoreProvider.openStore(key, options);
  }

  get realtime() {
    return this.nbstoreProvider.realtime;
  }
}
