import type { WorkerInitOptions } from '@cloud-notes/nbstore/worker/client';
import { Scope } from '@toeverything/infra';

import type { WorkspaceOpenOptions } from '../open-options';

export class WorkspaceScope extends Scope<{
  openOptions: WorkspaceOpenOptions;
  engineWorkerInitOptions: WorkerInitOptions;
}> {}
