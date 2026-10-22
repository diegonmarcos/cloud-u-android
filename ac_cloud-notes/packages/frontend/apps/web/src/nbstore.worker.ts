import '@cloud-notes/core/bootstrap/browser';

import { broadcastChannelStorages } from '@cloud-notes/nbstore/broadcast-channel';
import { cloudStorages } from '@cloud-notes/nbstore/cloud';
import { idbStorages } from '@cloud-notes/nbstore/idb';
import { idbV1Storages } from '@cloud-notes/nbstore/idb/v1';
import {
  StoreManagerConsumer,
  type WorkerManagerOps,
} from '@cloud-notes/nbstore/worker/consumer';
import { type MessageCommunicapable, OpConsumer } from '@toeverything/infra/op';

const consumer = new StoreManagerConsumer([
  ...idbStorages,
  ...idbV1Storages,
  ...broadcastChannelStorages,
  ...cloudStorages,
]);

if ('onconnect' in globalThis) {
  // if in shared worker

  (globalThis as any).onconnect = (event: MessageEvent) => {
    const port = event.ports[0];
    consumer.bindConsumer(new OpConsumer<WorkerManagerOps>(port));
  };
} else {
  // if in worker
  consumer.bindConsumer(
    new OpConsumer<WorkerManagerOps>(globalThis as MessageCommunicapable)
  );
}
